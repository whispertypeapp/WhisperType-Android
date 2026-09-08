package com.whispertype.android.platform.accessibility

/**
 * Fail-closed classifier that decides whether the currently focused editor is
 * safe to insert dictation text into (PRD FR-2 Eligibility, §16.4). Pure Kotlin
 * and fully unit-testable on a JVM host: it carries no Android runtime
 * dependency and publishes named constants for the relevant
 * `android.text.InputType` bit masks.
 *
 * [SAFE] is returned only for clearly ordinary editable text. Password / PIN /
 * payment / secure fields and any unknown flag combination fail closed to
 * [SECURE] or [UNCERTAIN] so WhisperType never writes into a protected field
 * and never writes when it cannot be confident about the field.
 */
enum class Classification {
    /** Ordinary editable text; safe to insert into. */
    SAFE,

    /** Password / PIN / secure / private field; never eligible. */
    SECURE,

    /** Could not confidently classify; never eligible (fails closed). */
    UNCERTAIN,
}

/**
 * Classifies an editor from its raw InputType mask plus independent
 * password/secure signals. Host-testable; see [SecurityClassifierTest].
 */
object SecurityClassifier {

    // ------------------------------------------------------------------
    // Named InputType bit masks (values match android.text.InputType).
    // ------------------------------------------------------------------
    const val TYPE_MASK_CLASS: Int = 0x0000_000f
    const val TYPE_MASK_VARIATION: Int = 0x0000_00f0

    const val TYPE_CLASS_TEXT: Int = 0x0000_0001
    const val TYPE_CLASS_NUMBER: Int = 0x0000_0002
    const val TYPE_CLASS_PHONE: Int = 0x0000_0003
    const val TYPE_CLASS_DATETIME: Int = 0x0000_0004

    const val TYPE_TEXT_VARIATION_NORMAL: Int = 0x0000_0000
    const val TYPE_NUMBER_VARIATION_NORMAL: Int = 0x0000_0000

    // Ordinary-text variations must remain eligible (Phase 3 fix, §2.3): email,
    // URI, person-name, postal-address, phonetic and short-message fields are
    // normal editable text, not secrets.
    const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS: Int = 0x0000_0020
    const val TYPE_TEXT_VARIATION_URI: Int = 0x0000_0030
    const val TYPE_TEXT_VARIATION_PERSON_NAME: Int = 0x0000_0060
    const val TYPE_TEXT_VARIATION_POSTAL_ADDRESS: Int = 0x0000_0070
    const val TYPE_TEXT_VARIATION_PHONETIC: Int = 0x0000_00c0
    const val TYPE_TEXT_VARIATION_SHORT_MESSAGE: Int = 0x0000_0040

    const val TYPE_TEXT_VARIATION_PASSWORD: Int = 0x0000_0080
    const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD: Int = 0x0000_0090
    const val TYPE_TEXT_VARIATION_WEB_PASSWORD: Int = 0x0000_00e0
    const val TYPE_NUMBER_VARIATION_PASSWORD: Int = 0x0000_0010

    /** Flag set that includes the secure (never-eligible) variations. */
    private val SECURE_VARIATIONS = setOf(
        TYPE_TEXT_VARIATION_PASSWORD,
        TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )

    /** Ordinary text variations that are eligible. */
    private val SAFE_TEXT_VARIATIONS = setOf(
        TYPE_TEXT_VARIATION_NORMAL,
        TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        TYPE_TEXT_VARIATION_URI,
        TYPE_TEXT_VARIATION_PERSON_NAME,
        TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
        TYPE_TEXT_VARIATION_PHONETIC,
        TYPE_TEXT_VARIATION_SHORT_MESSAGE,
    )

    /**
     * @param inputType the editor's raw InputType bit mask (0 when unknown —
     *                  many custom / web editors report 0; that alone is no
     *                  longer treated as uncertain, §2.3).
     * @param password true when the node reported a password / PIN / secure
     *                  flag (e.g. `node.isPassword` or a secure window flag).
     * @param contentInvalid true when the editor content / type could not be
     *                  read reliably; forces [UNCERTAIN].
     */
    fun classify(inputType: Int, password: Boolean, contentInvalid: Boolean): Classification {
        // Any reported password / secure flag is authoritative and fail-closed.
        if (password) return Classification.SECURE
        // If we could not confidently read the type, never guess.
        if (contentInvalid) return Classification.UNCERTAIN

        val cls = inputType and TYPE_MASK_CLASS
        val variation = inputType and TYPE_MASK_VARIATION

        if (variation in SECURE_VARIATIONS) return Classification.SECURE
        // PIN / payment / autofill number fields carry a password variation.
        if (cls == TYPE_CLASS_NUMBER && variation == TYPE_NUMBER_VARIATION_PASSWORD) {
            return Classification.SECURE
        }

        // Ordinary text classes and their safe variations are eligible.
        if (cls == TYPE_CLASS_TEXT && variation in SAFE_TEXT_VARIATIONS) {
            return Classification.SAFE
        }
        if (cls == TYPE_CLASS_NUMBER && variation != TYPE_NUMBER_VARIATION_PASSWORD) {
            return Classification.SAFE
        }
        // Phone / datetime classes are ordinary, non-secret input.
        if (cls == TYPE_CLASS_PHONE || cls == TYPE_CLASS_DATETIME) {
            return Classification.SAFE
        }
        // inputType == 0 (unknown) on a confirmed editable node: treat as ordinary
        // text (§2.3) rather than rejecting.
        if (inputType == 0) return Classification.SAFE

        // Unknown class / variation / flag combination -> cannot be confident.
        return Classification.UNCERTAIN
    }
}
