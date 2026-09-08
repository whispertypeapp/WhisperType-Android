package com.whispertype.android.core.model

/**
 * Transcription shaping mode supported by `gemini-3.5-transcribe-live`.
 *
 * [VERBATIM] is the literal transcription mode; captures speech word-for-word exactly
 * as spoken, including filler words (um, uh) and repetitions.
 * [SMART] enables server-side text shaping; removes disfluencies/fillers, resolves
 * inline self-corrections, and applies punctuation/formatting.
 */
enum class TranscriptionMode(val wireValue: String) {
    VERBATIM("verbatim"),
    SMART("smart");

    companion object {
        val DEFAULT = SMART

        fun fromString(value: String?): TranscriptionMode =
            entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) || it.wireValue.equals(value, ignoreCase = true)
            } ?: DEFAULT
    }
}
