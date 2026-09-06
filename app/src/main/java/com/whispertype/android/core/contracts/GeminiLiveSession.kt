package com.whispertype.android.core.contracts

import com.whispertype.android.core.model.AudioChunk
import com.whispertype.android.core.model.GeminiEvent
import com.whispertype.android.core.model.SendResult
import kotlinx.coroutines.flow.Flow

/**
 * One Gemini Live session. Readiness is distinct from transport connection:
 * [awaitReady] returns only after the server setup acknowledgement.
 *
 * A dictation session follows an explicit activity lifecycle: [startActivity]
 * opens a push-to-talk activity (or is a no-op under automatic VAD), [sendAudio]
 * streams ordered PCM frames, and [endActivity] closes the activity with the
 * configured realtime completion boundary. [close] is idempotent.
 *
 * [SendResult] is returned from every mutating call so callers can fail
 * promptly when a boundary or frame cannot be queued, instead of waiting for a
 * completion message that can never arrive.
 */
interface GeminiLiveSession {
    /** Blocks until the server acknowledges setup; throws on failure/timeout. */
    suspend fun awaitReady()

    /**
     * Opens the realtime activity for the push-to-talk turn. Rejected before
     * readiness. Under automatic VAD this is a no-op that reports success.
     */
    suspend fun startActivity(): SendResult

    /** Sends one audio chunk; rejected unless an activity is started and not yet ended. */
    suspend fun sendAudio(chunk: AudioChunk): SendResult

    /**
     * Closes the realtime activity with the configured completion boundary
     * (activityEnd for manual signaling, audioStreamEnd for automatic VAD).
     * Rejected unless an activity is started.
     */
    suspend fun endActivity(): SendResult

    /** Server events (transcripts, turn complete, failures). */
    fun events(): Flow<GeminiEvent>


    /** Idempotent close of the WebSocket and all session resources. */
    suspend fun close()
}
