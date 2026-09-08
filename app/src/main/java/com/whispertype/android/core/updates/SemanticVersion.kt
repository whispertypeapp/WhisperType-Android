package com.whispertype.android.core.updates

/**
 * Pure Kotlin semantic version parser and comparator.
 *
 * Handles standard SemVer strings such as "1.2.2", tags with leading 'v'
 * such as "v1.2.3", and suffixes like "-beta".
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val raw: String,
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    companion object {
        fun parse(versionString: String): SemanticVersion? {
            val clean = versionString.trim().removePrefix("v").removePrefix("V")
            val base = clean.substringBefore("-").substringBefore("+")
            val parts = base.split(".").mapNotNull { it.toIntOrNull() }
            if (parts.isEmpty()) return null
            val major = parts.getOrElse(0) { 0 }
            val minor = parts.getOrElse(1) { 0 }
            val patch = parts.getOrElse(2) { 0 }
            return SemanticVersion(major, minor, patch, versionString)
        }

        fun isNewer(remoteVersion: String, currentVersion: String): Boolean {
            val remote = parse(remoteVersion) ?: return false
            val current = parse(currentVersion) ?: return false
            return remote > current
        }
    }
}
