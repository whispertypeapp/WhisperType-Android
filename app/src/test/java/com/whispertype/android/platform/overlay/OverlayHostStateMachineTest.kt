package com.whispertype.android.platform.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure attach/detach lifecycle tests for [OverlayHostStateMachine]. */
class OverlayHostStateMachineTest {

    @Test
    fun `fresh machine starts detached`() {
        assertEquals(OverlayHostStatus.Detached, OverlayHostStateMachine().status)
    }

    @Test
    fun `attach request proceeds from detached`() {
        val m = OverlayHostStateMachine()
        assertTrue(m.attachRequested())
        assertEquals(OverlayHostStatus.AttachPending, m.status)
    }

    @Test
    fun `successful attach moves to attached`() {
        val m = OverlayHostStateMachine()
        m.attachRequested()
        m.attachSucceeded()
        assertEquals(OverlayHostStatus.Attached, m.status)
    }

    @Test
    fun `duplicate attach while attached is a no-op`() {
        val m = OverlayHostStateMachine()
        m.attachRequested()
        m.attachSucceeded()
        assertFalse(m.attachRequested())
        assertEquals(OverlayHostStatus.Attached, m.status)
    }

    @Test
    fun `duplicate attach while pending is a no-op`() {
        val m = OverlayHostStateMachine()
        m.attachRequested()
        assertFalse(m.attachRequested())
        assertEquals(OverlayHostStatus.AttachPending, m.status)
    }

    @Test
    fun `failed attach moves to attach failed`() {
        val m = OverlayHostStateMachine()
        m.attachRequested()
        m.attachFailed()
        assertEquals(OverlayHostStatus.AttachFailed, m.status)
    }

    @Test
    fun `retry after attach failed is permitted`() {
        val m = OverlayHostStateMachine()
        m.attachRequested()
        m.attachFailed()
        assertTrue(m.attachRequested())
        assertEquals(OverlayHostStatus.AttachPending, m.status)
    }

    @Test
    fun `detach from attached removes window`() {
        val m = OverlayHostStateMachine()
        m.attachRequested()
        m.attachSucceeded()
        assertTrue(m.detachRequested())
        assertEquals(OverlayHostStatus.Detached, m.status)
    }

    @Test
    fun `detach while pending cancels attach without remove`() {
        val m = OverlayHostStateMachine()
        m.attachRequested()
        // View not yet added: detach floods back to Detached and does not remove.
        assertFalse(m.detachRequested())
        assertEquals(OverlayHostStatus.Detached, m.status)
    }

    @Test
    fun `detach while detached is idempotent no-op`() {
        val m = OverlayHostStateMachine()
        assertFalse(m.detachRequested())
        assertEquals(OverlayHostStatus.Detached, m.status)
    }

    @Test
    fun `detach while attach failed is idempotent no-op`() {
        val m = OverlayHostStateMachine()
        m.attachRequested()
        m.attachFailed()
        assertFalse(m.detachRequested())
        assertEquals(OverlayHostStatus.AttachFailed, m.status)
    }

    @Test
    fun `recovering only when not detached`() {
        val m = OverlayHostStateMachine()
        m.recovering()
        assertEquals(OverlayHostStatus.Detached, m.status)

        m.attachRequested()
        m.attachSucceeded()
        m.recovering()
        assertEquals(OverlayHostStatus.Recovering, m.status)
    }

    @Test
    fun `detach from recovering removes window`() {
        val m = OverlayHostStateMachine()
        m.attachRequested()
        m.attachSucceeded()
        m.recovering()
        assertTrue(m.detachRequested())
        assertEquals(OverlayHostStatus.Detached, m.status)
    }
}
