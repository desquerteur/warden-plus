package com.warden.blocker.hosts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DomainMatcherTest {

    private fun setOf(vararg domains: String) = DomainHashSet().apply { domains.forEach { add(it) } }

    @Test
    fun `hash set add and contains`() {
        val set = setOf("ads.example.com", "tracker.net")
        assertEquals(2, set.size)
        assertFalse(set.add("tracker.net"))
        assertTrue(set.contains("ads.example.com"))
        assertFalse(set.contains("example.com"))
        assertTrue(set.contains("www.tracker.net", 4))
    }

    @Test
    fun `hash set grows and keeps every entry`() {
        val set = DomainHashSet()
        repeat(100_000) { set.add("host$it.example.com") }
        assertEquals(100_000, set.size)
        repeat(100_000) { assertTrue(set.contains("host$it.example.com")) }
        assertFalse(set.contains("host100000.example.com"))
    }

    @Test
    fun `list domains block themselves and subdomains only`() {
        val m = DomainMatcher(emptyList(), setOf("ads.example.com"))
        assertTrue(m.isBlocked("ads.example.com"))
        assertTrue(m.isBlocked("x.y.ads.example.com"))
        assertTrue(m.isBlocked("ADS.example.com."))
        assertFalse(m.isBlocked("example.com"))
        assertFalse(m.isBlocked("badads.example.com"))
        assertFalse(m.isBlocked("ads.example.com.evil.org"))
    }

    @Test
    fun `manual domains still work alongside lists`() {
        val m = DomainMatcher(listOf("instagram.com"), setOf("ads.example.com"))
        assertTrue(m.isBlocked("instagram.com"))
        assertTrue(m.isBlocked("www.instagram.com"))
        assertTrue(m.isBlocked("ads.example.com"))
        assertFalse(m.isBlocked("notinstagram.com"))
    }

    @Test
    fun `empty matcher blocks nothing`() {
        assertFalse(DomainMatcher.EMPTY.isBlocked("example.com"))
        assertTrue(DomainMatcher(emptyList(), DomainHashSet()).isEmpty)
    }

    /**
     * Manual benchmark: `HOSTS_BENCH_FILE=path/to/hosts gradlew testDebugUnitTest`.
     * Skipped (passes trivially) when the variable isn't set.
     */
    @Test
    fun `benchmark real hosts file`() {
        val path = System.getenv("HOSTS_BENCH_FILE") ?: return
        repeat(3) { benchmarkOnce(path) } // later rounds show warmed-up (JIT) numbers
    }

    private fun benchmarkOnce(path: String) {
        val t0 = System.nanoTime()
        val set = DomainHashSet()
        File(path).useLines { lines -> lines.forEach { line -> HostsParser.parseLine(line) { set.add(it) } } }
        val t1 = System.nanoTime()
        val m = DomainMatcher(emptyList(), set)
        var hits = 0
        repeat(100_000) { if (m.isBlocked("cdn$it.static.some-site.example.org")) hits++ }
        val t2 = System.nanoTime()
        println(
            "HOSTS_BENCH domains=${set.size} tableBytes=${set.tableBytes} " +
                "parseMs=${(t1 - t0) / 1_000_000} lookups100kMs=${(t2 - t1) / 1_000_000} hits=$hits",
        )
    }
}
