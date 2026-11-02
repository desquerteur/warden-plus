package com.warden.blocker.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIdsTest {

    @Test fun fixedIdsAreUnique() {
        assertEquals(NotificationIds.fixed.size, NotificationIds.fixed.toSet().size)
    }

    @Test fun newAppIdsNeverHitAFixedOne() {
        val range = NotificationIds.NEW_APP_BASE until NotificationIds.NEW_APP_BASE + NotificationIds.NEW_APP_RANGE
        NotificationIds.fixed.forEach { assertTrue("$it", it !in range) }
        listOf("", "Chess", "Subway Surfers", "a".repeat(500)).forEach { assertTrue(NotificationIds.newApp(it) in range) }
    }
}
