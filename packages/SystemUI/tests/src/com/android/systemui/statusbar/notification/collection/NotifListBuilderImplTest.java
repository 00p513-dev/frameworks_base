/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.notification.collection;

import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.systemui.statusbar.notification.collection.ListDumper.dumpList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.ArrayMap;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.collection.NotifListBuilderImpl.OnRenderListListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeRenderListListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeSortListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeTransformGroupsListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifComparator;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.SectionsProvider;
import com.android.systemui.statusbar.notification.logging.NotifLog;
import com.android.systemui.util.Assert;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotifListBuilderImplTest extends SysuiTestCase {

    private NotifListBuilderImpl mListBuilder;
    private FakeSystemClock mSystemClock = new FakeSystemClock();

    @Mock private NotifLog mNotifLog;
    @Mock private NotifCollection mNotifCollection;
    @Spy private OnBeforeTransformGroupsListener mOnBeforeTransformGroupsListener;
    @Spy private OnBeforeSortListener mOnBeforeSortListener;
    @Spy private OnBeforeRenderListListener mOnBeforeRenderListListener;
    @Spy private OnRenderListListener mOnRenderListListener = list -> mBuiltList = list;

    @Captor private ArgumentCaptor<CollectionReadyForBuildListener> mBuildListenerCaptor;

    private CollectionReadyForBuildListener mReadyForBuildListener;
    private List<NotificationEntryBuilder> mPendingSet = new ArrayList<>();
    private List<NotificationEntry> mEntrySet = new ArrayList<>();
    private List<ListEntry> mBuiltList;

    private Map<String, Integer> mNextIdMap = new ArrayMap<>();
    private int mNextRank = 0;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Assert.sMainLooper = TestableLooper.get(this).getLooper();

        mListBuilder = new NotifListBuilderImpl(mSystemClock, mNotifLog);
        mListBuilder.setOnRenderListListener(mOnRenderListListener);

        mListBuilder.attach(mNotifCollection);

        Mockito.verify(mNotifCollection).setBuildListener(mBuildListenerCaptor.capture());
        mReadyForBuildListener = checkNotNull(mBuildListenerCaptor.getValue());
    }

    @Test
    public void testNotifsAreSortedByRankAndWhen() {
        // GIVEN a simple pipeline

        // WHEN a series of notifs with jumbled ranks are added
        addNotif(0, PACKAGE_1).setRank(2);
        addNotif(1, PACKAGE_2).setRank(4).modifyNotification(mContext).setWhen(22);
        addNotif(2, PACKAGE_3).setRank(4).modifyNotification(mContext).setWhen(33);
        addNotif(3, PACKAGE_3).setRank(3);
        addNotif(4, PACKAGE_5).setRank(4).modifyNotification(mContext).setWhen(11);
        addNotif(5, PACKAGE_3).setRank(1);
        addNotif(6, PACKAGE_1).setRank(0);
        dispatchBuild();

        // The final output is sorted based first by rank and then by when
        verifyBuiltList(
                notif(6),
                notif(5),
                notif(0),
                notif(3),
                notif(2),
                notif(1),
                notif(4)
        );
    }

    @Test
    public void testNotifsAreGrouped() {
        // GIVEN a simple pipeline

        // WHEN a group is added
        addGroupChild(0, PACKAGE_1, GROUP_1);
        addGroupChild(1, PACKAGE_1, GROUP_1);
        addGroupChild(2, PACKAGE_1, GROUP_1);
        addGroupSummary(3, PACKAGE_1, GROUP_1);
        dispatchBuild();

        // THEN the notifs are grouped together
        verifyBuiltList(
                group(
                        summary(3),
                        child(0),
                        child(1),
                        child(2)
                )
        );
    }

    @Test
    public void testNotifsWithDifferentGroupKeysAreGrouped() {
        // GIVEN a simple pipeline

        // WHEN a package posts two different groups
        addGroupChild(0, PACKAGE_1, GROUP_1);
        addGroupChild(1, PACKAGE_1, GROUP_2);
        addGroupSummary(2, PACKAGE_1, GROUP_2);
        addGroupChild(3, PACKAGE_1, GROUP_2);
        addGroupChild(4, PACKAGE_1, GROUP_1);
        addGroupChild(5, PACKAGE_1, GROUP_2);
        addGroupChild(6, PACKAGE_1, GROUP_1);
        addGroupSummary(7, PACKAGE_1, GROUP_1);
        dispatchBuild();

        // THEN the groups are separated separately
        verifyBuiltList(
                group(
                        summary(2),
                        child(1),
                        child(3),
                        child(5)
                ),
                group(
                        summary(7),
                        child(0),
                        child(4),
                        child(6)
                )
        );
    }

    @Test
    public void testNotifsNotifChildrenAreSorted() {
        // GIVEN a simple pipeline

        // WHEN a group is added
        addGroupChild(0, PACKAGE_1, GROUP_1).setRank(4);
        addGroupChild(1, PACKAGE_1, GROUP_1).setRank(2)
                .modifyNotification(mContext).setWhen(11);
        addGroupChild(2, PACKAGE_1, GROUP_1).setRank(1);
        addGroupChild(3, PACKAGE_1, GROUP_1).setRank(2)
                .modifyNotification(mContext).setWhen(33);
        addGroupChild(4, PACKAGE_1, GROUP_1).setRank(2)
                .modifyNotification(mContext).setWhen(22);
        addGroupChild(5, PACKAGE_1, GROUP_1).setRank(0);
        addGroupSummary(6, PACKAGE_1, GROUP_1).setRank(3);
        dispatchBuild();

        // THEN the children are sorted by rank and when
        verifyBuiltList(
                group(
                        summary(6),
                        child(5),
                        child(2),
                        child(3),
                        child(4),
                        child(1),
                        child(0)
                )
        );
    }

    @Test
    public void testDuplicateGroupSummariesAreDiscarded() {
        // GIVEN a simple pipeline

        // WHEN a group with multiple summaries is added
        addNotif(0, PACKAGE_3);
        addGroupChild(1, PACKAGE_1, GROUP_1);
        addGroupChild(2, PACKAGE_1, GROUP_1);
        addGroupSummary(3, PACKAGE_1, GROUP_1).setPostTime(22);
        addGroupSummary(4, PACKAGE_1, GROUP_1).setPostTime(33);
        addNotif(5, PACKAGE_2);
        addGroupSummary(6, PACKAGE_1, GROUP_1).setPostTime(11);
        addGroupChild(7, PACKAGE_1, GROUP_1);
        dispatchBuild();

        // THEN only most recent summary is used
        verifyBuiltList(
                notif(0),
                group(
                        summary(4),
                        child(1),
                        child(2),
                        child(7)
                ),
                notif(5)
        );

        // THEN the extra summaries have their parents set to null
        assertNull(mEntrySet.get(3).getParent());
        assertNull(mEntrySet.get(6).getParent());
    }

    @Test
    public void testGroupsWithNoSummaryAreUngrouped() {
        // GIVEN a group with no summary
        addNotif(0, PACKAGE_2);
        addGroupChild(1, PACKAGE_4, GROUP_2);
        addGroupChild(2, PACKAGE_4, GROUP_2);
        addGroupChild(3, PACKAGE_4, GROUP_2);
        addGroupChild(4, PACKAGE_4, GROUP_2);

        // WHEN we build the list
        dispatchBuild();

        // THEN the children aren't grouped
        verifyBuiltList(
                notif(0),
                notif(1),
                notif(2),
                notif(3),
                notif(4)
        );
    }

    @Test
    public void testGroupsWithNoChildrenAreUngrouped() {
        // GIVEN a group with a summary but no children
        addGroupSummary(0, PACKAGE_5, GROUP_1);
        addNotif(1, PACKAGE_5);
        addNotif(2, PACKAGE_1);

        // WHEN we build the list
        dispatchBuild();

        // THEN the summary isn't grouped but is still added to the final list
        verifyBuiltList(
                notif(0),
                notif(1),
                notif(2)
        );
    }

    @Test
    public void testGroupsWithTooFewChildrenAreSplitUp() {
        // GIVEN a group with one child
        addGroupChild(0, PACKAGE_2, GROUP_1);
        addGroupSummary(1, PACKAGE_2, GROUP_1);

        // WHEN we build the list
        dispatchBuild();

        // THEN the child is added at top level and the summary is discarded
        verifyBuiltList(
                notif(0)
        );

        assertNull(mEntrySet.get(1).getParent());
    }

    @Test
    public void testGroupsWhoLoseChildrenMidPipelineAreSplitUp() {
        // GIVEN a group with two children
        addGroupChild(0, PACKAGE_2, GROUP_1);
        addGroupSummary(1, PACKAGE_2, GROUP_1);
        addGroupChild(2, PACKAGE_2, GROUP_1);

        // GIVEN a promoter that will promote one of children to top level
        mListBuilder.addPromoter(new IdPromoter(0));

        // WHEN we build the list
        dispatchBuild();

        // THEN both children end up at top level (because group is now too small)
        verifyBuiltList(
                notif(0),
                notif(2)
        );

        // THEN the summary is discarded
        assertNull(mEntrySet.get(1).getParent());
    }

    @Test
    public void testPreviousParentsAreSetProperly() {
        // GIVEN a notification that is initially added to the list
        PackageFilter filter = new PackageFilter(PACKAGE_2);
        filter.setEnabled(false);
        mListBuilder.addPreGroupFilter(filter);

        addNotif(0, PACKAGE_1);
        addNotif(1, PACKAGE_2);
        addNotif(2, PACKAGE_3);
        dispatchBuild();

        // WHEN it is suddenly filtered out
        filter.setEnabled(true);
        dispatchBuild();

        // THEN its previous parent indicates that it used to be added
        assertNull(mEntrySet.get(1).getParent());
        assertEquals(GroupEntry.ROOT_ENTRY, mEntrySet.get(1).getPreviousParent());
    }

    @Test
    public void testThatAnnulledGroupsAndSummariesAreProperlyRolledBack() {
        // GIVEN a registered transform groups listener
        RecordingOnBeforeTransformGroupsListener listener =
                new RecordingOnBeforeTransformGroupsListener();
        mListBuilder.addOnBeforeTransformGroupsListener(listener);

        // GIVEN a malformed group that will be dismantled
        addGroupChild(0, PACKAGE_2, GROUP_1);
        addGroupSummary(1, PACKAGE_2, GROUP_1);
        addNotif(2, PACKAGE_1);

        // WHEN we build the list
        dispatchBuild();

        // THEN only the child appears in the final list
        verifyBuiltList(
                notif(0),
                notif(2)
        );

        // THEN the summary has a null parent and an unset firstAddedIteration
        assertNull(mEntrySet.get(1).getParent());
        assertEquals(-1, mEntrySet.get(1).mFirstAddedIteration);
    }

    @Test
    public void testPreGroupNotifsAreFiltered() {
        // GIVEN a PreGroupNotifFilter and PreRenderFilter that filters out the same package
        NotifFilter preGroupFilter = spy(new PackageFilter(PACKAGE_2));
        NotifFilter preRenderFilter = spy(new PackageFilter(PACKAGE_2));
        mListBuilder.addPreGroupFilter(preGroupFilter);
        mListBuilder.addPreRenderFilter(preRenderFilter);

        // WHEN the pipeline is kicked off on a list of notifs
        addNotif(0, PACKAGE_1);
        addNotif(1, PACKAGE_2);
        addNotif(2, PACKAGE_3);
        addNotif(3, PACKAGE_2);
        dispatchBuild();

        // THEN the preGroupFilter is called on each notif in the original set
        verify(preGroupFilter).shouldFilterOut(eq(mEntrySet.get(0)), anyLong());
        verify(preGroupFilter).shouldFilterOut(eq(mEntrySet.get(1)), anyLong());
        verify(preGroupFilter).shouldFilterOut(eq(mEntrySet.get(2)), anyLong());
        verify(preGroupFilter).shouldFilterOut(eq(mEntrySet.get(3)), anyLong());

        // THEN the preRenderFilter is only called on the notifications not already filtered out
        verify(preRenderFilter).shouldFilterOut(eq(mEntrySet.get(0)), anyLong());
        verify(preRenderFilter, never()).shouldFilterOut(eq(mEntrySet.get(1)), anyLong());
        verify(preRenderFilter).shouldFilterOut(eq(mEntrySet.get(2)), anyLong());
        verify(preRenderFilter, never()).shouldFilterOut(eq(mEntrySet.get(3)), anyLong());

        // THEN the final list doesn't contain any filtered-out notifs
        verifyBuiltList(
                notif(0),
                notif(2)
        );

        // THEN each filtered notif records the NotifFilter that did it
        assertEquals(preGroupFilter, mEntrySet.get(1).mExcludingFilter);
        assertEquals(preGroupFilter, mEntrySet.get(3).mExcludingFilter);
    }

    @Test
    public void testPreRenderNotifsAreFiltered() {
        // GIVEN a NotifFilter that filters out a specific package
        NotifFilter filter1 = spy(new PackageFilter(PACKAGE_2));
        mListBuilder.addPreRenderFilter(filter1);

        // WHEN the pipeline is kicked off on a list of notifs
        addNotif(0, PACKAGE_1);
        addNotif(1, PACKAGE_2);
        addNotif(2, PACKAGE_3);
        addNotif(3, PACKAGE_2);
        dispatchBuild();

        // THEN the filter is called on each notif in the original set
        verify(filter1).shouldFilterOut(eq(mEntrySet.get(0)), anyLong());
        verify(filter1).shouldFilterOut(eq(mEntrySet.get(1)), anyLong());
        verify(filter1).shouldFilterOut(eq(mEntrySet.get(2)), anyLong());
        verify(filter1).shouldFilterOut(eq(mEntrySet.get(3)), anyLong());

        // THEN the final list doesn't contain any filtered-out notifs
        verifyBuiltList(
                notif(0),
                notif(2)
        );

        // THEN each filtered notif records the filter that did it
        assertEquals(filter1, mEntrySet.get(1).mExcludingFilter);
        assertEquals(filter1, mEntrySet.get(3).mExcludingFilter);
    }

    @Test
    public void testNotifFiltersCanBePreempted() {
        // GIVEN two notif filters
        NotifFilter filter1 = spy(new PackageFilter(PACKAGE_2));
        NotifFilter filter2 = spy(new PackageFilter(PACKAGE_5));
        mListBuilder.addPreGroupFilter(filter1);
        mListBuilder.addPreGroupFilter(filter2);

        // WHEN the pipeline is kicked off on a list of notifs
        addNotif(0, PACKAGE_1);
        addNotif(1, PACKAGE_2);
        addNotif(2, PACKAGE_5);
        dispatchBuild();

        // THEN both filters are called on the first notif but the second filter is never called
        // on the already-filtered second notif
        verify(filter1).shouldFilterOut(eq(mEntrySet.get(0)), anyLong());
        verify(filter1).shouldFilterOut(eq(mEntrySet.get(1)), anyLong());
        verify(filter1).shouldFilterOut(eq(mEntrySet.get(2)), anyLong());
        verify(filter2).shouldFilterOut(eq(mEntrySet.get(0)), anyLong());
        verify(filter2).shouldFilterOut(eq(mEntrySet.get(2)), anyLong());

        // THEN the final list doesn't contain any filtered-out notifs
        verifyBuiltList(
                notif(0)
        );

        // THEN each filtered notif records the filter that did it
        assertEquals(filter1, mEntrySet.get(1).mExcludingFilter);
        assertEquals(filter2, mEntrySet.get(2).mExcludingFilter);
    }

    @Test
    public void testNotifsArePromoted() {
        // GIVEN a NotifPromoter that promotes certain notif IDs
        NotifPromoter promoter = spy(new IdPromoter(1, 2));
        mListBuilder.addPromoter(promoter);

        // WHEN the pipeline is kicked off
        addNotif(0, PACKAGE_1);
        addGroupChild(1, PACKAGE_2, GROUP_1);
        addGroupChild(2, PACKAGE_2, GROUP_1);
        addGroupChild(3, PACKAGE_2, GROUP_1);
        addGroupChild(4, PACKAGE_2, GROUP_1);
        addGroupSummary(5, PACKAGE_2, GROUP_1);
        addNotif(6, PACKAGE_3);
        dispatchBuild();

        // THEN the filter is called on each group child
        verify(promoter).shouldPromoteToTopLevel(mEntrySet.get(1));
        verify(promoter).shouldPromoteToTopLevel(mEntrySet.get(2));
        verify(promoter).shouldPromoteToTopLevel(mEntrySet.get(3));
        verify(promoter).shouldPromoteToTopLevel(mEntrySet.get(4));

        // THEN the final list contains the promoted entries at top level
        verifyBuiltList(
                notif(0),
                notif(2),
                notif(3),
                group(
                        summary(5),
                        child(1),
                        child(4)),
                notif(6)
        );

        // THEN each promoted notif records the promoter that did it
        assertEquals(promoter, mEntrySet.get(2).mNotifPromoter);
        assertEquals(promoter, mEntrySet.get(3).mNotifPromoter);
    }

    @Test
    public void testNotifPromotersCanBePreempted() {
        // GIVEN two notif promoters
        NotifPromoter promoter1 = spy(new IdPromoter(1));
        NotifPromoter promoter2 = spy(new IdPromoter(2));
        mListBuilder.addPromoter(promoter1);
        mListBuilder.addPromoter(promoter2);

        // WHEN the pipeline is kicked off on some notifs and a group
        addNotif(0, PACKAGE_1);
        addGroupChild(1, PACKAGE_2, GROUP_1);
        addGroupChild(2, PACKAGE_2, GROUP_1);
        addGroupChild(3, PACKAGE_2, GROUP_1);
        addGroupSummary(4, PACKAGE_2, GROUP_1);
        addNotif(5, PACKAGE_3);
        dispatchBuild();

        // THEN both promoters are called on each child, except for children that a previous
        // promoter has already promoted
        verify(promoter1).shouldPromoteToTopLevel(mEntrySet.get(1));
        verify(promoter1).shouldPromoteToTopLevel(mEntrySet.get(2));
        verify(promoter1).shouldPromoteToTopLevel(mEntrySet.get(3));

        verify(promoter2).shouldPromoteToTopLevel(mEntrySet.get(1));
        verify(promoter2).shouldPromoteToTopLevel(mEntrySet.get(3));

        // THEN each promoter is recorded on each notif it promoted
        assertEquals(promoter1, mEntrySet.get(2).mNotifPromoter);
        assertEquals(promoter2, mEntrySet.get(3).mNotifPromoter);
    }

    @Test
    public void testNotifsAreSectioned() {
        // GIVEN a filter that removes all PACKAGE_4 notifs and a SectionsProvider that divides
        // notifs based on package name
        mListBuilder.addPreGroupFilter(new PackageFilter(PACKAGE_4));
        final SectionsProvider sectionsProvider = spy(new PackageSectioner());
        mListBuilder.setSectionsProvider(sectionsProvider);

        // WHEN we build a list with different packages
        addNotif(0, PACKAGE_4);
        addNotif(1, PACKAGE_2);
        addNotif(2, PACKAGE_1);
        addNotif(3, PACKAGE_3);
        addGroupSummary(4, PACKAGE_2, GROUP_1);
        addGroupChild(5, PACKAGE_2, GROUP_1);
        addGroupChild(6, PACKAGE_2, GROUP_1);
        addNotif(7, PACKAGE_1);
        addNotif(8, PACKAGE_2);
        addNotif(9, PACKAGE_5);
        addNotif(10, PACKAGE_4);
        dispatchBuild();

        // THEN the list is sorted according to section
        verifyBuiltList(
                notif(2),
                notif(7),
                notif(1),
                group(
                        summary(4),
                        child(5),
                        child(6)
                ),
                notif(8),
                notif(3),
                notif(9)
        );

        // THEN the sections provider is called on all top level elements (but no children and no
        // entries that were filtered out)
        verify(sectionsProvider).getSection(mEntrySet.get(1));
        verify(sectionsProvider).getSection(mEntrySet.get(2));
        verify(sectionsProvider).getSection(mEntrySet.get(3));
        verify(sectionsProvider).getSection(mEntrySet.get(7));
        verify(sectionsProvider).getSection(mEntrySet.get(8));
        verify(sectionsProvider).getSection(mEntrySet.get(9));
        verify(sectionsProvider).getSection(mBuiltList.get(3));
    }

    @Test
    public void testThatNotifComparatorsAreCalled() {
        // GIVEN a set of comparators that care about specific packages
        mListBuilder.setComparators(Arrays.asList(
                new HypeComparator(PACKAGE_4),
                new HypeComparator(PACKAGE_1, PACKAGE_3),
                new HypeComparator(PACKAGE_2)
        ));

        // WHEN the pipeline is kicked off on a bunch of notifications
        addNotif(0, PACKAGE_1);
        addNotif(1, PACKAGE_5);
        addNotif(2, PACKAGE_3);
        addNotif(3, PACKAGE_4);
        addNotif(4, PACKAGE_2);
        dispatchBuild();

        // THEN the notifs are sorted according to the hierarchy of comparators
        verifyBuiltList(
                notif(3),
                notif(0),
                notif(2),
                notif(4),
                notif(1)
        );
    }

    @Test
    public void testListenersAndPluggablesAreFiredInOrder() {
        // GIVEN a bunch of registered listeners and pluggables
        NotifFilter preGroupFilter = spy(new PackageFilter(PACKAGE_1));
        NotifPromoter promoter = spy(new IdPromoter(3));
        PackageSectioner sectioner = spy(new PackageSectioner());
        NotifComparator comparator = spy(new HypeComparator(PACKAGE_4));
        NotifFilter preRenderFilter = spy(new PackageFilter(PACKAGE_5));
        mListBuilder.addPreGroupFilter(preGroupFilter);
        mListBuilder.addOnBeforeTransformGroupsListener(mOnBeforeTransformGroupsListener);
        mListBuilder.addPromoter(promoter);
        mListBuilder.addOnBeforeSortListener(mOnBeforeSortListener);
        mListBuilder.setComparators(Collections.singletonList(comparator));
        mListBuilder.setSectionsProvider(sectioner);
        mListBuilder.addOnBeforeRenderListListener(mOnBeforeRenderListListener);
        mListBuilder.addPreRenderFilter(preRenderFilter);

        // WHEN a few new notifs are added
        addNotif(0, PACKAGE_1);
        addGroupSummary(1, PACKAGE_2, GROUP_1);
        addGroupChild(2, PACKAGE_2, GROUP_1);
        addGroupChild(3, PACKAGE_2, GROUP_1);
        addNotif(4, PACKAGE_5);
        addNotif(5, PACKAGE_5);
        addNotif(6, PACKAGE_4);
        dispatchBuild();

        // THEN the pluggables and listeners are called in order
        InOrder inOrder = inOrder(
                preGroupFilter,
                mOnBeforeTransformGroupsListener,
                promoter,
                mOnBeforeSortListener,
                sectioner,
                comparator,
                preRenderFilter,
                mOnBeforeRenderListListener,
                mOnRenderListListener);

        inOrder.verify(preGroupFilter, atLeastOnce())
                .shouldFilterOut(any(NotificationEntry.class), anyLong());
        inOrder.verify(mOnBeforeTransformGroupsListener)
                .onBeforeTransformGroups(anyList());
        inOrder.verify(promoter, atLeastOnce())
                .shouldPromoteToTopLevel(any(NotificationEntry.class));
        inOrder.verify(mOnBeforeSortListener).onBeforeSort(anyList());
        inOrder.verify(sectioner, atLeastOnce()).getSection(any(ListEntry.class));
        inOrder.verify(comparator, atLeastOnce())
                .compare(any(ListEntry.class), any(ListEntry.class));
        inOrder.verify(preRenderFilter, atLeastOnce())
                .shouldFilterOut(any(NotificationEntry.class), anyLong());
        inOrder.verify(mOnBeforeRenderListListener).onBeforeRenderList(anyList());
        inOrder.verify(mOnRenderListListener).onRenderList(anyList());
    }

    @Test
    public void testThatPluggableInvalidationsTriggersRerun() {
        // GIVEN a variety of pluggables
        NotifFilter packageFilter = new PackageFilter(PACKAGE_1);
        NotifPromoter idPromoter = new IdPromoter(4);
        SectionsProvider sectionsProvider = new PackageSectioner();
        NotifComparator hypeComparator = new HypeComparator(PACKAGE_2);

        mListBuilder.addPreGroupFilter(packageFilter);
        mListBuilder.addPromoter(idPromoter);
        mListBuilder.setSectionsProvider(sectionsProvider);
        mListBuilder.setComparators(Collections.singletonList(hypeComparator));

        // GIVEN a set of random notifs
        addNotif(0, PACKAGE_1);
        addNotif(1, PACKAGE_2);
        addNotif(2, PACKAGE_3);
        dispatchBuild();

        // WHEN each pluggable is invalidated THEN the list is re-rendered

        clearInvocations(mOnRenderListListener);
        packageFilter.invalidateList();
        verify(mOnRenderListListener).onRenderList(anyList());

        clearInvocations(mOnRenderListListener);
        idPromoter.invalidateList();
        verify(mOnRenderListListener).onRenderList(anyList());

        clearInvocations(mOnRenderListListener);
        sectionsProvider.invalidateList();
        verify(mOnRenderListListener).onRenderList(anyList());

        clearInvocations(mOnRenderListListener);
        hypeComparator.invalidateList();
        verify(mOnRenderListListener).onRenderList(anyList());
    }

    @Test
    public void testNotifFiltersAreAllSentTheSameNow() {
        // GIVEN three notif filters
        NotifFilter filter1 = spy(new PackageFilter(PACKAGE_5));
        NotifFilter filter2 = spy(new PackageFilter(PACKAGE_5));
        NotifFilter filter3 = spy(new PackageFilter(PACKAGE_5));
        mListBuilder.addPreGroupFilter(filter1);
        mListBuilder.addPreGroupFilter(filter2);
        mListBuilder.addPreGroupFilter(filter3);

        // GIVEN the SystemClock is set to a particular time:
        mSystemClock.setUptimeMillis(10047);

        // WHEN the pipeline is kicked off on a list of notifs
        addNotif(0, PACKAGE_1);
        addNotif(1, PACKAGE_2);
        dispatchBuild();

        // THEN the value of `now` is the same for all calls to shouldFilterOut
        verify(filter1).shouldFilterOut(mEntrySet.get(0), 10047);
        verify(filter2).shouldFilterOut(mEntrySet.get(0), 10047);
        verify(filter3).shouldFilterOut(mEntrySet.get(0), 10047);
        verify(filter1).shouldFilterOut(mEntrySet.get(1), 10047);
        verify(filter2).shouldFilterOut(mEntrySet.get(1), 10047);
        verify(filter3).shouldFilterOut(mEntrySet.get(1), 10047);
    }

    @Test
    public void testGroupTransformEntries() {
        // GIVEN a registered OnBeforeTransformGroupsListener
        RecordingOnBeforeTransformGroupsListener listener =
                spy(new RecordingOnBeforeTransformGroupsListener());
        mListBuilder.addOnBeforeTransformGroupsListener(listener);

        // GIVEN some new notifs
        addNotif(0, PACKAGE_1);
        addGroupChild(1, PACKAGE_2, GROUP_1);
        addGroupSummary(2, PACKAGE_2, GROUP_1);
        addGroupChild(3, PACKAGE_2, GROUP_1);
        addNotif(4, PACKAGE_3);
        addGroupChild(5, PACKAGE_2, GROUP_1);

        // WHEN we run the pipeline
        dispatchBuild();

        verifyBuiltList(
                notif(0),
                group(
                        summary(2),
                        child(1),
                        child(3),
                        child(5)
                ),
                notif(4)
        );

        // THEN all the new notifs, including the new GroupEntry, are passed to the listener
        verify(listener).onBeforeTransformGroups(
                Arrays.asList(
                        mEntrySet.get(0),
                        mBuiltList.get(1),
                        mEntrySet.get(4)
                )
        );
    }

    @Test
    public void testGroupTransformEntriesOnSecondRun() {
        // GIVEN a registered OnBeforeTransformGroupsListener
        RecordingOnBeforeTransformGroupsListener listener =
                spy(new RecordingOnBeforeTransformGroupsListener());
        mListBuilder.addOnBeforeTransformGroupsListener(listener);

        // GIVEN some notifs that have already been added (two of which are in malformed groups)
        addNotif(0, PACKAGE_1);
        addGroupChild(1, PACKAGE_2, GROUP_1);
        addGroupChild(2, PACKAGE_3, GROUP_2);

        dispatchBuild();
        clearInvocations(listener);

        // WHEN we run the pipeline
        addGroupSummary(3, PACKAGE_2, GROUP_1);
        addGroupChild(4, PACKAGE_3, GROUP_2);
        addGroupSummary(5, PACKAGE_3, GROUP_2);
        addGroupChild(6, PACKAGE_3, GROUP_2);
        addNotif(7, PACKAGE_2);

        dispatchBuild();

        verifyBuiltList(
                notif(0),
                notif(1),
                group(
                        summary(5),
                        child(2),
                        child(4),
                        child(6)
                ),
                notif(7)
        );

        // THEN all the new notifs, including the new GroupEntry, are passed to the listener
        verify(listener).onBeforeTransformGroups(
                Arrays.asList(
                        mEntrySet.get(0),
                        mEntrySet.get(1),
                        mBuiltList.get(2),
                        mEntrySet.get(7)
                )
        );
    }

    @Test
    public void testAnnulledGroupsHaveParentSetProperly() {
        // GIVEN a list containing a small group that's already been built once
        addGroupChild(0, PACKAGE_2, GROUP_2);
        addGroupSummary(1, PACKAGE_2, GROUP_2);
        addGroupChild(2, PACKAGE_2, GROUP_2);
        dispatchBuild();

        verifyBuiltList(
                group(
                        summary(1),
                        child(0),
                        child(2)
                )
        );
        GroupEntry group = (GroupEntry) mBuiltList.get(0);

        // WHEN a child is removed such that the group is no longer big enough
        mEntrySet.remove(2);
        dispatchBuild();

        // THEN the group is annulled and its parent is set back to null
        verifyBuiltList(
                notif(0)
        );
        assertNull(group.getParent());

        // but its previous parent indicates that it was added in the previous iteration
        assertEquals(GroupEntry.ROOT_ENTRY, group.getPreviousParent());
    }

    @Test(expected = IllegalStateException.class)
    public void testOutOfOrderPreGroupFilterInvalidationThrows() {
        // GIVEN a PreGroupNotifFilter that gets invalidated during the grouping stage
        NotifFilter filter = new PackageFilter(PACKAGE_5);
        OnBeforeTransformGroupsListener listener = (list) -> filter.invalidateList();
        mListBuilder.addPreGroupFilter(filter);
        mListBuilder.addOnBeforeTransformGroupsListener(listener);

        // WHEN we try to run the pipeline and the filter is invalidated
        addNotif(0, PACKAGE_1);
        dispatchBuild();

        // THEN an exception is thrown
    }

    @Test(expected = IllegalStateException.class)
    public void testOutOfOrderPrompterInvalidationThrows() {
        // GIVEN a NotifPromoter that gets invalidated during the sorting stage
        NotifPromoter promoter = new IdPromoter(47);
        OnBeforeSortListener listener =
                (list) -> promoter.invalidateList();
        mListBuilder.addPromoter(promoter);
        mListBuilder.addOnBeforeSortListener(listener);

        // WHEN we try to run the pipeline and the promoter is invalidated
        addNotif(0, PACKAGE_1);
        dispatchBuild();

        // THEN an exception is thrown
    }

    @Test(expected = IllegalStateException.class)
    public void testOutOfOrderComparatorInvalidationThrows() {
        // GIVEN a NotifComparator that gets invalidated during the finalizing stage
        NotifComparator comparator = new HypeComparator(PACKAGE_5);
        OnBeforeRenderListListener listener =
                (list) -> comparator.invalidateList();
        mListBuilder.setComparators(Collections.singletonList(comparator));
        mListBuilder.addOnBeforeRenderListListener(listener);

        // WHEN we try to run the pipeline and the comparator is invalidated
        addNotif(0, PACKAGE_1);
        dispatchBuild();

        // THEN an exception is thrown
    }

    @Test(expected = IllegalStateException.class)
    public void testOutOfOrderPreRenderFilterInvalidationThrows() {
        // GIVEN a PreRenderNotifFilter that gets invalidated during the finalizing stage
        NotifFilter filter = new PackageFilter(PACKAGE_5);
        OnBeforeRenderListListener listener = (list) -> filter.invalidateList();
        mListBuilder.addPreRenderFilter(filter);
        mListBuilder.addOnBeforeRenderListListener(listener);

        // WHEN we try to run the pipeline and the PreRenderFilter is invalidated
        addNotif(0, PACKAGE_1);
        dispatchBuild();

        // THEN an exception is thrown
    }

    @Test
    public void testInOrderPreRenderFilter() {
        // GIVEN a PreRenderFilter that gets invalidated during the grouping stage
        NotifFilter filter = new PackageFilter(PACKAGE_5);
        OnBeforeTransformGroupsListener listener = (list) -> filter.invalidateList();
        mListBuilder.addPreRenderFilter(filter);
        mListBuilder.addOnBeforeTransformGroupsListener(listener);

        // WHEN we try to run the pipeline and the filter is invalidated
        addNotif(0, PACKAGE_1);
        dispatchBuild();

        // THEN no exception thrown
    }

    /**
     * Adds a notif to the collection that will be passed to the list builder when
     * {@link #dispatchBuild()}s is called.
     *
     * @param index Index of this notification in the set. This must be the current size of the set.
     *              it exists to improve readability of the resulting code, since later tests will
     *              have to refer to notifs by index.
     * @param packageId Package that the notif should be posted under
     * @return A NotificationEntryBuilder that can be used to further modify the notif. Do not call
     *         build() on the builder; that will be done on the next dispatchBuild().
     */
    private NotificationEntryBuilder addNotif(int index, String packageId) {
        final NotificationEntryBuilder builder = new NotificationEntryBuilder()
                .setPkg(packageId)
                .setId(nextId(packageId))
                .setRank(nextRank());

        builder.modifyNotification(mContext)
                .setContentTitle("Top level singleton")
                .setChannelId("test_channel");

        assertEquals(mEntrySet.size() + mPendingSet.size(), index);
        mPendingSet.add(builder);
        return builder;
    }

    /** Same behavior as {@link #addNotif(int, String)}. */
    private NotificationEntryBuilder addGroupSummary(int index, String packageId, String groupId) {
        final NotificationEntryBuilder builder = new NotificationEntryBuilder()
                .setPkg(packageId)
                .setId(nextId(packageId))
                .setRank(nextRank());

        builder.modifyNotification(mContext)
                .setChannelId("test_channel")
                .setContentTitle("Group summary")
                .setGroup(groupId)
                .setGroupSummary(true);

        assertEquals(mEntrySet.size() + mPendingSet.size(), index);
        mPendingSet.add(builder);
        return builder;
    }

    /** Same behavior as {@link #addNotif(int, String)}. */
    private NotificationEntryBuilder addGroupChild(int index, String packageId, String groupId) {
        final NotificationEntryBuilder builder = new NotificationEntryBuilder()
                .setPkg(packageId)
                .setId(nextId(packageId))
                .setRank(nextRank());

        builder.modifyNotification(mContext)
                .setChannelId("test_channel")
                .setContentTitle("Group child")
                .setGroup(groupId);

        assertEquals(mEntrySet.size() + mPendingSet.size(), index);
        mPendingSet.add(builder);
        return builder;
    }

    private int nextId(String packageName) {
        Integer nextId = mNextIdMap.get(packageName);
        if (nextId == null) {
            nextId = 0;
        }
        mNextIdMap.put(packageName, nextId + 1);
        return nextId;
    }

    private int nextRank() {
        int nextRank = mNextRank;
        mNextRank++;
        return nextRank;
    }

    private void dispatchBuild() {
        if (mPendingSet.size() > 0) {
            for (NotificationEntryBuilder builder : mPendingSet) {
                mEntrySet.add(builder.build());
            }
            mPendingSet.clear();
        }

        mReadyForBuildListener.onBeginDispatchToListeners();
        mReadyForBuildListener.onBuildList(mEntrySet);
    }

    private void verifyBuiltList(ExpectedEntry ...expectedEntries) {
        try {
            assertEquals(
                    "List is the wrong length",
                    expectedEntries.length,
                    mBuiltList.size());

            for (int i = 0; i < expectedEntries.length; i++) {
                ListEntry outEntry = mBuiltList.get(i);
                ExpectedEntry expectedEntry = expectedEntries[i];

                if (expectedEntry instanceof ExpectedNotif) {
                    assertEquals(
                            "Entry " + i + " isn't a NotifEntry",
                            NotificationEntry.class,
                            outEntry.getClass());
                    assertEquals(
                            "Entry " + i + " doesn't match expected value.",
                            ((ExpectedNotif) expectedEntry).entry, outEntry);
                } else {
                    ExpectedGroup cmpGroup = (ExpectedGroup) expectedEntry;

                    assertEquals(
                            "Entry " + i + " isn't a GroupEntry",
                            GroupEntry.class,
                            outEntry.getClass());

                    GroupEntry outGroup = (GroupEntry) outEntry;

                    assertEquals(
                            "Summary notif for entry " + i
                                    + " doesn't match expected value",
                            cmpGroup.summary,
                            outGroup.getSummary());
                    assertEquals(
                            "Summary notif for entry " + i
                                        + " doesn't have proper parent",
                            outGroup,
                            outGroup.getSummary().getParent());

                    assertEquals("Children for entry " + i,
                            cmpGroup.children,
                            outGroup.getChildren());

                    for (int j = 0; j < outGroup.getChildren().size(); j++) {
                        NotificationEntry child = outGroup.getChildren().get(j);
                        assertEquals(
                                "Child " + j + " for entry " + i
                                        + " doesn't have proper parent",
                                outGroup,
                                child.getParent());
                    }
                }
            }
        } catch (AssertionError err) {
            throw new AssertionError(
                    "List under test failed verification:\n" + dumpList(mBuiltList), err);
        }
    }

    private ExpectedNotif notif(int index) {
        return new ExpectedNotif(mEntrySet.get(index));
    }

    private ExpectedGroup group(ExpectedSummary summary, ExpectedChild...children) {
        return new ExpectedGroup(
                summary.entry,
                Arrays.stream(children)
                        .map(child -> child.entry)
                        .collect(Collectors.toList()));
    }

    private ExpectedSummary summary(int index) {
        return new ExpectedSummary(mEntrySet.get(index));
    }

    private ExpectedChild child(int index) {
        return new ExpectedChild(mEntrySet.get(index));
    }

    private abstract static class ExpectedEntry {
    }

    private static class ExpectedNotif extends ExpectedEntry {
        public final NotificationEntry entry;

        private ExpectedNotif(NotificationEntry entry) {
            this.entry = entry;
        }
    }

    private static class ExpectedGroup extends ExpectedEntry {
        public final NotificationEntry summary;
        public final List<NotificationEntry> children;

        private ExpectedGroup(
                NotificationEntry summary,
                List<NotificationEntry> children) {
            this.summary = summary;
            this.children = children;
        }
    }

    private static class ExpectedSummary {
        public final NotificationEntry entry;

        private ExpectedSummary(NotificationEntry entry) {
            this.entry = entry;
        }
    }

    private static class ExpectedChild {
        public final NotificationEntry entry;

        private ExpectedChild(NotificationEntry entry) {
            this.entry = entry;
        }
    }

    /** Filters out notifs from a particular package */
    private static class PackageFilter extends NotifFilter {
        private final String mPackageName;

        private boolean mEnabled = true;

        PackageFilter(String packageName) {
            super("PackageFilter");

            mPackageName = packageName;
        }

        @Override
        public boolean shouldFilterOut(NotificationEntry entry, long now) {
            return mEnabled && entry.getSbn().getPackageName().equals(mPackageName);
        }

        public void setEnabled(boolean enabled) {
            mEnabled = enabled;
        }
    }

    /** Promotes notifs with particular IDs */
    private static class IdPromoter extends NotifPromoter {
        private final List<Integer> mIds;

        IdPromoter(Integer... ids) {
            super("IdPromoter");
            mIds = Arrays.asList(ids);
        }

        @Override
        public boolean shouldPromoteToTopLevel(NotificationEntry child) {
            return mIds.contains(child.getSbn().getId());
        }
    }

    /** Sorts specific notifs above all others. */
    private static class HypeComparator extends NotifComparator {

        private final List<String> mPreferredPackages;

        HypeComparator(String ...preferredPackages) {
            super("HypeComparator");
            mPreferredPackages = Arrays.asList(preferredPackages);
        }

        @Override
        public int compare(ListEntry o1, ListEntry o2) {
            boolean contains1 = mPreferredPackages.contains(
                    o1.getRepresentativeEntry().getSbn().getPackageName());
            boolean contains2 = mPreferredPackages.contains(
                    o2.getRepresentativeEntry().getSbn().getPackageName());

            return Boolean.compare(contains2, contains1);
        }
    }

    /** Sorts notifs into sections based on their package name */
    private static class PackageSectioner extends SectionsProvider {

        PackageSectioner() {
            super("PackageSectioner");
        }

        @Override
        public int getSection(ListEntry entry) {
            switch (entry.getRepresentativeEntry().getSbn().getPackageName()) {
                case PACKAGE_1:
                    return 1;
                case PACKAGE_2:
                    return 2;
                case PACKAGE_3:
                    return 3;
                default:
                    return 4;
            }
        }
    }

    private static class RecordingOnBeforeTransformGroupsListener
            implements OnBeforeTransformGroupsListener {

        @Override
        public void onBeforeTransformGroups(List<ListEntry> list) { }
    }

    private static final String PACKAGE_1 = "com.test1";
    private static final String PACKAGE_2 = "com.test2";
    private static final String PACKAGE_3 = "org.test3";
    private static final String PACKAGE_4 = "com.test4";
    private static final String PACKAGE_5 = "com.test5";

    private static final String GROUP_1 = "group_1";
    private static final String GROUP_2 = "group_2";
}
