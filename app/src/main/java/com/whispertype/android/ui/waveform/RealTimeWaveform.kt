package com.whispertype.android.ui.waveform

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay

/** Matches [com.whispertype.android.platform.runtime.DictationCoordinator.Config.speechAmplitudeThreshold]. */
internal const val WAVEFORM_SPEECH_GATE = 0.02f

internal const val WAVEFORM_SILENCE_THRESHOLD = WAVEFORM_SPEECH_GATE

/** Sanitizes microphone levels before they reach Compose animation/drawing. */
internal fun normalizedWaveformAmplitude(amplitude: Float): Float =
    if (amplitude.isFinite()) amplitude.coerceIn(0f, 1f) else 0f

/** Collapses microphone-floor noise to one stable zero target. */
internal fun effectiveWaveformAmplitude(amplitude: Float): Float =
    normalizedWaveformAmplitude(amplitude).takeIf { it > WAVEFORM_SPEECH_GATE } ?: 0f

/** True when the pill should show animated bars instead of the resting line. */
internal fun isWaveformSpeaking(amplitude: Float): Boolean =
    normalizedWaveformAmplitude(amplitude) > WAVEFORM_SPEECH_GATE

/** The rolling phase is useful only while live audio is visibly above the gate. */
internal fun shouldAnimateWaveform(amplitude: Float, isListening: Boolean): Boolean =
    isListening && isWaveformSpeaking(amplitude)

/** True when the pill should show the resting flat line instead of bars. */
internal fun shouldDrawFlatWaveform(amplitude: Float): Boolean = !isWaveformSpeaking(amplitude)

/**
 * Boost mic RMS into a display level that fills the pill lane. Raw RMS is
 * typically 0.02–0.15 while speaking; without boost bars collapse to dots.
 */
internal fun waveformDisplayLevel(amplitude: Float): Float {
    if (!isWaveformSpeaking(amplitude)) return 0f
    // Once above the speech gate, fill the lane — raw RMS is too small to scale directly.
    return 1f
}

/** Half-height of one bar in px (from centerline). [mix] is 0..1 per bar. */
internal fun waveformBarHalfPx(displayLevel: Float, mix: Float, laneHeightPx: Float): Float {
    if (displayLevel <= 0f) return 0f
    val clampedMix = mix.coerceIn(0f, 1f)
    return (0.35f + 0.65f * clampedMix) * displayLevel * (laneHeightPx * 0.46f)
}

private const val PHASE_TICK_MS = 50L
private const val PHASE_CYCLE_MS = 550L
private val FULL_TURN = (Math.PI * 2).toFloat()
private val PHASE_STEP = FULL_TURN * PHASE_TICK_MS / PHASE_CYCLE_MS

private const val BAR_COUNT = 32

/**
 * Centered soundwave for the Studio hairline pill: a flat resting line while
 * silent, animated mirrored bars while speaking. Amplitude is a speech gate,
 * not a height scale — bars fill most of the lane once above the gate.
 */
@Composable
fun RealTimeWaveform(
    amplitude: Float,
    modifier: Modifier = Modifier,
    lineColor: Color = Color(0xFF5EE0C4),
    isListening: Boolean = true,
) {
    val normalized = normalizedWaveformAmplitude(amplitude)
    val targetSpeaking = if (isListening) isWaveformSpeaking(normalized) else false
    val targetDisplay = if (targetSpeaking) waveformDisplayLevel(normalized) else 0f

    val animatedDisplay by animateFloatAsState(
        targetValue = targetDisplay,
        animationSpec = tween(durationMillis = 140),
        label = "waveformDisplay",
    )

    val animate = shouldAnimateWaveform(normalized, isListening)
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate) {
        if (animate) {
            while (true) {
                phase = (phase + PHASE_STEP) % FULL_TURN
                delay(PHASE_TICK_MS)
            }
        } else {
            phase = 0f
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val midY = h * 0.5f
        val insetX = 4.dp.toPx()
        val flatStroke = 3.dp.toPx()

        if (animatedDisplay <= 0f) {
            drawLine(
                color = lineColor,
                start = Offset(insetX, midY),
                end = Offset(w - insetX, midY),
                strokeWidth = flatStroke,
                cap = StrokeCap.Round,
            )
            return@Canvas
        }

        val step = w / BAR_COUNT
        val barWidth = (step * 0.55f).coerceAtLeast(1.5f)

        for (i in 0 until BAR_COUNT) {
            val x = step * (i + 0.5f)
            val v1 = sin(phase * 1.0f + i * 0.55f)
            val v2 = sin(phase * 0.65f + i * 1.1f)
            val v3 = sin(phase * 1.4f + i * 0.25f)
            val mix = abs((v1 + v2 + v3) / 3f)
            val half = waveformBarHalfPx(animatedDisplay, mix, h)
            drawLine(
                color = lineColor,
                start = Offset(x, midY - half),
                end = Offset(x, midY + half),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}
