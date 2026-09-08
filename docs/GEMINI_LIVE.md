# Gemini Live Voice Engine — Transcription & Wire Reference

> **0.10.0 status (read first).** The engine now runs on the dedicated streaming
> transcription model **`gemini-3.5-transcribe-live`** (Live API public preview).
> The **echo channel is deleted**: no `outputAudioTranscription`, no
> `systemInstruction`, no polish levels, no echo barrier stack. Dictation is the
> server's `inputTranscription` (committed final segments) plus
> `interimInputTranscription` (revisable partials); text shaping runs server-side
> in the model's `smart` transcription mode; a BCP-47 hint (`en-US` / `hi-IN`)
> matches the speech mode. The historical narrative below (echo discovery,
> polish levels, 0.6.2 barrier stack) describes how earlier versions worked —
> the code no longer contains any of it.

> **Version status.** This document was consolidated at `0.4.2`; the app is now
> `0.10.0`. The wire reference (Part 2) remains accurate. Since 0.4.2 the
> following changed:
> - **The echo is the primary dictation source.** `outputTranscription` is **on
>   by default** and `inputTranscription` is the fast fallback — the exact
>   reverse of the 0.4.2-era "output is never a candidate source" note. See the
>   "0.4.1 echo" section. **(Removed in 0.10.0 — echo channel deleted.)**
> - **Hard deadline is 20 s, settle debounce is 250 ms** (not 3 s / 250 ms as
>   some 0.4.2 sections state; the doc later self-corrects to 20 s / 600 ms).
>   **(0.10.0: the barrier stack is a single 250 ms quiet window + one 2.5 s
>   tail backstop.)**
> - **0.6.0**: NONE/LOW polish turns `outputAudioTranscription` **off** and
>   settles on the raw ASR in ~0.7 s flat (no echo wait). The warm pool is
>   profile-aware (a changed polish/language/echo setting never reuses a stale
>   session). Experimental segmented activities (Settings → "Segment at pauses")
>   split the recording at silence with `activityHandling = NO_INTERRUPTION`;
>   device-pending. **(0.10.0: polish levels and the echo setting are gone;
>   the warm profile is language + activity flags only.)**

This document is the consolidated reference for the **Gemini Live dictation
engine**: how it was built, what is sent and read on the wire, the exact
mechanism, how the modules connect, the session/settlement state machines, and
the failsafes. **Part 1** describes the transcription engine (formerly
`docs/GEMINI_LIVE_TRANSCRIPTION.md`); **Part 2** is the exhaustive, code-verified
wire reference (formerly `docs/GEMINI_LIVE_WIRE_REFERENCE.md`). Where a behavior
is observed-on-device rather than proven in code, it is labeled as such.

---

## Part 1 — Transcription engine

> **0.10.0 — the transcribe engine (current):** audio streams over the Live
> WebSocket to `gemini-3.5-transcribe-live` (TEXT modality, `smart` mode,
> optional `languageCodes` hint, manual activity signaling). The server returns
> revisable partials on `interimInputTranscription` and committed final segments
> on `inputTranscription`; both feed the session accumulator as INPUT candidates.
> Settlement waits one 250 ms quiet window plus one 2.5 s tail backstop from the
> activity-end boundary, then inserts the accumulated text verbatim (a fragment
> guard refuses truncated long dictations). Hinglish code-mixing is native to the
> model; whatever text it returns is inserted as-is.

> **0.4.1 — the echo discovery (historical, verified by host probes against
> `gemini-3.1-flash-live-preview`):** `inputTranscription` is pure ASR and is
> **not** influenced by the `systemInstruction`. The model's *spoken reply*
> (`outputTranscription`) IS controlled by the instruction, so the engine
> instructed the model to **repeat the user's speech back verbatim** (a styled
> echo) and read `outputTranscription` as the **primary dictation source**.
> This whole architecture was deleted in 0.10.0. See §8.

---

### 1. TL;DR — what the engine is

WhisperType captures 16 kHz mono PCM16 from the microphone and streams it over
one WebSocket to Google's **Gemini Live** API (`BidiGenerateContent`) using the
**`gemini-3.5-transcribe-live`** model — Google's dedicated streaming
transcription model. The app is **push-to-talk**: it manually delimits each
utterance with `realtimeInput.activityStart` / `realtimeInput.activityEnd`
(automatic VAD is disabled). The server streams the recognized speech as
revisable partials (`serverContent.interimInputTranscription`) and committed
final segments (`serverContent.inputTranscription`) — the only dictation source.
Text shaping (disfluency removal, self-corrections, formatting, casing) runs
server-side in the model's `smart` mode; a `languageCodes` hint (`en-US` /
`hi-IN`) biases language detection and code-mixing. `modelTurn` text is never
used.

---

### 2. How it was built (the steps)

The engine was rebuilt from a known-failing state (`0.2.11`) through a
six-release remediation plan plus a 0.3.1 robustness pass.

#### 2.1 The failure being solved
The Live server rarely returned the user's speech through `inputTranscription`,
dictation mostly produced no text, and stop-to-insert latency was high (with
8–10 s finalization stalls). The original 0.3-era diagnosis (the superseded failure-report docs)
isolated client defects: the app closed the wrong input stream
(`clientContent.turnComplete` instead of a realtime activity boundary), it
interleaved an unfinished text prime with realtime audio, it used the model's
own speech (`outputTranscription`) as a fallback, and the client had session
races, main-thread contention, and additive timers.

#### 2.2 Release A (0.2.12) — instrumentation + protocol experiment
- `MutableSessionMetrics`: session-local **monotonic** stage timing (tap, key,
  settings, socket open, setup complete, capture, first audio, first transcript,
  stop, quiesce, activity end, turn complete, settled, insertion) plus aggregate
  counters (frames, queue depth, transcription counts, deadline/overflow flags).
  An injectable clock makes it JVM-testable; durations are never wall-clock.
- Experimental realtime wire builders: `activityStart`, `activityEnd`,
  `audioStreamEnd`, and setup support for `realtimeInputConfig`.
- Exact wire tests.

#### 2.3 Release B (0.2.13) — production protocol correction
- **Deleted the dictation prime** (`sendTextTurn` / `buildTextTurn` /
  `DICTATION_PRIME_TEXT`): the normal path sends **no `clientContent`**.
- `inputTranscription` became the **only** candidate source; `outputTranscription`
  and `modelTurn` text are never selected.
- Setup now uses **manual activity signaling**: `realtimeInputConfig.
  automaticActivityDetection.disabled = true`, no systemInstruction, no output
  transcription, audio format stays `audio/pcm;rate=16000`.
- New session contract: `startActivity() / sendAudio() / endActivity()` with an
  enforced `Connecting -> Ready -> ActivityStarted -> ActivityEnded -> Closed`
  state machine (duplicate start/end send no second wire message; audio before
  start / after end is rejected; a failed send rejects immediately).
- The runtime enforces wire order **setup -> setupComplete -> activityStart ->
  ordered audio -> activityEnd** and fails promptly (typed `gemini_transport`)
  instead of waiting 8 s when a boundary is rejected.

#### 2.4 Release C (0.2.14) — session + audio correctness
- Extracted a host-testable **`DictationCoordinator`** with a per-session
  `ActiveLiveSession` holder (one ownership boundary for session, capture,
  transcript accumulator, metrics, and every job).
- Duplicate START rejected synchronously; every event/callback validates session
  identity; cleanup is compare-and-clear (an old holder can never clear a newer
  session).
- Insertion results correlated by `KEY_SESSION_ID`; stale responses ignored.
- `AudioCapture` gives the **producer exclusive `Chunker` ownership**: orderly
  shutdown unblocks the blocking read, flushes the zero-padded partial frame,
  and closes the channel before the completion boundary (bounded
  `awaitQuiescence`, hard-stop fallback). Capture read failures fail the session.
- Nine coordinator race tests + audio shutdown tests.

#### 2.5 Release D (0.2.15) — local latency
- Secret retrieval (file/Keystore/AES), `AudioRecord` construction/start, and
  base64/JSON/queue work all moved off the main thread.
- Eager in-memory runtime-settings snapshot replaces two sequential DataStore
  `first()` reads.
- One service-scoped `OkHttpClient` shared by every session.
- UI amplitude sampled ~16.7 Hz while capture/transmission stay 50 Hz; raw
  server-frame and transcript-content logging removed.

#### 2.6 Release E (0.2.16) — settlement + fast finalization
- `TranscriptAccumulator` merges streaming `inputTranscription` revisions into
  one cumulative value (extensions, duplicates, corrections, word-boundary
  protection); only the settled candidate is validated and inserted.
- One **absolute 3 s monotonic deadline** from STOP; a **250 ms settle debounce**
  resets on each revision but can never extend the deadline.
- Hard-deadline behavior: valid transcript -> insert; none -> `gemini_no_transcript`;
  a clearly provisional fragment -> fail. Early `turnComplete` is retained and
  reused. Seven virtual-time settlement tests.

#### 2.7 Release F (0.2.17) — prewarming + immediate capture
- `WarmLiveSessionManager`: eligibility-driven prewarm pool
  (`None/Connecting/Ready/Claimed/Closing/Backoff`), atomic claim, bounded
  backoff (1/2/5/10 s), conservative 30 s warm idle timeout; a claimed session
  is never closed by losing eligibility.
- Capture starts **immediately on the accepted tap**; cold-session PCM flows into
  a bounded 500-frame **pre-ready buffer** and drains in strict order once the
  session is ready (overflow fails with `gemini_connection_too_slow`).
  `DictationState.Listening` gains a `connecting` flag for the UI.

#### 2.8 Release 0.3.0 — consolidation + per-session diagnostics
- All A–F work consolidated, versioned 0.3.0, git commit + BuildConfig.GIT_COMMIT
  surfaced on the home screen.

#### 2.9 Release 0.3.1 — trust user speech, Hinglish, failsafes
On-device `SESSION DONE` analysis showed every "No transcript could be
recognized" failure had `inputTx=1` + `hardDeadline=false`: a transcript
**arrived** but `TranscriptSelector` rejected it. The selector's preamble /
greeting / acknowledgment / repetition heuristics had been designed for **model
output**, but the source is the user's own ASR speech, which legitimately starts
with "Okay…", "Yes…", "Of course…", "Note that…". Changes:
- **`TranscriptSelector` user-speech trust policy**: only rejects blank,
  punctuation-only, garbled, and Devanagari-in-English (Hinglish accepts
  Devanagari so it never errors). Removed the model-preamble/greeting/ack/
  repetition rejections. Added `diagnose()` for rejection-reason logging.
- **Hinglish reaches the model**: a Hinglish `systemInstruction` biases the Live
  transcription to Latin script. A `languageCode` on `inputAudioTranscription`
  was tried but the Live API **rejects it** ("unknown name language code"), so it
  is not sent.
- **Failsafes**: a lenient fallback inserts a rejected transcript unless truly
  unusable; a **Retry** button on the error panel; retryable errors persist
  until Retry/Dismiss; `SESSION DONE` logs `reject=<rule>` and `lenient=true`.

---

### 3. What actually ended up working (device-verified)

| Concern | Result |
| --- | --- |
| Server returns user speech as text | **Working** — `inputTranscription` arrives in nearly every session (the manual activity boundary + continuous real-time pacing fixed delivery). |
| No greetings / acknowledgments inserted | **Working** — the `systemInstruction` forces a verbatim echo, so the model never ad-libs. |
| Short sentences with natural openers ("Okay so…", "Yes…") | **Working** — selector trusts user speech (0.3.1). |
| Long English sentences | **Working** — same trust policy; settlement is debounce + 20 s hard deadline. |
| Hinglish (Hindi+English, Latin output) | **Working** — Hinglish `systemInstruction` produces Latin-script romanized Hinglish. |
| Stop-to-insert latency | Fast — typically ~0.5–1.6 s (debounce path), never waiting on `turnComplete` (which the server rarely sends). NONE/LOW polish settles on the raw ASR in ~0.7 s flat (0.6.0). |
| Retry / failsafe on genuine failures | **Working** — Retry button + persistent retryable errors + lenient fallback. |
| Known server behavior | The preview Live model's `inputTranscription` is still fundamentally intermittent; if it returns nothing within the hard deadline the app reports `gemini_no_transcript` with a Retry affordance. |

> **Not used / rejected on the wire**: `languageCode` inside
> `inputAudioTranscription` is rejected by the Live API. `modelTurn` text is
> impossible (voice-only model rejects TEXT modality). `clientContent.turnComplete`
> is never used for realtime audio. `outputAudioTranscription` is **on by
> default** since 0.4.1 (the echo channel) and is **off** only for NONE/LOW
> polish since 0.6.0.

---

### 4. The prompt (systemInstruction)

The only prompt sent is the `systemInstruction`
(`LanguageMode.liveInstruction(style)`); the dictation text prime was deleted in
Release B, so the normal path sends **no `clientContent`**. There is
deliberately **no dictation text prime** and no user-facing prompt field; the
`systemInstruction` exists because the failed `languageCode` approach cannot
bias script or polish — the instruction is the only lever over the model's
output.

`FlowRuntimeService` builds the session config with
`systemInstruction = language.liveInstruction(cachedPolishLevel)` where
`cachedPolishLevel` is one of the four `TranscriptionStyle` values. Style text,
per `TranscriptionStyle`:

- **NONE** — "Transcribe the user's speech verbatim, exactly as spoken. Do not
  add or change punctuation, capitalization, grammar, or wording. Output only
  the words spoken."
- **LOW** — "Transcribe the user's speech with light cleanup: add basic
  sentence punctuation and capitalization, but keep the exact words and natural
  spoken phrasing."
- **MEDIUM** — "Repeat the user's speech back and refine it into exceptional,
  publication-grade writing. Go well beyond clean copy: cut every filler,
  hesitation, redundancy, and false start; tighten wordy phrasing; choose the
  most precise and well-chosen words; vary sentence length and structure for
  rhythm; and use sophisticated punctuation (em dashes, colons, semicolons)
  where it clarifies. Restructure freely — reorganize ideas into the clearest
  logical flow, split or merge sentences, and add bullet points, numbered items,
  or paragraphs where they improve readability. The result should read like a
  professional editor spent time on it: polished, elegant, and effortless —
  while still carrying the user's message across and preserving every point they
  made."
- **HIGH** — "Repeat the user's speech back and transform it into masterfully
  crafted, publication-grade prose — writing a professional editor would
  publish. Eliminate every trace of spoken language: fillers, hesitations, false
  starts, repetition, and rambling. Condense each wordy phrase to its most
  elegant, economical form and choose words that are precise, vivid, and
  memorable. Craft varied sentence rhythms and deploy sophisticated punctuation
  (em dashes, colons, semicolons) deliberately. Reimagine the structure —
  reorganize ideas into the most logical, compelling order and use headings,
  bullet points, numbered lists, or paragraphs wherever they sharpen clarity.
  Elevate the tone to confident, articulate, assured writing. The final output
  should read like carefully edited, award-quality prose — while still carrying
  the user's message across and preserving every point they made."

Both MEDIUM and HIGH additionally carry the shared reliability tail: preserve
every idea and point, never omit/summarize away content, never change, invent,
or drop facts, names, numbers, dates, or quoted phrases (polish the wording,
never the substance), and keep the output close to the user's length — never
drastically shorten a long dictation.

NONE and LOW are strict verbatim; only MEDIUM and HIGH may rephrase, reorder,
or restructure (even add bullet points), and only while preserving every point.

Per `LanguageMode`:

| `LanguageMode` | `style` | instruction sent |
| --- | --- | --- |
| `ENGLISH` | `NONE` | **null — no `systemInstruction` at all** |
| `ENGLISH` | `LOW` / `MEDIUM` / `HIGH` | the style text only |
| `HINGLISH` | any | style text + Hinglish rule + `"Output only the transcription, nothing else."` |

The Hinglish rule appended for every style:

> "CRITICAL SCRIPT RULE: the output MUST be entirely in Latin (Roman) script.
> Never use Devanagari (Hindi) script - never output a single Devanagari
> character. Even though the user is speaking in Hindi, render every Hindi word
> in Latin letters as it sounds, exactly as if the user were speaking in Latin
> script (for example write 'main theek hoon', never 'मैं ठीक हूँ'). CRITICAL
> LANGUAGE RULE: NEVER translate the user's speech into a different language. If
> the user speaks English, output it in English exactly as spoken (with the
> polish style above applied) - never convert English words into Hindi or
> Hinglish. Only the Hindi words the user actually says are written in Latin
> letters. These rules apply to the whole output with no exceptions."

So English + `NONE` yields no instruction key; **Hinglish always carries the
Latin-script rule** regardless of style. For Hinglish with a NONE-style base the
composed instruction reads:

> "Transcribe the user's speech exactly as spoken. CRITICAL SCRIPT RULE: the
> output MUST be entirely in Latin (Roman) script. Never use Devanagari (Hindi)
> script - never output a single Devanagari character. Even though the user is
> speaking in Hindi, render every Hindi word in Latin letters as it sounds,
> exactly as if the user were speaking in Latin script (for example write 'main
> theek hoon', never 'मैं ठीक हूँ'). CRITICAL LANGUAGE RULE: NEVER translate the
> user's speech into a different language. If the user speaks English, output it
> in English exactly as spoken (with the polish style above applied) - never
> convert English words into Hindi or Hinglish. Only the Hindi words the user
> actually says are written in Latin letters. These rules apply to the whole
> output with no exceptions. Output only the transcription, nothing else."

This biases the Live transcription to produce **Latin-script romanized Hinglish**
for mixed Hindi+English speech — English spoken by the user stays English,
only Hindi words are romanized, never a wholesale translation.

---

### 5. Audio pipeline

```
AudioRecord (16 kHz mono PCM16, blocking read, real-time pace)
  -> Chunker (20 ms frames, 640 bytes; producer-owned; zero-pads final partial)
  -> AudioCapture.chunks (bounded ReceiveChannel, cap 64)
  -> DictationCoordinator.streamAudio (one ordered sender coroutine)
       |-- if session ready:  sendAudio(chunk) directly (base64+JSON+queue on Dispatchers.IO)
       `-- if still connecting: PreReadyAudioBuffer (500 frames ≈ 10 s) then drain in order
  -> OkHttpGeminiLiveSession.sendAudio -> WebSocket
```

- **Orderly shutdown** (STOP): `requestStop()` unblocks the blocking read; the
  producer flushes all complete frames plus the zero-padded partial frame, then
  closes the channel; the sender drains and joins before `activityEnd` is sent.
- **Amplitude**: computed once in the capture path from a frame slice; UI
  publication is throttled to ~16.7 Hz while audio stays at 50 Hz.
- **Pre-ready buffering** (cold connect): bounded memory-only buffer; overflow
  yields a typed `gemini_connection_too_slow` failure (no silent drops).

---

### 6. Module connections

```
Main process (FlowRuntimeService)                      :accessibility process
+----------------------------------------------+        +-----------------------+
| overlay intents (START/STOP/CANCEL/RETRY/    |        | WhisperTypeAccessibility
|   DISMISS)  ->  DictationCoordinator         |        |   Service
|   DictationHost impl (this service):         |        |   - EditorTracker (focus/keyboard)
|   - resolveSession (key+settings+session)    |        |   - target capture + commitText
|   - startCapture (permission+FGS+AudioRecord)|        |   - eligibility -> IPC
|   - sendInsertion (IPC)                      |        +-----------------------+
|   - publish(state) / onSessionFinished(...)  |                    ^
|                                              |        Messenger IPC (RuntimeIpc):
+----------------------------------------------+        MSG_INSERT / MSG_INSERT_RESULT
        |  resolveSession returns
        v
DictationCoordinator (pure orchestration, JVM-testable)
  - ActiveLiveSession holder: session, capture, accumulator, metrics, jobs
  - lifecycle: start -> runSession -> Listening -> stop -> Finalizing -> settle -> insert
  - finalization: 3 s hard deadline + 250 ms settle debounce
  - TranscriptAccumulator -> TranscriptSelector (trust policy + diagnose)
  - failsafes: lenient fallback insert, retry()/dismiss(), persistent errors
        |  claim()/cold
        v
WarmLiveSessionManager (eligibility-driven prewarm, 30 s idle, 1/2/5/10 s backoff)
        |
        v
OkHttpGeminiLiveSession (implements GeminiLiveSession)
  - state machine: Connecting -> Ready -> ActivityStarted -> ActivityEnded -> Closed
  - startActivity / sendAudio / endActivity / awaitReady / events() / close
        |
        v
GeminiLiveWire (pure codec)   <--binary frames-->   wss://.../BidiGenerateContent
```

The key seams/interfaces (session contract, `DictationHost`, `AudioPipeline`,
`TranscriptAccumulator` / `TranscriptSelector`, `MutableSessionMetrics`, the warm
pool, and `RuntimeIpc`) are described with the module-interaction sequence in
Part 2 §18.

---

### 7. Transcript settlement

#### 7.1 `TranscriptAccumulator` merge rules

`accept(message)` keeps **one cumulative current value** and bumps
`revisionCount` on every accepted change. Priority (exact code order):

1. **Blank/whitespace** message (`message.trim().isEmpty()`) → ignored, no bump.
2. **Exact duplicate** (`message == current`) → ignored, no bump.
3. **No current yet** (`current == null`) → accepted as-is.
4. **Cumulative extension** (`isCumulativeExtension`) → merged; the longer
   cumulative value wins. Rule: `message.startsWith(current)` AND the character
   right after `current` is whitespace OR `current` itself already ends on a
   trailing whitespace. Fragments are never trimmed before this check, because
   internal spaces can carry word boundaries.
5. **Reverse prefix** (`current.startsWith(message)`, a shorter/partial
   revision) → ignored; the longer cumulative value wins.
6. **Otherwise** (correction/replacement of a prior provisional value) →
   replaced.

The coordinator keeps **one** accumulator, fed by both the transcribe model's
revisable partials (`interimInputTranscription`) and committed finals
(`inputTranscription`) — each arrives as a cumulative revision of the current
utterance, which the rules above handle. (The 0.4.1-era delta-append echo
accumulator was deleted in 0.10.0.)

#### 7.2 `TranscriptSelector` trust policy + `RejectionRule`

The selector trusts the user's own ASR speech (`inputTranscription`) and only
rejects content that can never be usable dictation. `select(candidates)` does a
two-pass left-to-right scan: pass 1 finds the first candidate whose **cleaned**
text passes (and is not implausibly expanded); pass 2 (only if pass 1 fails)
finds the first candidate whose **raw** text passes. `cleaned` wins over `raw`;
within a tier, the earliest candidate wins. No invalid text is ever returned
(`TranscriptSelection.None` has no text — accessing it throws).

`reject(text, language)` fires, in priority order:

| `RejectionRule` | Condition |
| --- | --- |
| `BLANK` | `text.isBlank()` |
| `PUNCTUATION_ONLY` | no `isLetterOrDigit()` character |
| `GARBLED` | > 0 replacement (`\uFFFD`) / control chars and their ratio exceeds `GARBLED_CHAR_RATIO = 0.2` |
| `DEVANAGARI` | any Devanagari block char (`\u0900..\u097F`, `\uA8E0..\uA8FF`, `\u1CD0..\u1CFF`) **and** `language == LanguageMode.ENGLISH` |

Hinglish mode **accepts** Devanagari (prefer inserting it over erroring).
`diagnose(candidates)` mirrors `select`'s two-pass order and returns the first
`RejectionDiagnosis(rule, wordCount, charCount, hasDevanagari)` that would make
selection fail — logging only, never transcript text. A cleaned candidate is
also rejected when implausibly expanded (`cleanedWords > 10 * rawWords`, the one
remaining model-output heuristic). URLs, emails, identifiers, numbers, natural
punctuation, and code-switching survive because only the four rules above fire.

#### 7.3 Settlement timing and failsafes (`DictationCoordinator`, 0.10.0)

- **One source, no echo.** The accumulator text (from `interimInputTranscription`
  / `inputTranscription`) is the only dictation source; there is no echo channel
  to prefer or wait for.
- **250 ms quiet window** (`Config.settleDebounceMs`) — reset by every accepted
  ASR revision while finalizing; when it elapses with text present, settlement
  fires (`RAW_FALLBACK_TIMEOUT`, or `TURN_COMPLETE_QUIET` when the server's
  `turnComplete` was seen).
- **1.0.0: settlement waits for the tail final.** The transcribe model commits
  final `inputTranscription` segments *incrementally during speech*, and the
  tail segment's final arrives only **after** `activityEnd`. Quiet alone is
  therefore never enough: the quiet path settles only once a **post-boundary
  final segment** (`tailFinalSeen`) or a server done-hint (`turnComplete` /
  `generationComplete`) has arrived. This fixed on-device cut-off dictations
  where the session settled ~300 ms after stop on a provisional partial.
- **2.5 s tail backstop** (`Config.asrTailTimeoutMs`) — one absolute timer from
  the activity-end boundary. If the ASR tail never goes quiet (or never arrives),
  settlement proceeds on whatever text exists once it elapses; with no text at
  all it fails explicitly with `gemini_no_transcript`. This replaces the 0.6.2
  stack of echo-quiet (900 ms), echo-stall (2.5 s), source-missing grace (2 s)
  and hard deadline (20 s) barriers.
- **Lifecycle hints** (`turnComplete` / `generationComplete`) only re-evaluate
  settlement early; they are never transcript finality by themselves.
- **Provisional-fragment rule** — a selection shorter than 2 trimmed characters
  at the tail backstop (`isClearlyProvisional: text.trim().length < 2`, e.g.
  `"t"` from `"the"`) is rejected with `gemini_no_transcript` rather than
  inserted.
- **Fragment guard** (`isFragmentForDuration`) — a recording over 5 s that
  settles on a transcript below 30% of the duration's expected words is a
  truncated dictation and fails retryable (`gemini_transcript_fragment`) instead
  of inserting a fragment.
- **Lenient fallback** (`lenientAccept`) — when strict selection returns
  `None`, the source is still the user's own speech, so it is inserted unless:
  blank / no letter-or-digit, `diagnosis.rule == GARBLED`, or a fragment < 2
  chars at the tail backstop. `metrics.usedLenientFallback = true` when it
  fires.
- Settlement path: `accumulator.settledText()` →
  `ResultCandidate(raw, null, language)` → `selector.select` → `Cleaned`/`Raw` →
  insert (or provisional reject), or `None` → lenient fallback → insert, else
  `failNoTranscript` (`gemini_no_transcript`, retryable).

---

### 8. The echo architecture (0.4.1) — historical (deleted in 0.10.0)

#### 8.1 Why the echo
`inputTranscription` is produced by the server's ASR and is **not** influenced by
the `systemInstruction` — that is why script (Latin) and polish controls never
worked on it. The `systemInstruction` only shapes the model's *generated* reply.
Native audio Live models output **AUDIO only** (TEXT modality is unavailable),
and `AudioTranscriptionConfig` (the type of both `inputAudioTranscription` and
`outputAudioTranscription`) is an **empty message** — there is no `languageCode`
field. The only model-text channel is `outputTranscription`: the transcription of
the model's spoken reply. Official docs confirm the AUDIO-only constraint ("If
you need the model response as text, use the output audio transcription
feature").

#### 8.2 The verified mechanism (host probes, gemini-3.1-flash-live-preview)
- **The instruction registers and controls the model's spoken output.** With a
  verbatim-echo instruction the model repeated the input exactly
  (`the quick brown fox jumps over the lazy dog`); without one it greeted
  ("That's a classic pangram!…").
- **`outputTranscription` works even WITH a systemInstruction** — the old
  "systemInstruction suppresses output transcription" finding (Release-B era,
  2026-08-05) is invalid for this model.
- **Hinglish → Latin:** Devanagari input `मैं ठीक हूँ और कल office जाऊँगा` came
  back as `Main theek hoon aur kal office jaoonga` (the instruction forces
  Roman/Latin script in the model's output).
- **Polish levels work:** `um like i mean we should so uh meet on thursday for
  the project review` → HIGH echo = `We should meet on Thursday for the project
  review.`
- **Latency:** ~0.6 s (sentence), ~3 s (30 words), ~8 s (100 words) — the echo
  transcription streams as the model generates, not at 1× real time.

#### 8.3 How the app uses it
1. Setup keeps `responseModalities: ["AUDIO"]`, adds `outputAudioTranscription: {}`
   (default true), keeps `inputAudioTranscription: {}` and manual activity
   signaling, and sends the per-polish-level `systemInstruction` (with the
   Hinglish Latin rule).
2. The session emits `outputTranscription` as **ECHO** candidates and
   `inputTranscription` as **INPUT** candidates.
3. The coordinator accumulates both; settlement **prefers the echo** (always
   waits for it when it is streaming), falls back to the raw input only when no
   echo arrives (2 s watchdog) or at the 20 s deadline.

---

### 9. Reliability findings + the 0.4.2 settlement (verified 2026-08-06)

#### 9.1 What the probes showed (long dictations were lossy)

Calibration probes replayed 18-82 s of speech through the live API with the app's
exact wire shape (manual activity detection, `activityStart`/`activityEnd`):

| Input | `inputTranscription` | `outputTranscription` (echo) |
| --- | --- | --- |
| 9.6 s script | cumulative, complete (25 words) | cumulative, complete, verbatim |
| 42.5 s script | cumulative, complete (118 words), ~0.5 s after end | **word-level deltas**, verbatim complete, streamed ~10 s |
| 82.8 s lecture | **never delivered** (60-90 s wait) | word-level deltas, **2-4 word summary** |

The three failure modes users hit map directly to these findings:

1. **"Just the last word"** — the echo streams as per-word *deltas*; the old
   accumulator REPLACE rule kept only the last message. Fixed by delta-append
   accumulation (`TranscriptAccumulator(appendDeltas = true)` for the echo).
2. **"First few words" / "1-2 word summary"** — the model condenses long turns,
   so a long echo is inherently short. Fixed by the completeness gate: the echo
   is inserted only when its content-word ratio covers the raw ASR
   (`TranscriptCompleteness`), otherwise the complete raw is salvaged.
3. **"Everything is lost"** — on very long turns neither source is delivered.
   Mitigated by the completeness gate (a partial echo never discards the raw
   ASR) and settlement falling back to the raw ASR. The recording
   re-transcription backstop that once covered this case was removed: the app
   now uses only the `gemini-3.1-flash-live-preview` live model, so there is no
   second transcription endpoint to fall back on, and a truncated echo never
   triggers a retry.

#### 9.2 Settlement policy (0.4.2) — never lose the user's words

At settle time (`DictationCoordinator.selectSettledText`):

| Condition | Insert |
| --- | --- |
| Echo present and covers raw (ratio >= 0.6) | polished echo (`ECHO_COMPLETE`) |
| Echo absent | raw ASR (`RAW_ONLY`) |
| Echo present but partial/summary | raw ASR (`ECHO_PARTIAL_RAW`) |
| Raw absent (echo only) | echo (`ECHO_ONLY`); duration-sanity governs |

There is no retry path for "the echo was incomplete": a partial echo never
causes the whole dictation to fail, and a truncated echo never triggers a retry.
`failNoTranscript` only fires when nothing (echo or raw) is usable. The old
audio-recovery backstop (re-transcribing the retained recording via a REST
`generateContent` call) was removed: the app uses only the
`gemini-3.1-flash-live-preview` live model, so there is no other endpoint to
fall back on.

**Hinglish settles echo-only.** The raw `inputTranscription` ASR is never used
for Hinglish — the server transcribes the spoken Hindi in Devanagari, not the
Latin script the user expects — so only the instructed echo settles a Hinglish
session. The hardened system prompt forbids Devanagari outright, and a
live-model transliteration fallback covers any remaining script leakage.

#### 9.3 Settlement timing

- Echo-fallback raw settle starts **after `activityEnd`** (never on a
  provisional mid-activity ASR), with a 2 s echo grace window.
- The settle debounce is 600 ms (above the measured ~90-300 ms echo delta gaps)
  so settlement never lands mid-delta-stream.
- The 20 s hard deadline is unchanged; at the deadline the completeness gate
  guarantees coverage — the echo where complete, else the raw ASR.

#### 9.4 Diagnostics

Every session logs a `SESSION DONE` line (never transcript or audio) with the
settle path and word counts, e.g. `settle=ECHO_PARTIAL_RAW expW=93 settledW=118`.
Watch `settle=` on device: healthy short sessions are `ECHO_COMPLETE`; a
`settle=ECHO_PARTIAL_RAW` line means the echo was truncated and the complete raw
ASR was salvaged instead.

---

### 10. Verification

- JVM unit tests + lint + assemble: `./gradlew :app:testDebugUnitTest
  :app:lintDebug :app:assembleDebug`.
- On-device protocol + acceptance matrix: `docs/TESTING.md`.
- Remaining gates (require the phone): the full acceptance matrix, warm/cold
  latency budgets, StrictMode off-main confirmation, and the prewarm billing check.

---

## Part 2 — Wire reference

An exhaustive, code-verified mechanics reference for the Gemini Live WebSocket
(`BidiGenerateContent`) transport as implemented in this repo. Every field name,
message shape, timeout, and state listed below was read directly from the
source. Nothing here is inferred from the public API docs; where a behavior is
observed-on-device rather than proven in code, it is labeled as such.

This reference was written alongside the 0.4.0 evolution; the historical
planning documents that accompanied it have since been consolidated into the
`docs/` documentation hub.

This document contains **no real API keys, no transcripts, and no audio
content**. Every payload is shown as a placeholder (`<base64>`, `<text>`, ...).

---

### 11. Endpoint and authentication

The WebSocket endpoint is built by `GeminiSessionFactory.buildWsUrl`
(`platform/gemini/GeminiSessionFactory.kt`):

```kotlin
"wss://generativelanguage.googleapis.com/ws/" +
    "google.ai.generativelanguage.${config.apiVersion}." +
    "GenerativeService.BidiGenerateContent?key=$apiKey"
```

With the default `GeminiSessionConfig.DEFAULT_API_VERSION = "v1beta"` this is:

```
wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=<API_KEY>
```

Key facts:

- The **API key is a query parameter**, appended by `buildWsUrl`. The key is
  provided once per session by `GeminiSessionFactory.create(apiKey, config, client, metrics)`,
  which `require`s it non-empty.
- `OkHttpGeminiLiveSession.redactUrl` strips the key before any log line that
  touches the URL:
  ```kotlin
  url.replace(Regex("key=[^&]*"), "key=<redacted>")
  ```
  `redactUrl` is applied to the `onOpen` log line, so the authenticated URL
  never reaches logcat.
- The key is **never carried in any derived artifact**: `GeminiEvent`s carry
  only typed failures (`DictationFailure`), whose contract explicitly forbids
  keys, URLs, transcripts, editor content, and raw audio; `MutableSessionMetrics.summary()`
  emits only derived durations, counts, and flags; the `SESSION DONE` log and
  the opt-in history record (`FlowRuntimeService.onSessionFinished`) contain
  only the outcome, stage latencies, and (optionally) the settled transcript
  text. There is no code path that forwards the key to the overlay, the
  accessibility process, or the history repository.

---

### 12. Client → server messages (exact JSON)

All builders live in `platform/gemini/GeminiLiveWire.kt`. The codec is pure —
it builds and parses JSON strings but never touches a socket
(`OkHttpGeminiLiveSession` forwards the strings).

#### 12.1 `setup` — the mandatory first message (`buildSetup`)

Sent from `onOpen` (the moment OkHttp reports the socket is open, code 101). The
keys are built in this exact order by `buildJsonObject` (insertion-ordered):

```json
{
  "setup": {
    "model": "models/gemini-3.5-transcribe-live",
    "generationConfig": {
      "responseModalities": ["TEXT"]
    },
    "inputAudioTranscription": {
      "mode": "smart",
      "languageCodes": ["en-US"]
    },
    "realtimeInputConfig": {
      "automaticActivityDetection": {
        "disabled": true
      }
    }
  }
}
```

Per-key detail (from `GeminiLiveWire.buildSetup` and
`GeminiSessionConfig`):

| Key | Value | Condition |
| --- | --- | --- |
| `model` | `"models/${config.model}"` — `"models/gemini-3.5-transcribe-live"` (the pinned `GeminiSessionFactory.LIVE_MODEL`) | always |
| `generationConfig.responseModalities` | `["TEXT"]` (the config default `responseModalities = listOf("TEXT")`) | always, unless `config.omitGenerationConfig` |
| `inputAudioTranscription.mode` | `"smart"` (default) or `"verbatim"` | only when `config.inputAudioTranscription` (default `true`) |
| `inputAudioTranscription.languageCodes` | `["en-US"]` / `["hi-IN"]` from the speech mode | only when `config.transcriptionLanguageCode != null` |
| `realtimeInputConfig.automaticActivityDetection.disabled` | `true` | only when `config.automaticActivityDetectionDisabled` (default `true`) |
| `realtimeInputConfig.activityHandling` | `"NO_INTERRUPTION"` | only when `config.activityHandlingNoInterruption` (segmentation) |

> **0.10.0 — the transcribe wire.** `outputAudioTranscription` and
> `systemInstruction` are **never** sent (the echo channel and its instruction
> were deleted). `maxOutputTokens` is omitted by default so no server-side cap
> can truncate a long transcript. The `mode` field selects server-side shaping
> (`smart` = disfluency removal, self-corrections, formatting, casing polish;
> `verbatim` = literal). `languageCodes` is the documented BCP-47 hint for
> language detection / code-mixing (accepted by the transcribe model — the old
> 3.1-era rejection of a `languageCode` field applied to a different wire).

> **Preview caveat — resolved by live wire probe (2026-08-27).** The reported
> quirk where a generationConfig carrying `responseModalities: ["TEXT"]`
> suppresses final `inputTranscription` segments **did not reproduce**: a probe
> against the real endpoint using exactly the app's setup (TEXT modality +
> `smart` mode + `languageCodes` + manual activity signaling) streamed 10
> interims and committed one final segment, with `generationComplete`. Manual
> VAD (`activityStart`/`activityEnd`) works; **automatic VAD produced no
> transcripts at all** — the app's manual signaling is the correct production
> shape. `GeminiSessionConfig.omitGenerationConfig` remains as a one-line
> fallback if the endpoint behavior ever regresses; the default documented
> shape is verified working.

#### 12.2 Realtime activity boundaries and audio frames

Push-to-talk manual activity signaling (the production design —
`automaticActivityDetection.disabled = true`):

- **Start of utterance** (`buildActivityStart`), sent from `startActivity` on
  the `Ready -> ActivityStarted` transition, before the first audio frame:
  ```json
  { "realtimeInput": { "activityStart": {} } }
  ```
- **End of utterance** (`buildActivityEnd`), sent from `endActivity` on the
  `ActivityStarted -> ActivityEnded` transition, after the final audio frame:
  ```json
  { "realtimeInput": { "activityEnd": {} } }
  ```
- **Audio frame** (`buildAudioChunk`):
  ```json
  {
    "realtimeInput": {
      "audio": {
        "data": "<base64>",
        "mimeType": "audio/pcm;rate=16000"
      }
    }
  }
  ```
  `data` is base64 of raw PCM16 (16-bit signed little-endian, mono, 16 kHz —
  `GemAudioFormat.SAMPLE_RATE_HZ = 16000`, `CHANNELS = 1`, `ENCODING_PCM_16BIT`).
  Each frame is exactly **640 bytes** (`FRAME_MILLIS=20` ms: `20/1000 * 16000 * 2`
  via `GemAudioFormat.bytesPerFrame` and `AudioChunk.expectedByteCount`). The
  base64 + JSON + queue work runs on `Dispatchers.IO` inside `sendAudio`.

#### 12.3 What is NEVER sent (removed or unused)

| Message | Builder | Status |
| --- | --- | --- |
| `clientContent.turnComplete` (`{"clientContent":{"turnComplete":true}}`) | `buildTurnComplete` | **Never sent.** The builder still exists in the codec and is covered by a unit test, but no caller invokes it — the dictation prime (`sendTextTurn`/text turns, `clientContent.turns`) was deleted in Release B. |
| `realtimeInput.audioStreamEnd` (`{"realtimeInput":{"audioStreamEnd":true}}`) | `buildAudioStreamEnd` | **Not used in production.** It is the completion boundary for the automatic-VAD variant; `endActivity` selects it only when `automaticActivityDetectionDisabled == false`. The default config is `true` (manual), so the code path is dead in production. |
| `clientContent.turns` (text turns) | — | **Not used for dictation.** On 3.1, `send_client_content` is only for seeding history; realtime text goes through `realtimeInput.text`. |
| `languageCode` | — | **Never sent** — the API rejects it (see §12.1). `AudioTranscriptionConfig` is an empty message with no such field. |

---

### 13. Server → client messages (exact JSON + parse mapping)

Every server frame is decoded to UTF-8 and fed through
`GeminiLiveWire.parseServerMessage`, which routes on the **root** keys:

```kotlin
root.containsKey("setupComplete") -> SetupComplete
root.containsKey("setupError")    -> parseSetupError   -> SetupError(message)
root.containsKey("error")         -> parseTopLevelError -> SetupError(message)
root.containsKey("serverContent") -> parseServerContent -> ServerContent(...)
root.containsKey("goAway")        -> GoAway
else                              -> Unknown(raw)
```

Parse failures (any `Throwable`) fall back to `ServerMessage.Unknown(raw)`,
which is discarded by the session. `Json` is configured with
`ignoreUnknownKeys = true` and `isLenient = false`.

| Server payload | Parsed into | Behavior |
| --- | --- | --- |
| `setupComplete` | `ServerMessage.SetupComplete` | The **readiness gate**: `state: Connecting -> Ready`, `ready.complete(Unit)`, emits `GeminiEvent.Ready`. Only after this may audio be sent (`awaitReady`). |
| `setupError.error.message` | `ServerMessage.SetupError(message)` | Typed **`gemini_setup`** failure — `ready.completeExceptionally`, emits `GeminiEvent.Failed`. |
| top-level `error.message` | `ServerMessage.SetupError(message)` | Same typed `gemini_setup` failure as above. |
| `serverContent.interimInputTranscription.text` | `interimInputTranscription: String?` | **The revisable partial (0.10.0 transcribe model).** Emits `GeminiEvent.TranscriptCandidates(..., source = INPUT)` for live display; the accumulator treats it as an evolving revision. |
| `serverContent.inputTranscription.text` | `inputTranscription: String?` | **THE dictation source — committed final segment.** Emits `GeminiEvent.TranscriptCandidates(..., source = INPUT)` and counts `inputTranscriptionCount`. |
| `serverContent.outputTranscription.text` | `outputTranscription: String?` | Parsed for compatibility only; the transcribe model has no audio output, so this never arrives (echo channel deleted 0.10.0). |
| `serverContent.modelTurn.parts[].text` | `textParts: List<String>` | Parsed **for compatibility only**; never a candidate. The transcribe model is TEXT-modality and returns no modelTurn. |
| `serverContent.turnComplete` | `turnComplete: Boolean` | Lifecycle flag: sets `metrics.turnCompleteArrived = true`, emits `GeminiEvent.TurnComplete` (retained even when it precedes STOP — Release E7). A settlement hint only — quiet is still mandatory. |
| `serverContent.interrupted` | `interrupted: Boolean` | Lifecycle flag, parsed and carried on `ServerContent`; no dedicated action. |
| `goAway` | `ServerMessage.GoAway` | Emits `GeminiEvent.SessionEnd`. |
| anything else | `ServerMessage.Unknown(raw)` | Ignored. |

The **critical binary-frame fact**: the Gemini Live server sends every
server→client message as a **binary WebSocket frame (opcode 0x2)**. OkHttp
therefore routes them to the byte overload, and
`OkHttpGeminiLiveSession` has:

```kotlin
override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
    onMessage(webSocket, bytes.utf8())
}
```

Both overloads decode through the same parser. **This binary callback must
never be removed** — without it every server message would be silently dropped
and the session would hang at `Connecting` until the 15 s ready timeout.

---

### 14. Transport lifecycle

#### 14.1 Connection and keep-alive

- The OkHttp WebSocket is created with `Request.Builder().url(wsUrl)` in the
  `OkHttpGeminiLiveSession` constructor; the handshake completes with an
  **HTTP 101 upgrade** (`onOpen` logs `code=101`).
- `GeminiSessionFactory.defaultClient`:
  ```kotlin
  OkHttpClient.Builder()
      .pingInterval(Duration.ofSeconds(20))
      .readTimeout(0, TimeUnit.MILLISECONDS)
      .build()
  ```
  - **`pingInterval` = 20 s** — OkHttp sends a keep-alive ping every 20 s. The
    20 s pong window is observed in the wild as
    `onFailure "sent ping but didn't receive pong within 20000ms"` (OkHttp's
    standard timeout; mapped to a `gemini_transport` failure).
  - **`readTimeout` = 0** (infinite) — a server that streams nothing must not
    be killed by the read timeout while idle.
  - One long-lived client is shared service-wide (`FlowRuntimeService.sharedOkHttpClient`);
    its dispatcher/connection pool is never shut down per session.
- Observed close codes (recorded from on-device testing):
  - **1000** — normal close (`OkHttpGeminiLiveSession.close()` sends
    `close(NORMAL_CLOSE_CODE, "session closed")`).
  - **1008** — policy violation, seen as `"The operation was aborted"` and as
    invalid-API-key responses in tests (e.g. `1008 "API key not valid..."`).

#### 14.2 Readiness gate

`awaitReady()` is the **`setupComplete`** gate, not socket-open:

```kotlin
withTimeout(READY_TIMEOUT_MS) { ready.await() }
```

- `READY_TIMEOUT_MS = 15_000`. On timeout: `GeminiLiveException` with
  `gemini_setup` / `"Timed out waiting for the Gemini session to start."`,
  `recoverable = true`.
- `onClosing`/`onClosed` **before** `ready` completes also fail the gate with
  `gemini_setup` (reason text, or `"Connection closed (code N) before setup."`).
- `onFailure` completes the gate exceptionally with the raw throwable and (if
  the session was not already `closed`) emits `GeminiEvent.Failed(gemini_transport)`.

#### 14.3 Warm-pool lifecycle (`WarmLiveSessionManager`)

Eligibility-driven prewarm (focused non-secure editor, mic +
API key configured — `FlowRuntimeService.computeWarmEligibility`; the pool
stays eligible during `Starting` and retryable `Error` so a Ready socket can
be claimed before `Listening`):

- States: `None -> Connecting -> Ready(session) -> Claimed -> Closing`, with
  `Backoff(retryMs)` between failed attempts.
- **Reconnect backoff: 1 s, 2 s, 5 s, then 10 s** (`Config.backoffStepsMs`); the
  last step repeats (`getOrElse ... last()`), with `backoffAttempt` reset to 0
  on success.
- **Idle timeout: 180 s** (`Config.warmIdleTimeoutMs`, raised from 30 s in 1.0.8); when it fires, the warm
  session is closed and the pool returns to `None`. A `Claimed` session is never
  closed by the manager — it belongs to an active dictation.
- `claim()` atomically takes a `Ready` session, cancels the idle timer, sets
  `Claimed`, and starts a replacement prewarm. On a claim the coordinator sees
  `SessionResolution(ready = true)` and skips cold connect/buffering.
- **Billing gate (open)**: idle prewarm sessions may incur billable usage —
  verify before enabling prewarming in production (see `docs/PUSH_TO_PHONE_VIA_ADB.md`).

#### 14.4 Close and the transport state machine

```kotlin
private enum class State { Connecting, Ready, ActivityStarted, ActivityEnded, Closed }
```

Driven by one `AtomicReference`, so concurrent callers (audio sender + finalizer)
cannot violate it. Transition rules enforced in `startActivity` / `sendAudio` /
`endActivity` (rejection reasons are the `SendResult.Rejected` strings):

| Call | Allowed from | Rejected from |
| --- | --- | --- |
| `startActivity` | `Ready` (CAS → `ActivityStarted`, sends `activityStart`) | `Connecting` → `session_not_ready`; `ActivityEnded` → `activity_ended`; `Closed` → `socket_closed`. Duplicate start in `ActivityStarted` → `Accepted`, **no second wire message**. |
| `sendAudio` | `ActivityStarted` only | `Connecting` → `session_not_ready`; `Ready` → `audio_before_activity_start`; `ActivityEnded` → `audio_after_activity_end`; `Closed` → `socket_closed`. |
| `endActivity` | `ActivityStarted` (CAS → `ActivityEnded`, sends `activityEnd`) | `Connecting` → `session_not_ready`; `Ready` → `activity_not_started`; `Closed` → `socket_closed`. Duplicate end in `ActivityEnded` → `Accepted`, **no second wire message**. |
| `close` | **any state** | never — idempotent (`AtomicBoolean` compare-and-set) |

`close()` is idempotent: it CASes `closed`, forces `State.Closed`, sends
`close(1000, "session closed")`, nulls the socket, completes the ready deferred
exceptionally, and closes the event channel. A failed `WebSocket.send` on any
path returns `SendResult.Rejected` immediately and forces `State.Closed` —
callers never wait for a completion message that can never arrive.

---

### 15. Full session timeline

#### 15.1 Wire-order invariant

For one dictation turn, message order is always:

```
setup -> setupComplete -> activityStart -> ordered audio frames -> activityEnd
```

The coordinator guarantees this by (a) sending `setup` in `onOpen` before
anything else, (b) gating the first audio on `awaitReady()` (the `setupComplete`),
(c) opening the activity before the first frame, and (d) draining the producer
and **joining the audio sender** before `endActivity` is sent
(`startFinalization`: `requestStop` → `awaitQuiescence` → `audioJob.join` →
`CaptureQuiesced` → `endActivity`).

#### 15.2 Monotonic stage list (`MutableSessionMetrics.Event`)

All timestamps use the injectable monotonic clock (`nowNanos`), never wall
clock. `mark(event)` records the **first** timestamp for each event; later marks
are ignored. The enum, in definition order, and the recording point of each:

| Event | Recorded by | Where |
| --- | --- | --- |
| `Tap` | `DictationCoordinator.start()` | the accepted tap on the bubble |
| `KeyLoadStarted` | `FlowRuntimeService.resolveSession` | start of key resolution |
| `KeyLoaded` | `FlowRuntimeService.resolveSession` | key provider returned (warm claim or cold key load) |
| `SettingsReady` | `FlowRuntimeService.resolveSession` | runtime settings snapshot resolved |
| `SocketCreated` | `FlowRuntimeService.resolveSession` | warm claim or `GeminiSessionFactory.create` |
| `SocketOpen` | `OkHttpGeminiLiveSession.onOpen` | HTTP 101 upgrade |
| `SetupComplete` | `OkHttpGeminiLiveSession.onMessage` | `setupComplete` received |
| `CaptureStartRequested` | `FlowRuntimeService.startCapture` | before `AudioRecord` construction |
| `CaptureStarted` | `FlowRuntimeService.startCapture` | `AudioStartResult.Started` |
| `ActivityStartQueued` | `OkHttpGeminiLiveSession.startActivity` + coordinator | `activityStart` enqueued on the socket |
| `FirstAudioQueued` | `DictationCoordinator.sendChunk` | first accepted audio frame |
| `FirstInputTranscript` | `OkHttpGeminiLiveSession.onServerContent` | first non-empty `inputTranscription` |
| `Stop` | `DictationCoordinator.stop()` / `cancel()` | user STOP/CANCEL (and auto-stop) |
| `CaptureQuiesced` | `DictationCoordinator.startFinalization` | producer drained, sender joined |
| `LastAudioQueued` | `DictationCoordinator.streamAudio` | capture channel closed, final frame sent |
| `ActivityEndQueued` | `OkHttpGeminiLiveSession.endActivity` + coordinator | `activityEnd` enqueued |
| `TurnComplete` | `OkHttpGeminiLiveSession.onServerContent` | `serverContent.turnComplete` seen |
| `TranscriptSettled` | `DictationCoordinator.settle` | selection or failsafe resolution |
| `InsertionRequested` | `DictationCoordinator.insertSettled` | insert IPC handed to accessibility |
| `InsertionResult` | `DictationCoordinator.onInsertionResult` | insert result routed by session id |

Derived duration accessors: `tapToCaptureMs`, `tapToSetupCompleteMs`,
`tapToFirstAudioQueuedMs`, `firstAudioToFirstTranscriptMs`,
`stopToCaptureQuiescedMs`, `stopToActivityEndQueuedMs`, `stopToTurnCompleteMs`,
`stopToSettledMs`, `stopToInsertionResultMs`, `insertionRequestedToResultMs`.

#### 15.3 Decoding a `SESSION DONE` line

At the terminal state `FlowRuntimeService.onSessionFinished` logs exactly one
line (`metrics.summary()`, joined tokens, no transcript/audio/keys):

```
SESSION DONE outcome=Success tapToCapture=105ms setup=718ms tapToFirstAudio=728ms
firstAudioToFirstTranscript=7861ms stopToQuiesce=65ms stopToActivityEnd=65ms
stopToTurnComplete=65ms stopToSettled=553ms stopToInsert=608ms insertToResult=49ms
captured=393 accepted=393 rejected=0 maxQueue=1858 inputTx=1 outputTx=0
turnComplete=false hardDeadline=false overflow=false [reject=DEVANAGARI] [lenient=true]
```

Field by field (`MutableSessionMetrics.summary()` order):

| Token | Meaning |
| --- | --- |
| `outcome=Success` | `DictationState` class name (`Success`, `Error`, `Cancelled`, ...) |
| `tapToCapture=105ms` | `tapAt -> captureStartedAt` |
| `setup=718ms` | `tapAt -> setupCompleteAt` |
| `tapToFirstAudio=728ms` | `tapAt -> firstAudioQueuedAt` |
| `firstAudioToFirstTranscript=7861ms` | `firstAudioQueuedAt -> firstInputTranscriptAt` |
| `stopToQuiesce=65ms` | `stopAt -> captureQuiescedAt` |
| `stopToActivityEnd=65ms` | `stopAt -> activityEndQueuedAt` |
| `stopToTurnComplete=65ms` | `stopAt -> turnCompleteAt` (absent if `turnCompleteArrived=false`) |
| `stopToSettled=553ms` | `stopAt -> transcriptSettledAt` |
| `stopToInsert=608ms` | `stopAt -> insertionResultAt` |
| `insertToResult=49ms` | `insertionRequestedAt -> insertionResultAt` |
| `captured=393` | PCM frames captured by the producer |
| `accepted=393` | frames accepted by `sendAudio` |
| `rejected=0` | frames rejected by the session state machine |
| `maxQueue=1858` | peak `WebSocket.queueSize()` (`recordWebSocketQueue`) |
| `inputTx=1` | `serverContent.inputTranscription` messages seen |
| `outputTx=0` | `serverContent.outputTranscription` messages seen |
| `turnComplete=false` | whether `serverContent.turnComplete` arrived |
| `hardDeadline=false` | settlement came from the 3 s deadline (vs the debounce) |
| `overflow=false` | pre-ready audio buffer never overflowed |
| `[reject=DEVANAGARI]` | first selector `RejectionRule` that blocked a candidate (only when selection failed) |
| `[lenient=true]` | a rejected candidate was still inserted by the lenient fallback (only when it was) |

All durations are **monotonic**; no transcript/audio/keys are ever logged.
`inputTx` / `outputTx` = input/output transcription message counts.

The example above: a warm session, ~3.9 s of audio, the server delivered one
input transcription ~7.9 s after the first frame, STOP settled via the debounce
(~553 ms) and the insertion round-trip took ~49 ms — but the candidate was
initially rejected (Devanagari in English mode) and rescued by the lenient
fallback.

---

### 16. Error taxonomy

Every typed failure (`DictationFailure`) in the codebase, with its exact
trigger, `recoverable`, `retryAllowed`, and user-visible `message`. Unless
noted, `retryAllowed = recoverable` was set explicitly.

| code | Trigger | recoverable | retryAllowed | User-visible message |
| --- | --- | --- | --- | --- |
| `gemini_setup` | `awaitReady` 15 s timeout; `setupError.error.message`; top-level `error.message`; `onClosing`/`onClosed` before ready; coordinator fallback when `awaitReady` throws a non-`GeminiLiveException` | true | true | session: the server/timing detail; coordinator fallback: `"Could not reach Gemini. Check your network and API key."` |
| `gemini_transport` | (a) `onFailure` — pong timeout, network drop (`t.message ?: "Connection failed"`); (b) `SessionEnd` in the coordinator → `"The Gemini session closed."`; (c) rejected activity boundary → `"Gemini rejected the activity boundary (<reason>)."` | true | (a)/(c) **true**, (b) **false** (default; the turn ended itself) | as above |
| `gemini_no_transcript` | settlement with no usable value at the hard deadline | true | true | `"No transcript could be recognized. Try again."` |
| `gemini_connection_too_slow` | `PreReadyAudioBuffer` overflow (500 frames ≈ 10 s while connecting) — overflow is never silently dropped; since 1.0.8 this is a last-resort backstop, not the primary connect timeout | true | true | `"The Gemini connection is too slow. Try again."` |
| `runtime_no_api_key` | `keyProvider.provideKey()` returned null/empty in `resolveSession` or `createColdSession` | true | false | `"Add your Gemini API key in Settings first."` |
| `runtime_mic_permission` | `RECORD_AUDIO` not granted at `startCapture` | true | false | `"Microphone permission was revoked."` |
| `runtime_no_accessibility` | `sendInsertion` returned false (`a11yReply == null`, i.e. accessibility process not registered) | true | true | `"Could not reach the accessibility service."` |
| `insert_ambiguous` | `InsertionResult.Ambiguous` (commit could not be confirmed) | true | false | `"Could not confirm the text was inserted. Use Copy to grab it."` |

Also present but not in the PRD taxonomy: `gemini_events` — the
`runSession` catch-all when the session stops unexpectedly
(`"The Gemini session stopped unexpectedly."`, `recoverable = true`,
`retryAllowed = false`). Insertion failures arriving via IPC
(`InsertionResult.Failed`) carry whatever code the accessibility process packed
(`RuntimeIpc.KEY_FAILURE_*`).

**Retryable-error persistence.** In `DictationCoordinator.fail`:
`if (failure.retryAllowed) return` — the `DictationState.Error` is left on
screen and persists until the user acts. Non-retryable failures auto-reset to
`Idle` after `returnToIdleMs` (1200 ms).

**Retry / Dismiss overlay actions** (`FlowRuntimeService.onOverlayIntent`):
- `RETRY` → `coordinator.retry()` — accepted only while `lastPublished is
  DictationState.Error`; it clears the errored holder (`resetToIdle`) and starts
  a fresh session; otherwise returns false.
- `DISMISS` → `coordinator.dismiss()` — clears the Error panel and returns to
  `Idle`.
- `CANCEL` → `coordinator.cancel()` — marks `Cancelled(USER)` and tears down.
- `COPY` → no-op (`Unit`).

---

### 17. State machines

#### 17.1 Transport machine (per session, `OkHttpGeminiLiveSession`)

```
                        startActivity (sends activityStart)
        onOpen:             |             endActivity (sends activityEnd)
         setup     setupComplete      +---------------+   close
   Connecting --------→ Ready -------→ ActivityStarted ------→ ActivityEnded
        +                |  |              |                     |
        |                |  +--------------+  duplicate: no wire  |
        +---- close ----+  sendAudio (frames)  message            |
                                                             close |
                                                              +----+----+
                                                              v         v
                                                               Closed
```

Rules: `startActivity` before `Ready` → `session_not_ready`; audio before
`startActivity` → `audio_before_activity_start`; audio after `endActivity` →
`audio_after_activity_end`; `endActivity` before start → `activity_not_started`;
duplicate start/end → `Accepted` with no second wire message; a failed
`WebSocket.send` forces `Closed`; `close()` is idempotent from any state.

#### 17.2 Dictation lifecycle (per overlay turn, `DictationState`)

```
            start() (tap)                    stop() / auto-stop
   Idle ───────────────→ Starting ─────────────→ Listening ──────────→ Finalizing
                          │                        │  │                   │
                          │                    cancel()                settle()
                          │                        │  │                   │
                          │  ┌─────────────────────┘  │   insertSettled    v
                          │  v                        v   ──────────────→ Inserting
                          │ Cancelled(reason=USER)    │                   │
                          │                          │        IPC result  v
                          │                          │        ┌─────→ Success
                          └──────────────────────────┘        └─────→ Error(insert_ambiguous / Failed)
                                                          (retryable Error persists
                                                           until Retry / Dismiss)
```

`DictationState` variants: `Unavailable`, `Idle`, `Starting(sessionId, target)`,
`Listening(sessionId, amplitude, elapsedMillis, connecting)`,
`Finalizing(sessionId)`, `Inserting(sessionId)`, `Success(sessionId)`,
`Cancelled(sessionId, reason)`, `CopyAvailable(sessionId, candidate)`,
`Error(sessionId, failure)`. `cancel()` publishes `Cancelled(USER)`, marks
`Stop`, and tears down; the overlay returns to `Idle` after `returnToIdleMs`.

**0.4.0 auto-stop** (`DictationCoordinator.runAutoStop`): while `Listening`, a
watcher ticks every `AUTO_STOP_CHECK_MS = 200` ms and stops on whichever fires
first — a **silence timeout** (`Config.autoStopSeconds`, reset to 0 whenever
`capture.amplitude.value >= speechAmplitudeThreshold = 0.02f`) or a **hard
recording cap** (`Config.maxRecordingSeconds`). Both end through the same
`stop()` path as a user STOP. The runtime feeds the settings-backed product
default (60 s) into both knobs (`FlowRuntimeService` config wiring); disabled
when the value is 0.

---

### 18. Module interaction (sequence)

ASCII sequence for one full dictation turn (warm claim shown; cold path differs
only in `resolveSession` + pre-ready buffering):

```
 Bubble tap
   │  OverlayIntent.START_DICTATION
   ▼
 FlowRuntimeService.onOverlayIntent ──► DictationCoordinator.start()        [metrics: Tap]
   │                                        │
   │                                        │ runSession(holder)
   │                                        ▼
   │                     DictationHost.resolveSession(metrics)              [KeyLoadStarted]
   │                        │  warmManager.claim() ?: 
   │                        │  GeminiSessionFactory.create(key, config)      [KeyLoaded, SettingsReady, SocketCreated]
   │                        │     └─ OkHttpGeminiLiveSession(newWebSocket) ─► HTTP 101 ─► onOpen ── sends {"setup":...}   [SocketOpen]
   │                        │     └─ events().collect ─► onLiveEvent(holder)
   │                        ▼
   │                     DictationHost.startCapture(metrics)                [CaptureStartRequested, CaptureStarted]
   │                        └─ AudioCapture (RECORD_AUDIO + FGS microphone)
   │                        ▼
   │                     publish(Listening(connecting = !ready))
   │
   │   awaitReadyAndStart: session.awaitReady() ──► ◄── {"setupComplete":{}}      [SetupComplete → Ready]
   │        └─ startActivity() ──► {"realtimeInput":{"activityStart":{}}}         [ActivityStartQueued]
   │        └─ ready.complete()
   │
   │   streamAudio (one ordered sender):
   │        └─ sendAudio(chunk) ──► {"realtimeInput":{"audio":{"data":"<base64>","mimeType":"audio/pcm;rate=16000"}}}
   │             (cold: PreReadyAudioBuffer 500 frames ≈ 10 s → drain in order → overflow = gemini_connection_too_slow)
   │                                                                            [FirstAudioQueued]
   │                                    ◄── binary frame (opcode 0x2) ── {"serverContent":{"inputTranscription":{"text":"<text>"},...}}
   │                                       parseServerMessage → ServerContent → GeminiEvent.TranscriptCandidates
   │                                       └─ TranscriptAccumulator.accept(raw) ── onTranscriptUpdate (reset settle debounce)
   │                                       {"serverContent":{...,"turnComplete":true}} → TurnComplete (retained)  [TurnComplete]
   │
   STOP / auto-stop ──► coordinator.stop() ──► Finalizing                     [Stop]
   │   startFinalization:
   │      deadlineJob (3 s hard deadline)            ──► TranscriptSettled at fire [TranscriptSettled]
   │      capture.requestStop → awaitQuiescence → audioJob.join                 [CaptureQuiesced, LastAudioQueued]
   │      endActivity() ──► {"realtimeInput":{"activityEnd":{}}}                [ActivityEndQueued]
   │      afterActivityEnd → settle debounce (250 ms) → settle()
   │          └─ accumulator.settledText → ResultCandidate → TranscriptSelector.select
   │               (None → lenientAccept → insert | failNoTranscript)
   │          └─ insertSettled → DictationHost.sendInsertion(sessionId, text)   [InsertionRequested → Inserting]
   │               └─ DictionaryCorrections.apply(text, cachedDictionary)
   │               └─ RuntimeIpc.MSG_INSERT (Messenger) ──► accessibility process ──► commitText(target)
   │          ◄── RuntimeIpc.MSG_INSERT_RESULT ── onInsertionResult(sessionId, result) [InsertionResult]
   │               └─ Success | Error(insert_ambiguous / failed) → Idle after returnToIdleMs
   │          teardown → session.close() ──► WebSocket close(1000, "session closed")
   │
   ▼
 DictationHost.onSessionFinished(state, metrics, transcript)
     └─ "SESSION DONE outcome=... <metrics.summary()>"
     └─ historyRepository.record(...)  (opt-in, settled transcript only)
```

Seams: `GeminiLiveSession` (contract — the session the coordinator drives),
`DictationHost` (Android-facing host interface: publish, resolveSession,
startCapture, sendInsertion, onSessionFinished), `AudioPipeline` (capture
abstraction so orchestration is host-testable), `TranscriptAccumulator` /
`TranscriptSelector` (pure settlement), `MutableSessionMetrics` (pure
diagnostics), `WarmLiveSessionManager` (warm pool), `RuntimeIpc` (typed
cross-process Messenger contract: `MSG_REGISTER_REPLY=1`, `MSG_ELIGIBILITY=2`,
`MSG_INSERT=3`, `MSG_INSERT_RESULT=4`, keys `session_id` / `insert_text`).

---

### 19. Key source files

| File | Role |
| --- | --- |
| `platform/gemini/GeminiLiveWire.kt` | Pure wire codec (build + parse) |
| `platform/gemini/GeminiSessionConfig.kt` | Per-session config (model, activity mode, instruction, language, defaults) |
| `platform/gemini/GeminiSessionFactory.kt` | Session + shared `OkHttpClient` + endpoint URL (default client, default model) |
| `platform/gemini/OkHttpGeminiLiveSession.kt` | OkHttp WebSocket session + transport state machine |
| `platform/gemini/WarmLiveSessionManager.kt` | Warm prewarm pool (backoff + idle) |
| `platform/runtime/DictationCoordinator.kt` | Pure orchestration + settlement + failsafes + auto-stop + failures |
| `platform/runtime/FlowRuntimeService.kt` | Android host (FGS, IPC, key/settings, capture, logging) |
| `audio/AudioCapture.kt`, `audio/AudioPipeline.kt`, `audio/Chunker.kt` | Capture + orderly shutdown |
| `audio/PreReadyAudioBuffer.kt` | Bounded pre-ready buffer (cold connect) |
| `core/transcript/TranscriptAccumulator.kt` | Cumulative transcript merging |
| `core/transcript/TranscriptSelector.kt` | User-speech trust validation + `diagnose()` |
| `core/transcript/TranscriptCompleteness.kt` | Echo/raw content-ratio completeness gate |
| `core/model/MutableSessionMetrics.kt` | Monotonic diagnostics + `summary()` |
| `core/model/LanguageMode.kt`, `core/model/TranscriptionStyle.kt` | Mode -> systemInstruction mapping |
| `core/model/DictationState.kt`, `core/model/DictationFailure.kt`, `core/model/SendResult.kt` | Typed state/failure contracts |
| `core/audio/GemAudioFormat.kt`, `core/model/AudioChunk.kt` | Audio framing (640 B / 20 ms @ 16 kHz) |
| `platform/ipc/RuntimeIpc.kt` | Typed cross-process message contract |
