package com.whispertype.android.core.audio

import android.media.AudioFormat

/**
 * Canonical audio pipeline format for the Gemini Live backend. Chunk sizes are
 * always derived from the sample rate and the 20 ms frame duration — never from
 * a magic byte count. [SAMPLE_RATE_HZ] is the Gemini Live supported input rate.
 */
object GemAudioFormat {
    /** Officially supported Gemini Live input sample rate (Hz). */
    const val SAMPLE_RATE_HZ = 16000

    /** Exact frame duration in milliseconds. */
    const val FRAME_MILLIS = 20

    /** Logical channel count. */
    const val CHANNELS = 1

    /** AudioRecord input channel mask (mono). */
    const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO

    /** AudioRecord PCM encoding (16-bit signed little-endian). */
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    /** Bytes per single PCM16 sample. */
    const val CHAR_BYTES = 2

    /** Bytes per 20 ms frame at [SAMPLE_RATE_HZ]: 20/1000 * 16000 * 2. */
    val bytesPerFrame: Int get() = FRAME_MILLIS * SAMPLE_RATE_HZ * CHAR_BYTES / 1000
}
