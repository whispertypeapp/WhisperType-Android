package com.whispertype.android.platform.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure host tests for [FocusedEditor]'s fail-closed secure/uncertain flags.
 * [FocusedEditor] is framework-light (plain data) and its flags only consult
 * the pure [SecurityClassifier], so it is host-testable.
 */
class FocusedEditorTest {

    private fun editor(
        inputType: Int,
        password: Boolean = false,
        contentInvalid: Boolean = false,
    ) = FocusedEditor(
        packageName = "com.example.app",
        displayId = 0,
        windowId = 5,
        editorIdentity = null,
        inputType = inputType,
        isPassword = password,
        contentInvalid = contentInvalid,
        selectionStart = 0,
        selectionEnd = 0,
        generation = 3L,
    )

    @Test
    fun `plain editor is neither secure nor uncertain`() {
        val e = editor(
            inputType = SecurityClassifier.TYPE_CLASS_TEXT or SecurityClassifier.TYPE_TEXT_VARIATION_NORMAL,
        )
        assertFalse(e.isSecure)
        assertFalse(e.isUncertain)
    }

    @Test
    fun `password editor with missing resource id is secure and distinct generation`() {
        val e = editor(
            inputType = SecurityClassifier.TYPE_CLASS_TEXT or SecurityClassifier.TYPE_TEXT_VARIATION_NORMAL,
            password = true,
        )
        assertTrue(e.isSecure)
    }

    @Test
    fun `editor with unreadable type is uncertain`() {
        val e = editor(inputType = 0, password = false, contentInvalid = true)
        assertTrue(e.isUncertain)
    }
}
