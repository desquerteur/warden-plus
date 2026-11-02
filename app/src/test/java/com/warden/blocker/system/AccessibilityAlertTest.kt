package com.warden.blocker.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityAlertTest {

    @Test fun noAlarmWhileTheServiceIsOn() =
        assertFalse(AccessibilityAlert.shouldWarn(accessibilityOn = true, usedBefore = true, blockingOn = true, hasBlockedApps = true))

    @Test fun freshInstallThatWasNeverSetUpGetsNoAlarm() =
        assertFalse(AccessibilityAlert.shouldWarn(accessibilityOn = false, usedBefore = false, blockingOn = false, hasBlockedApps = false))

    @Test fun switchedOffAfterItWasInUse() =
        assertTrue(AccessibilityAlert.shouldWarn(accessibilityOn = false, usedBefore = true, blockingOn = false, hasBlockedApps = false))

    /** Updating from 0.2.1, which didn't remember "used before" yet. */
    @Test fun blockingOnOrBlockedAppsCountAsRelyingOnIt() {
        assertTrue(AccessibilityAlert.shouldWarn(accessibilityOn = false, usedBefore = false, blockingOn = true, hasBlockedApps = false))
        assertTrue(AccessibilityAlert.shouldWarn(accessibilityOn = false, usedBefore = false, blockingOn = false, hasBlockedApps = true))
    }
}
