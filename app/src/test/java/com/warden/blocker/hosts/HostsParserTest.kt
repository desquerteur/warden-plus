package com.warden.blocker.hosts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostsParserTest {

    private fun parse(text: String) = HostsParser.parse(text.lineSequence())

    @Test
    fun `parses hosts format with sink addresses and comments`() {
        val result = parse(
            """
            # Title: StevenBlack/hosts
            127.0.0.1 localhost
            127.0.0.1 localhost.localdomain
            255.255.255.255 broadcasthost
            ::1 localhost
            0.0.0.0 0.0.0.0
            0.0.0.0 ads.example.com
            127.0.0.1 tracker.example.net   # trailing comment
            0.0.0.0	tab.example.org
            """.trimIndent(),
        )
        assertEquals(setOf("ads.example.com", "tracker.example.net", "tab.example.org"), result)
    }

    @Test
    fun `multiple domains on one line`() {
        assertEquals(setOf("a.com", "b.com", "c.net"), parse("0.0.0.0 a.com b.com c.net"))
    }

    @Test
    fun `plain domain list`() {
        assertEquals(setOf("one.com", "two.org"), parse("one.com\n\n  two.org  \n# skip me"))
    }

    @Test
    fun `adblock rules`() {
        val result = parse(
            """
            ! Adblock comment
            [Adblock Plus 2.0]
            ||ads.example.com^
            ||important.example.com^${'$'}important
            ||third.example.com^${'$'}third-party
            ||path.example.com/banner
            @@||allowed.example.com^
            ||*.wild.com^
            """.trimIndent(),
        )
        assertEquals(setOf("ads.example.com", "important.example.com"), result)
    }

    @Test
    fun `lowercases and deduplicates`() {
        assertEquals(setOf("ads.example.com"), parse("0.0.0.0 ADS.Example.com\nads.example.com\nads.example.com."))
    }

    @Test
    fun `ignores ips and invalid names`() {
        val result = parse(
            """
            192.168.1.1
            0.0.0.0 10.0.0.1
            0.0.0.0 -bad.com
            0.0.0.0 bad-.com
            0.0.0.0 under_score.example.com
            0.0.0.0 spaces?.com
            0.0.0.0 singlelabel
            0.0.0.0 local
            0.0.0.0 1.2.3
            0.0.0.0 ümlaut.de
            some random words
            """.trimIndent(),
        )
        assertEquals(setOf("under_score.example.com"), result)
    }

    @Test
    fun `strips byte order mark`() {
        assertEquals(setOf("bom.com"), parse("﻿0.0.0.0 bom.com"))
    }

    @Test
    fun `validation edge cases`() {
        assertTrue(HostsParser.isValidDomain("a.b"))
        assertFalse(HostsParser.isValidDomain("a..b"))
        assertFalse(HostsParser.isValidDomain("${"x".repeat(64)}.com"))
        assertTrue(HostsParser.isValidDomain("${"x".repeat(63)}.com"))
        assertNull(HostsParser.normalize("localhost"))
        assertTrue(HostsParser.isIpAddress("fe80::1%lo0"))
    }
}
