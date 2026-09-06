package com.whispertype.android.core.model

/**
 * A physical-keyboard dictation shortcut: a key code plus optional modifier
 * mask (Ctrl / Alt / Shift / Meta). Pure and framework-free — the modifier bits
 * mirror [android.view.KeyEvent]'s meta-state bits so the accessibility service
 * can match events without mapping, but no Android type appears here.
 *
 * `keyCode == 0` is the sentinel for "hotkey disabled".
 */
data class HotkeyShortcut(
    val keyCode: Int,
    val modifiers: Int = 0,
) {
    val isEnabled: Boolean get() = keyCode != 0

    /**
     * True when a key event with [eventKeyCode] and [eventMetaState] should
     * trigger this shortcut. The modifiers are compared after masking to the
     * bits this app cares about (the four common modifier keys), so an
     * irrelevant meta bit (e.g. caps-lock) never blocks a match.
     */
    fun matches(eventKeyCode: Int, eventMetaState: Int): Boolean =
        isEnabled && eventKeyCode == keyCode &&
            (eventMetaState and MODIFIER_MASK) == (modifiers and MODIFIER_MASK)

    companion object {
        /** Key-event meta-state bits this app treats as modifiers. */
        const val META_CTRL_ON = 0x00001000
        const val META_ALT_ON = 0x00000002
        const val META_SHIFT_ON = 0x00000001
        const val META_META_ON = 0x00010000
        const val MODIFIER_MASK = META_CTRL_ON or META_ALT_ON or META_SHIFT_ON or META_META_ON

        val Disabled = HotkeyShortcut(keyCode = 0, modifiers = 0)
    }
}
