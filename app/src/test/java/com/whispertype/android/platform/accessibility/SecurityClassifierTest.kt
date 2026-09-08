package com.whispertype.android.platform.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure host tests for [SecurityClassifier] (PRD FR-2 Eligibility, §16.4). */
class SecurityClassifierTest {

    // ------------------------------------------------------------------
    // SAFE
    // ------------------------------------------------------------------

    @Test
    fun `plain single line text is safe`() {
        val type = SecurityClassifier.TYPE_CLASS_TEXT or SecurityClassifier.TYPE_TEXT_VARIATION_NORMAL
        assertEquals(Classification.SAFE, SecurityClassifier.classify(type, password = false, contentInvalid = false))
    }

    @Test
    fun `ordinary multi-line plain text is safe`() {
        val type = SecurityClassifier.TYPE_CLASS_TEXT or SecurityClassifier.TYPE_TEXT_VARIATION_NORMAL
        assertEquals(Classification.SAFE, SecurityClassifier.classify(type, password = false, contentInvalid = false))
    }

    @Test
    fun `plain number field without password variation is safe`() {
        val type = SecurityClassifier.TYPE_CLASS_NUMBER or SecurityClassifier.TYPE_NUMBER_VARIATION_NORMAL
        assertEquals(Classification.SAFE, SecurityClassifier.classify(type, password = false, contentInvalid = false))
    }

    @Test
    fun `plain text with autocorrect flag is still safe`() {
        val type = SecurityClassifier.TYPE_CLASS_TEXT or
            SecurityClassifier.TYPE_TEXT_VARIATION_NORMAL or
            0x0000_8000 // TYPE_TEXT_FLAG_AUTO_CORRECT
        assertEquals(Classification.SAFE, SecurityClassifier.classify(type, password = false, contentInvalid = false))
    }

    // ------------------------------------------------------------------
    // SECURE
    // ------------------------------------------------------------------

    @Test
    fun `text password variation is secure`() {
        val type = SecurityClassifier.TYPE_CLASS_TEXT or SecurityClassifier.TYPE_TEXT_VARIATION_PASSWORD
        assertEquals(Classification.SECURE, SecurityClassifier.classify(type, password = false, contentInvalid = false))
    }

    @Test
    fun `web password variation is secure`() {
        val type = SecurityClassifier.TYPE_CLASS_TEXT or SecurityClassifier.TYPE_TEXT_VARIATION_WEB_PASSWORD
        assertEquals(Classification.SECURE, SecurityClassifier.classify(type, password = false, contentInvalid = false))
    }

    @Test
    fun `visible password variation is secure`() {
        val type = SecurityClassifier.TYPE_CLASS_TEXT or SecurityClassifier.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        assertEquals(Classification.SECURE, SecurityClassifier.classify(type, password = false, contentInvalid = false))
    }

    @Test
    fun `number password variation pin is secure`() {
        val type = SecurityClassifier.TYPE_CLASS_NUMBER or SecurityClassifier.TYPE_NUMBER_VARIATION_PASSWORD
        assertEquals(Classification.SECURE, SecurityClassifier.classify(type, password = false, contentInvalid = false))
    }

    @Test
    fun `reported password flag forces secure even for plain type`() {
        val type = SecurityClassifier.TYPE_CLASS_TEXT or SecurityClassifier.TYPE_TEXT_VARIATION_NORMAL
        assertEquals(Classification.SECURE, SecurityClassifier.classify(type, password = true, contentInvalid = false))
    }

    // ------------------------------------------------------------------
    // UNCERTAIN
    // ------------------------------------------------------------------

    @Test
    fun `content invalid forces uncertain even for text`() {
        val type = SecurityClassifier.TYPE_CLASS_TEXT or SecurityClassifier.TYPE_TEXT_VARIATION_NORMAL
        assertEquals(Classification.UNCERTAIN, SecurityClassifier.classify(type, password = false, contentInvalid = true))
    }

    @Test
    fun `zero input type with no other signal is safe for custom editors`() {
        // §2.3: many custom / web editors report inputType == 0; that alone must
        // not fail closed.
        assertEquals(Classification.SAFE, SecurityClassifier.classify(0, password = false, contentInvalid = false))
    }

    @Test
    fun `phone class is safe ordinary text`() {
        val type = 0x0000_0003 // TYPE_CLASS_PHONE
        assertEquals(Classification.SAFE, SecurityClassifier.classify(type, password = false, contentInvalid = false))
    }

    @Test
    fun `text with email variation is safe`() {
        val type = SecurityClassifier.TYPE_CLASS_TEXT or SecurityClassifier.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        assertEquals(Classification.SAFE, SecurityClassifier.classify(type, password = false, contentInvalid = false))
    }

    @Test
    fun `text with uri variation is safe`() {
        val type = SecurityClassifier.TYPE_CLASS_TEXT or SecurityClassifier.TYPE_TEXT_VARIATION_URI
        assertEquals(Classification.SAFE, SecurityClassifier.classify(type, password = false, contentInvalid = false))
    }

    @Test
    fun `unknown variation flag combination is uncertain`() {
        val type = SecurityClassifier.TYPE_CLASS_TEXT or 0x0000_00f0 // unknown variation mask
        assertEquals(Classification.UNCERTAIN, SecurityClassifier.classify(type, password = false, contentInvalid = false))
    }
}
