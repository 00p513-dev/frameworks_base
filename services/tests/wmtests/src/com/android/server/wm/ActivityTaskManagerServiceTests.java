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

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.PictureInPictureParams;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.EnterPipRequestedItem;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.IDisplayWindowListener;
import android.view.WindowContainerTransaction;

import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockitoSession;

import java.util.ArrayList;

/**
 * Tests for the {@link ActivityTaskManagerService} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityTaskManagerServiceTests
 */
@MediumTest
@RunWith(WindowTestRunner.class)
public class ActivityTaskManagerServiceTests extends ActivityTestsBase {

    private final ArgumentCaptor<ClientTransaction> mClientTransactionCaptor =
            ArgumentCaptor.forClass(ClientTransaction.class);

    @Before
    public void setUp() throws Exception {
        doReturn(false).when(mService).isBooting();
        doReturn(true).when(mService).isBooted();
    }

    /** Verify that activity is finished correctly upon request. */
    @Test
    public void testActivityFinish() {
        final ActivityStack stack = new StackBuilder(mRootActivityContainer).build();
        final ActivityRecord activity = stack.getBottomMostTask().getTopNonFinishingActivity();
        assertTrue("Activity must be finished", mService.finishActivity(activity.appToken,
                0 /* resultCode */, null /* resultData */,
                Activity.DONT_FINISH_TASK_WITH_ACTIVITY));
        assertTrue(activity.finishing);

        assertTrue("Duplicate activity finish request must also return 'true'",
                mService.finishActivity(activity.appToken, 0 /* resultCode */,
                        null /* resultData */, Activity.DONT_FINISH_TASK_WITH_ACTIVITY));
    }

    @Test
    public void testOnPictureInPictureRequested() throws RemoteException {
        final ActivityStack stack = new StackBuilder(mRootActivityContainer).build();
        final ActivityRecord activity = stack.getBottomMostTask().getTopNonFinishingActivity();
        ClientLifecycleManager lifecycleManager = mService.getLifecycleManager();
        doNothing().when(lifecycleManager).scheduleTransaction(any());
        doReturn(true).when(activity).checkEnterPictureInPictureState(anyString(), anyBoolean());

        mService.requestPictureInPictureMode(activity.token);

        verify(lifecycleManager).scheduleTransaction(mClientTransactionCaptor.capture());
        final ClientTransaction transaction = mClientTransactionCaptor.getValue();
        // Check that only an enter pip request item callback was scheduled.
        assertEquals(1, transaction.getCallbacks().size());
        assertTrue(transaction.getCallbacks().get(0) instanceof EnterPipRequestedItem);
        // Check the activity lifecycle state remains unchanged.
        assertNull(transaction.getLifecycleStateRequest());
    }

    @Test(expected = IllegalStateException.class)
    public void testOnPictureInPictureRequested_cannotEnterPip() throws RemoteException {
        final ActivityStack stack = new StackBuilder(mRootActivityContainer).build();
        final ActivityRecord activity = stack.getBottomMostTask().getTopNonFinishingActivity();
        ClientLifecycleManager lifecycleManager = mService.getLifecycleManager();
        doNothing().when(lifecycleManager).scheduleTransaction(any());
        doReturn(false).when(activity).checkEnterPictureInPictureState(anyString(), anyBoolean());

        mService.requestPictureInPictureMode(activity.token);

        // Check enter no transactions with enter pip requests are made.
        verify(lifecycleManager, times(0)).scheduleTransaction(any());
    }

    @Test
    public void testTaskTransaction() {
        removeGlobalMinSizeRestriction();
        final ActivityStack stack = new StackBuilder(mRootActivityContainer)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        final Task task = stack.getTopMostTask();
        WindowContainerTransaction t = new WindowContainerTransaction();
        Rect newBounds = new Rect(10, 10, 100, 100);
        t.setBounds(task.mRemoteToken, new Rect(10, 10, 100, 100));
        mService.applyContainerTransaction(t);
        assertEquals(newBounds, task.getBounds());
    }

    @Test
    public void testStackTransaction() {
        removeGlobalMinSizeRestriction();
        final ActivityStack stack = new StackBuilder(mRootActivityContainer)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        ActivityManager.StackInfo info =
                mService.getStackInfo(WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD);
        WindowContainerTransaction t = new WindowContainerTransaction();
        assertEquals(stack.mRemoteToken, info.stackToken);
        Rect newBounds = new Rect(10, 10, 100, 100);
        t.setBounds(info.stackToken, new Rect(10, 10, 100, 100));
        mService.applyContainerTransaction(t);
        assertEquals(newBounds, stack.getBounds());
    }

    @Test
    public void testDisplayWindowListener() {
        final ArrayList<Integer> added = new ArrayList<>();
        final ArrayList<Integer> changed = new ArrayList<>();
        final ArrayList<Integer> removed = new ArrayList<>();
        IDisplayWindowListener listener = new IDisplayWindowListener.Stub() {
            @Override
            public void onDisplayAdded(int displayId) {
                added.add(displayId);
            }

            @Override
            public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
                changed.add(displayId);
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                removed.add(displayId);
            }
        };
        mService.mWindowManager.registerDisplayWindowListener(listener);
        // Check that existing displays call added
        assertEquals(1, added.size());
        assertEquals(0, changed.size());
        assertEquals(0, removed.size());
        added.clear();
        // Check adding a display
        DisplayContent newDisp1 = new TestDisplayContent.Builder(mService, 600, 800).build();
        assertEquals(1, added.size());
        assertEquals(0, changed.size());
        assertEquals(0, removed.size());
        added.clear();
        // Check that changes are reported
        Configuration c = new Configuration(newDisp1.getRequestedOverrideConfiguration());
        c.windowConfiguration.setBounds(new Rect(0, 0, 1000, 1300));
        newDisp1.onRequestedOverrideConfigurationChanged(c);
        mService.mRootActivityContainer.ensureVisibilityAndConfig(null /* starting */,
                newDisp1.mDisplayId, false /* markFrozenIfConfigChanged */,
                false /* deferResume */);
        assertEquals(0, added.size());
        assertEquals(1, changed.size());
        assertEquals(0, removed.size());
        changed.clear();
        // Check that removal is reported
        newDisp1.remove();
        assertEquals(0, added.size());
        assertEquals(0, changed.size());
        assertEquals(1, removed.size());
    }

    /*
        a test to verify b/144045134 - ignore PIP mode request for destroyed activity.
        mocks r.getParent() to return null to cause NPE inside enterPipRunnable#run() in
        ActivityTaskMangerservice#enterPictureInPictureMode(), which rebooted the device.
        It doesn't fully simulate the issue's reproduce steps, but this should suffice.
     */
    @Test
    public void testEnterPipModeWhenRecordParentChangesToNull() {
        MockitoSession mockSession = mockitoSession()
                .initMocks(this)
                .mockStatic(ActivityRecord.class)
                .startMocking();

        ActivityRecord record = mock(ActivityRecord.class);
        IBinder token = mock(IBinder.class);
        PictureInPictureParams params = mock(PictureInPictureParams.class);
        record.pictureInPictureArgs = params;

        //mock operations in private method ensureValidPictureInPictureActivityParamsLocked()
        when(ActivityRecord.forTokenLocked(token)).thenReturn(record);
        doReturn(true).when(record).supportsPictureInPicture();
        doReturn(false).when(params).hasSetAspectRatio();

        //mock other operations
        doReturn(true).when(record)
                .checkEnterPictureInPictureState("enterPictureInPictureMode", false);
        doReturn(false).when(mService).isInPictureInPictureMode(any());
        doReturn(false).when(mService).isKeyguardLocked();

        //to simulate NPE
        doReturn(null).when(record).getParent();

        mService.enterPictureInPictureMode(token, params);
        //if record's null parent is not handled gracefully, test will fail with NPE

        mockSession.finishMocking();
    }
}

