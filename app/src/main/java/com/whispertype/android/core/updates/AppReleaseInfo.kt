package com.whispertype.android.core.updates

/**
 * Metadata for a release fetched from GitHub.
 */
data class AppReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val releaseUrl: String,
    val downloadUrl: String,
    val isNewerThanCurrent: Boolean,
)
