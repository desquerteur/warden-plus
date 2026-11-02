package com.warden.blocker.stats

/** Block counters for one calendar day: per-app intercepts and blocked DNS lookups. */
data class DayBlocks(val apps: Map<String, Int> = emptyMap(), val domains: Int = 0) {
    val appTotal: Int get() = apps.values.sum()
}

/** Totals over a range of days, ready for the dashboard tiles. */
data class BlockTotals(val apps: Int, val domains: Int, val perApp: Map<String, Int>) {
    val total: Int get() = apps + domains
}

/**
 * Immutable daily block counters keyed by epoch day, with a compact text encoding so the
 * whole log fits in one DataStore string. Pure Kotlin so it can be unit-tested.
 *
 * Encoding: one line per day, `day|domains|pkg=n,pkg=n`. Package names never contain
 * `|`, `,`, `=` or newlines (other keys get them replaced by `_`), so no escaping is needed;
 * malformed lines are skipped.
 */
data class BlockLog(val days: Map<Long, DayBlocks> = emptyMap()) {

    fun withApp(day: Long, packageName: String, count: Int = 1): BlockLog {
        if (packageName.isBlank() || count <= 0) return this
        val key = packageName.replace(RESERVED, "_")
        val d = days[day] ?: DayBlocks()
        val apps = d.apps + (key to (d.apps[key] ?: 0) + count)
        return BlockLog(days + (day to d.copy(apps = apps)))
    }

    fun withDomains(day: Long, count: Int = 1): BlockLog {
        if (count <= 0) return this
        val d = days[day] ?: DayBlocks()
        return BlockLog(days + (day to d.copy(domains = d.domains + count)))
    }

    /** Drops days older than [keepDays] days counted back from [today] (today included). */
    fun pruned(today: Long, keepDays: Int = KEEP_DAYS): BlockLog {
        val oldest = today - keepDays + 1
        return if (days.keys.all { it >= oldest }) this else BlockLog(days.filterKeys { it >= oldest })
    }

    fun totals(dayRange: LongRange): BlockTotals {
        var apps = 0
        var domains = 0
        val perApp = HashMap<String, Int>()
        for ((day, d) in days) {
            if (day !in dayRange) continue
            apps += d.appTotal
            domains += d.domains
            d.apps.forEach { (pkg, n) -> perApp[pkg] = (perApp[pkg] ?: 0) + n }
        }
        return BlockTotals(apps, domains, perApp)
    }

    fun encode(): String = days.toSortedMap().entries.joinToString("\n") { (day, d) ->
        val apps = d.apps.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
        "$day|${d.domains}|$apps"
    }

    companion object {
        const val KEEP_DAYS = 90
        private val RESERVED = Regex("[|,=\\r\\n]")

        fun decode(text: String?): BlockLog {
            if (text.isNullOrBlank()) return BlockLog()
            val days = HashMap<Long, DayBlocks>()
            for (line in text.lineSequence()) {
                val parts = line.split('|')
                if (parts.size != 3) continue
                val day = parts[0].toLongOrNull() ?: continue
                val domains = parts[1].toIntOrNull()?.coerceAtLeast(0) ?: continue
                val apps = parts[2].split(',').mapNotNull { entry ->
                    val pkg = entry.substringBefore('=', "")
                    val n = entry.substringAfter('=', "").toIntOrNull()
                    if (pkg.isBlank() || n == null || n <= 0) null else pkg to n
                }.toMap()
                days[day] = DayBlocks(apps, domains)
            }
            return BlockLog(days)
        }
    }
}
