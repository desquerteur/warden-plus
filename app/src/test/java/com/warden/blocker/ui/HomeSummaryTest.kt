package com.warden.blocker.ui

import com.warden.blocker.hosts.HostsSource
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSummaryTest {

    private fun list(domains: Int, enabled: Boolean = true) =
        HostsSource(id = "id$domains", name = "List", url = null, enabled = enabled, domainCount = domains)

    private fun n(value: Long) = "%,d".format(value)

    @Test fun itemsOnly() {
        assertEquals("3 items protected", protectedSummary(3, emptyList()))
        assertEquals("1 item protected", protectedSummary(1, emptyList()))
        assertEquals("0 items protected", protectedSummary(0, emptyList()))
    }

    @Test fun enabledListsAreCounted() {
        assertEquals("3 items · ${n(77_758)} domains", protectedSummary(3, listOf(list(77_758))))
        assertEquals("1 item · ${n(1_100)} domains", protectedSummary(1, listOf(list(1_000), list(100))))
    }

    @Test fun disabledOrEmptyListsAreIgnored() {
        assertEquals("2 items protected", protectedSummary(2, listOf(list(5_000, enabled = false), list(0))))
    }

    @Test fun listsWithoutItems() {
        assertEquals("${n(77_758)} domains from lists", protectedSummary(0, listOf(list(77_758))))
    }
}
