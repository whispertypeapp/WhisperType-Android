package com.whispertype.android.core.model

/**
 * Events emitted by a [com.whispertype.android.core.contracts.GeminiLiveSession].
 * Distinct from transport-level connection state: connection does not imply
 * readiness.
 */
sealed interface GeminiEvent {
    /** Server setup acknowledgement received; audio transmission may begin. */
    data object Ready : GeminiEvent

    /**
     * Which server text channel produced a [TranscriptCandidates] event.
     * [INPUT] is the raw ASR of the user's speech (`inputTranscription`);
     * [ECHO] is the model's own spoken reply (`outputTranscription`), which the
     * systemInstruction controls (verbatim echo, polish, Latin script).
     */
    enum class TranscriptSource { INPUT, ECHO }

    /** Transcript candidates for one frame, tagged by their source channel.
     *  [isFinal] is true only for a committed final segment
     *  (`inputTranscription`); revisable partials (`interimInputTranscription`)
     *  are false. The coordinator never settles on a partial. */
    data class TranscriptCandidates(
        val candidates: List<ResultCandidate>,
        val source: TranscriptSource = TranscriptSource.INPUT,
        val isFinal: Boolean = false,
    ) : GeminiEvent

    /** Activity end acknowledged by the server. */
    data object TurnComplete : GeminiEvent

    /** Model generation ended; transcription messages may still arrive independently. */
    data object GenerationComplete : GeminiEvent

    /** The current model generation was interrupted by new realtime input. */
    data object Interrupted : GeminiEvent

    /** Advance notice; [timeLeft] is the server's protobuf JSON duration string. */
    data class GoAway(val timeLeft: String?) : GeminiEvent

    /** Server closed the turn/session. */
    data object SessionEnd : GeminiEvent

    /** A typed, non-sensitive failure. */
    data class Failed(val failure: DictationFailure) : GeminiEvent
}