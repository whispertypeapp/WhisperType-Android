package com.whispertype.android.core.model

/**
 * Supported dictation language modes. Hinglish is Hindi romanized in Latin
 * script mixed naturally with English. Only Latin-script modes are supported
 * in v1.
 *
 * 0.10.0: the mode no longer carries a Live `systemInstruction` — the
 * transcribe model is raw transcription transport only, and every text
 * decision (shaping, code-mixing, script) is made server-side in the model's
 * `smart` transcription mode. The mode now stamps emitted candidates and picks
 * the BCP-47 language hint sent in setup (`en-US` / `hi-IN`).
 */
enum class LanguageMode {
    ENGLISH,
    HINGLISH,
}