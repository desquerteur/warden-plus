package com.warden.blocker.hosts

import java.io.Reader

/**
 * Reads lines like [java.io.BufferedReader.readLine] (`\n`, `\r` or `\r\n` end a line), but
 * never holds more than [maxLength] chars: longer lines are read past and skipped. A 30 MB
 * file without a single line break (minified JSON, a binary) would otherwise end up as one
 * giant String and could crash the whole app with an OutOfMemoryError.
 */
class BoundedLineReader(
    private val reader: Reader,
    private val maxLength: Int = MAX_LINE_LENGTH,
) : AutoCloseable {

    private val buf = CharArray(64 * 1024)
    private var pos = 0
    private var len = 0
    private var skipLf = false // last line ended with '\r'; drop a directly following '\n'
    private val line = StringBuilder()

    /** Number of lines skipped because they were longer than [maxLength]. */
    var skippedLines = 0
        private set

    /** The next line without its terminator, or null at the end of the input. */
    fun readLine(): String? {
        while (true) {
            line.setLength(0)
            var tooLong = false
            var sawAny = false
            while (true) {
                if (pos == len) {
                    len = reader.read(buf, 0, buf.size)
                    pos = 0
                    if (len <= 0) {
                        len = 0
                        if (!sawAny) return null
                        break // last line without a terminator
                    }
                }
                val c = buf[pos++]
                if (skipLf) {
                    skipLf = false
                    if (c == '\n') continue
                }
                if (c == '\n') break
                if (c == '\r') { skipLf = true; break }
                sawAny = true
                if (!tooLong) {
                    if (line.length < maxLength) line.append(c) else { tooLong = true; line.setLength(0) }
                }
            }
            if (!tooLong) return line.toString()
            skippedLines++
        }
    }

    override fun close() = reader.close()

    companion object {
        /** Far longer than any real hosts line (a domain is at most 253 chars). */
        const val MAX_LINE_LENGTH = 4096
    }
}
