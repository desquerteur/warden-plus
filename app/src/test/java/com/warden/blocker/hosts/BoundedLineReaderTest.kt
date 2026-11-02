package com.warden.blocker.hosts

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.Reader
import java.io.StringReader

class BoundedLineReaderTest {

    private fun lines(text: String, max: Int = 4096): Pair<List<String>, Int> {
        val r = BoundedLineReader(StringReader(text), max)
        val out = generateSequence { r.readLine() }.toList()
        return out to r.skippedLines
    }

    @Test fun splitsLikeBufferedReader() {
        val text = "a\nb\r\nc\rd\n\ne"
        assertEquals(text.reader().buffered().readLines(), lines(text).first)
        assertEquals(listOf("a", "b", "c", "d", "", "e"), lines(text).first)
    }

    @Test fun trailingNewlineGivesNoEmptyLastLine() {
        assertEquals(listOf("a", "b"), lines("a\nb\n").first)
        assertEquals(emptyList<String>(), lines("").first)
    }

    @Test fun skipsOverlongLinesAndKeepsTheRest() {
        val (out, skipped) = lines("0.0.0.0 ok.com\n" + "x".repeat(50) + "\nb.com", max = 20)
        assertEquals(listOf("0.0.0.0 ok.com", "b.com"), out)
        assertEquals(1, skipped)
    }

    @Test fun lineOfExactlyMaxLengthIsKept() {
        assertEquals(listOf("x".repeat(20)), lines("x".repeat(20), max = 20).first)
        assertEquals(emptyList<String>(), lines("x".repeat(21), max = 20).first)
    }

    /** 30 MB without a line break is streamed past in small chunks, never built as a String. */
    @Test fun hugeFileWithoutLineBreaksDoesNotBufferIt() {
        val total = 30L * 1024 * 1024
        val endless = object : Reader() {
            var left = total
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                if (left == 0L) return -1
                val n = minOf(len.toLong(), left).toInt()
                cbuf.fill('{', off, off + n)
                left -= n
                return n
            }
            override fun close() {}
        }
        val r = BoundedLineReader(endless)
        assertEquals(null, r.readLine())
        assertEquals(1, r.skippedLines)
    }
}
