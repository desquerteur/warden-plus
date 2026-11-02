package com.warden.blocker.hosts

import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class HostsAddUrlTest {

    private val dir: File = Files.createTempDirectory("hosts-test").toFile()
    private val context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }

    @After fun cleanUp() { dir.deleteRecursively() }

    /** Two quick taps on the same template: the second waits for the first and must not
     *  add the same list a second time (nor download it again). */
    @Test fun doubleTapAddsTheListOnce() = runBlocking(Dispatchers.Default) {
        val downloads = AtomicInteger()
        val repo = HostsListRepository(context) {
            downloads.incrementAndGet()
            Thread.sleep(400) // slow download: the second tap arrives meanwhile
            "0.0.0.0 ads.example.com\n".byteInputStream() to -1L
        }
        val url = "https://example.com/hosts.txt"

        val first = async { repo.addUrl(url) }
        delay(100)
        val second = async { repo.addUrl(url) }

        val a = first.await().getOrThrow()
        val b = second.await().getOrThrow()
        assertEquals(1, repo.sources.value.size)
        assertEquals(a.id, b.id)
        assertEquals(1, downloads.get())
        assertTrue(File(File(dir, "hostlists"), "${a.id}.txt").exists())
    }
}
