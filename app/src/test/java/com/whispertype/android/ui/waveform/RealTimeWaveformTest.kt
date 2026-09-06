package com.whispertype.android.ui.waveform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealTimeWaveformTest {

    private val laneHeightPx = 48f * 3f // ~48dp at mdpi scale for math checks

    @Test
    fun `phase animation stays stopped at effective silence`() {
        assertFalse(shouldAnimateWaveform(0f, isListening = true))
        assertFalse(shouldAnimateWaveform(WAVEFORM_SPEECH_GATE, isListening = true))
        assertEquals(0f, effectiveWaveformAmplitude(WAVEFORM_SPEECH_GATE), 0f)
    }

    @Test
    fun `flat line draws at and below speech gate`() {
        assertTrue(shouldDrawFlatWaveform(0f))
        assertTrue(shouldDrawFlatWaveform(WAVEFORM_SPEECH_GATE))
        assertFalse(shouldDrawFlatWaveform(WAVEFORM_SPEECH_GATE + 0.001f))
    }

    @Test
    fun `phase animation starts above speech gate while listening`() {
        assertTrue(shouldAnimateWaveform(WAVEFORM_SPEECH_GATE + 0.001f, isListening = true))
    }

    @Test
    fun `phase animation stays stopped when not listening`() {
        assertFalse(shouldAnimateWaveform(1f, isListening = false))
    }

    @Test
    fun `typical speech RMS boosts to full display level`() {
        assertEquals(0f, waveformDisplayLevel(0f), 0f)
        assertEquals(0f, waveformDisplayLevel(0.02f), 0f)
        assertEquals(1f, waveformDisplayLevel(0.05f), 0f)
        assertEquals(1f, waveformDisplayLevel(0.15f), 0f)
        assertEquals(1f, waveformDisplayLevel(1f), 0f)
    }

    @Test
    fun `typical speech bars occupy at least 30 percent of lane height`() {
        val display = waveformDisplayLevel(0.05f)
        val minHalf = waveformBarHalfPx(display, mix = 0f, laneHeightPx)
        val minBarHeight = minHalf * 2f
        assertTrue(
            "quietest bar should be visible",
            minBarHeight >= laneHeightPx * 0.30f,
        )
    }

    @Test
    fun `loudest bars stay within lane without clipping past pill`() {
        val display = waveformDisplayLevel(1f)
        val maxHalf = waveformBarHalfPx(display, mix = 1f, laneHeightPx)
        val maxBarHeight = maxHalf * 2f
        assertTrue(maxBarHeight <= laneHeightPx * 0.96f)
    }

    @Test
    fun `invalid and out of range amplitudes are normalized`() {
        assertEquals(0f, normalizedWaveformAmplitude(Float.NaN), 0f)
        assertEquals(0f, normalizedWaveformAmplitude(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(0f, normalizedWaveformAmplitude(-1f), 0f)
        assertEquals(0f, normalizedWaveformAmplitude(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1f, normalizedWaveformAmplitude(2f), 0f)
    }
}
