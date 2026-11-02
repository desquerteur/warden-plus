package com.warden.blocker.update

/**
 * Semantic version (MAJOR.MINOR.PATCH[-prerelease][+build]) as used by release tags
 * (`v1.2.3`) and `BuildConfig.VERSION_NAME` (`1.2.3`). Build metadata is ignored for
 * ordering, as the SemVer spec requires.
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        // A pre-release sorts before its release: 1.0.0-beta < 1.0.0.
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return compareValues(preRelease.isEmpty(), other.preRelease.isEmpty())
        }
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val c = compareIdentifier(preRelease[i], other.preRelease[i])
            if (c != 0) return c
        }
        return compareValues(preRelease.size, other.preRelease.size)
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (preRelease.isEmpty()) "" else "-" + preRelease.joinToString(".")

    companion object {
        private val PATTERN = Regex(
            """^[vV]?(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$"""
        )

        /** Parses `1.2.3`, `v1.2.3`, `1.2` (= 1.2.0) or `1.2.3-beta.1`; null if not a version. */
        fun parse(text: String): SemVer? {
            val m = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, pre) = m.destructured
            return SemVer(
                major = major.toIntOrNull() ?: return null,
                minor = minor.toIntOrNull() ?: return null,
                patch = if (patch.isEmpty()) 0 else patch.toIntOrNull() ?: return null,
                preRelease = if (pre.isEmpty()) emptyList() else pre.split('.').also { ids ->
                    if (ids.any { it.isEmpty() }) return null
                },
            )
        }

        // Numeric identifiers compare numerically and sort before alphanumeric ones.
        private fun compareIdentifier(a: String, b: String): Int {
            val na = a.toLongOrNull()
            val nb = b.toLongOrNull()
            return when {
                na != null && nb != null -> na.compareTo(nb)
                na != null -> -1
                nb != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
