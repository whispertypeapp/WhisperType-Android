package com.whispertype.android.core.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 0.5.8: which insertion failures fall back to the clipboard. */
class InsertionResultTest {

    private fun failure(code: String): DictationFailure =
        DictationFailure(code = code, message = code, recoverable = true)

    @Test
    fun `field-failure codes fall back to the clipboard`() {
        assertTrue(failure("insert_target_ineligible").isClipboardFallback())
        assertTrue(failure("insert_connection_unavailable").isClipboardFallback())
        assertTrue(failure("insert_target_stale").isClipboardFallback())
    }

    @Test
    fun `a protected-field failure never falls back to the clipboard`() {
        assertFalse(failure("insert_target_not_safe").isClipboardFallback())
    }

    @Test
    fun `unrelated failures never fall back to the clipboard`() {
        assertFalse(failure("insert_ambiguous").isClipboardFallback())
        assertFalse(failure("runtime_no_api_key").isClipboardFallback())
        assertFalse(failure("gemini_no_transcript").isClipboardFallback())
    }
}
