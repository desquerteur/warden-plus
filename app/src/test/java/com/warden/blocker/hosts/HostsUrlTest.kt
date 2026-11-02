package com.warden.blocker.hosts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostsUrlTest {

    @Test
    fun `accepts https and adds missing scheme`() {
        assertEquals("https://example.com/hosts", HostsListRepository.normalizeUrl(" https://example.com/hosts "))
        assertEquals("https://example.com/list.txt", HostsListRepository.normalizeUrl("example.com/list.txt"))
    }

    @Test
    fun `rejects non-https and garbage`() {
        assertNull(HostsListRepository.normalizeUrl("http://example.com/hosts"))
        assertNull(HostsListRepository.normalizeUrl("ftp://example.com/hosts"))
        assertNull(HostsListRepository.normalizeUrl("file:///sdcard/hosts"))
        assertNull(HostsListRepository.normalizeUrl("https://"))
        assertNull(HostsListRepository.normalizeUrl("not a url"))
        assertNull(HostsListRepository.normalizeUrl(""))
    }

    @Test
    fun `templates point at StevenBlack over https`() {
        HostsTemplates.ALL.forEach { assertEquals(it.url, HostsListRepository.normalizeUrl(it.url)) }
    }

    @Test
    fun `default name is host plus path`() {
        assertEquals("example.com/lists/ads.txt", HostsListRepository.defaultName("https://example.com/lists/ads.txt"))
    }
}
