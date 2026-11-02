package com.warden.blocker.update

import android.app.ActivityManager.RunningAppProcessInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallResultReceiverTest {

    @Test fun launchesDirectlyOnlyWhileOnScreen() {
        assertTrue(InstallResultReceiver.canStartActivityNow(RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        // The VPN keeps a foreground service running, but no screen of Warden is visible.
        assertFalse(InstallResultReceiver.canStartActivityNow(RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE))
        assertFalse(InstallResultReceiver.canStartActivityNow(RunningAppProcessInfo.IMPORTANCE_CACHED))
    }
}
