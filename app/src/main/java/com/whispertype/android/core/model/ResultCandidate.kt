package com.whispertype.android.core.model

/**
 * A transcript candidate for one turn. `raw` is taken verbatim from the
 * server; `cleaned` is the server-provided cleaned transcript when present.
 */
data class ResultCandidate(
    val raw: String,
    val cleaned: String?,
    val language: LanguageMode,
)