package com.warden.blocker.hosts

/**
 * A compact set of domains for very large blocklists (100k+ entries).
 *
 * Instead of keeping each domain as a String (~100 bytes with HashSet overhead), it stores a
 * 64-bit hash per domain in an open-addressing [LongArray] — about 8–16 bytes per domain.
 * Two different domains sharing a 64-bit hash is astronomically unlikely (~1e-14 per lookup
 * at 200k entries), which is fine for a blocker.
 *
 * Lookups hash a *range* of the input string, so checking every parent suffix of a host
 * (`a.b.example.com`, `b.example.com`, `example.com`, `com`) allocates nothing.
 */
class DomainHashSet(expectedSize: Int = 16) {

    private var table = LongArray(capacityFor(expectedSize))
    private var mask = table.size - 1

    var size = 0
        private set

    /** Bytes held by the backing table (for memory reporting). */
    val tableBytes: Long get() = table.size * 8L

    /** Adds [domain] (expected already lowercase). Returns false if it was already present. */
    fun add(domain: CharSequence): Boolean = addHash(hash(domain, 0, domain.length))

    fun contains(domain: CharSequence): Boolean = contains(domain, 0)

    /** True if `domain.substring(start)` is in the set, without creating the substring. */
    fun contains(domain: CharSequence, start: Int): Boolean {
        val h = hash(domain, start, domain.length)
        var i = mix(h).toInt() and mask
        while (true) {
            val v = table[i]
            if (v == EMPTY) return false
            if (v == h) return true
            i = (i + 1) and mask
        }
    }

    private fun addHash(h: Long): Boolean {
        if ((size + 1) * 10L > table.size * 7L) grow()
        var i = mix(h).toInt() and mask
        while (true) {
            val v = table[i]
            if (v == EMPTY) { table[i] = h; size++; return true }
            if (v == h) return false
            i = (i + 1) and mask
        }
    }

    private fun grow() {
        val old = table
        table = LongArray(old.size * 2)
        mask = table.size - 1
        for (v in old) {
            if (v == EMPTY) continue
            var i = mix(v).toInt() and mask
            while (table[i] != EMPTY) i = (i + 1) and mask
            table[i] = v
        }
    }

    companion object {
        private const val EMPTY = 0L

        /** Power of two keeping the load factor ≤ 0.7. */
        private fun capacityFor(n: Int): Int {
            val needed = (n.coerceAtLeast(8) * 10L / 7 + 1)
            var cap = 16
            while (cap < needed) cap = cap shl 1
            return cap
        }

        /** FNV-1a 64 over chars [start, end), never 0 (0 marks an empty slot). */
        fun hash(s: CharSequence, start: Int, end: Int): Long {
            var h = -0x340d631b7bdddcdbL // FNV offset basis
            for (i in start until end) {
                h = h xor s[i].code.toLong()
                h *= 0x100000001b3L // FNV prime
            }
            h = mix(h)
            return if (h == EMPTY) 1L else h
        }

        /** Murmur3 fmix64 — spreads bits so the low bits make a good table index. */
        private fun mix(x: Long): Long {
            var k = x
            k = k xor (k ushr 33)
            k *= -0xae502812aa7333L
            k = k xor (k ushr 33)
            k *= -0x3b314601e57a13adL
            k = k xor (k ushr 33)
            return k
        }
    }
}
