package com.warden.blocker.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockLogTest {

    @Test fun countsAccumulatePerDayAndApp() {
        val log = BlockLog()
            .withApp(100, "com.a").withApp(100, "com.a").withApp(100, "com.b")
            .withDomains(100, 5).withDomains(101)
        assertEquals(mapOf("com.a" to 2, "com.b" to 1), log.days[100]!!.apps)
        assertEquals(5, log.days[100]!!.domains)
        assertEquals(1, log.days[101]!!.domains)
    }

    @Test fun totalsOnlyIncludeTheRequestedDays() {
        val log = BlockLog().withApp(98, "x").withApp(99, "x").withApp(100, "y").withDomains(100, 3)
        val t = log.totals(99L..100L)
        assertEquals(2, t.apps)
        assertEquals(3, t.domains)
        assertEquals(5, t.total)
        assertEquals(mapOf("x" to 1, "y" to 1), t.perApp)
    }

    @Test fun keepsNinetyDays() {
        var log = BlockLog()
        for (day in 1L..120L) log = log.withDomains(day)
        val pruned = log.pruned(today = 120)
        assertEquals(90, pruned.days.size)
        assertEquals(31L, pruned.days.keys.min())
    }

    @Test fun encodeDecodeRoundTrip() {
        val log = BlockLog().withApp(19000, "com.example.app").withApp(19000, "feature:Reels")
            .withDomains(19000, 42).withDomains(19001, 1)
        assertEquals(log, BlockLog.decode(log.encode()))
    }

    @Test fun reservedCharactersAreSanitised() {
        val log = BlockLog().withApp(1, "feature:a,b=c|d")
        assertEquals(setOf("feature:a_b_c_d"), log.days[1]!!.apps.keys)
        assertEquals(log, BlockLog.decode(log.encode()))
    }

    @Test fun decodeToleratesGarbage() {
        assertTrue(BlockLog.decode(null).days.isEmpty())
        assertTrue(BlockLog.decode("").days.isEmpty())
        val log = BlockLog.decode("nonsense\n5|2|a=1,b=x,=3\n6|z|a=1")
        assertEquals(setOf(5L), log.days.keys)
        assertEquals(mapOf("a" to 1), log.days[5]!!.apps)
    }
}
