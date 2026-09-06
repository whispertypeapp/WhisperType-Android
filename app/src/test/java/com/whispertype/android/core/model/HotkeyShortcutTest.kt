package com.whispertype.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [HotkeyShortcut] matching: key + optional modifier combo,
 * mask-relative so stray meta bits never block a match.
 */
class HotkeyShortcutTest {

    @Test
    fun `plain key matches with no modifiers`() {
        val shortcut = HotkeyShortcut(keyCode = 96) // KEYCODE_GRAVE
        assertTrue(shortcut.matches(eventKeyCode = 96, eventMetaState = 0))
    }

    @Test
    fun `plain key does not match a different key`() {
        val shortcut = HotkeyShortcut(keyCode = 96)
        assertFalse(shortcut.matches(eventKeyCode = 131, eventMetaState = 0))
    }

    @Test
    fun `plain key does not match when a modifier is held`() {
        val shortcut = HotkeyShortcut(keyCode = 96)
        assertFalse(shortcut.matches(eventKeyCode = 96, eventMetaState = HotkeyShortcut.META_CTRL_ON))
    }

    @Test
    fun `ctrl combo matches when ctrl is held`() {
        val shortcut = HotkeyShortcut(keyCode = 96, modifiers = HotkeyShortcut.META_CTRL_ON)
        assertTrue(shortcut.matches(eventKeyCode = 96, eventMetaState = HotkeyShortcut.META_CTRL_ON))
    }

    @Test
    fun `ctrl combo does not match without ctrl`() {
        val shortcut = HotkeyShortcut(keyCode = 96, modifiers = HotkeyShortcut.META_CTRL_ON)
        assertFalse(shortcut.matches(eventKeyCode = 96, eventMetaState = 0))
    }

    @Test
    fun `ctrl combo matches when extra non-modifier meta bits are present`() {
        // Caps-lock / num-lock bits are outside MODIFIER_MASK and must not block.
        val capsLock = 0x00004000 // META_CAPS_LOCK_ON, outside our mask
        val shortcut = HotkeyShortcut(keyCode = 96, modifiers = HotkeyShortcut.META_CTRL_ON)
        assertTrue(
            shortcut.matches(
                eventKeyCode = 96,
                eventMetaState = HotkeyShortcut.META_CTRL_ON or capsLock,
            ),
        )
    }

    @Test
    fun `shift+alt combo matches only when both held`() {
        val combo = HotkeyShortcut.META_SHIFT_ON or HotkeyShortcut.META_ALT_ON
        val shortcut = HotkeyShortcut(keyCode = 131, modifiers = combo)
        assertTrue(shortcut.matches(eventKeyCode = 131, eventMetaState = combo))
        assertFalse(shortcut.matches(eventKeyCode = 131, eventMetaState = HotkeyShortcut.META_SHIFT_ON))
        assertFalse(shortcut.matches(eventKeyCode = 131, eventMetaState = HotkeyShortcut.META_ALT_ON))
    }

    @Test
    fun `disabled shortcut never matches`() {
        val shortcut = HotkeyShortcut.Disabled
        assertFalse(shortcut.matches(eventKeyCode = 0, eventMetaState = 0))
        assertFalse(shortcut.matches(eventKeyCode = 96, eventMetaState = 0))
        assertEquals(0, HotkeyShortcut.Disabled.keyCode)
        assertFalse(HotkeyShortcut.Disabled.isEnabled)
    }
}
