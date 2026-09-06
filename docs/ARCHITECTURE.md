# WhisperType Android — Architecture

> **MODEL POLICY (non-negotiable):** the app may call **exactly one** Gemini
> model — the Gemini Live transcription model `gemini-3.5-transcribe-live` over
> `BidiGenerateContent`, pinned as `GeminiSessionFactory.LIVE_MODEL`. No
> `generateContent`, no Flash/Pro/TTS variants, no non-live transcribe endpoint.
> Audio goes only to Gemini Live; text shaping runs server-side in the model's
> `smart` mode. Enforced by `ModelPolicyTest`.

This document describes the current runtime architecture of WhisperType Android
(`app/src/main/java/com/whispertype/android`): the two processes, the module map,
and where to go for the voice engine and the 0.4.0 evolution.

## Two processes

WhisperType splits screen work from session work across two processes.

### Main process — `FlowRuntimeService` (overlay + orchestration + Gemini)

`platform/runtime/FlowRuntimeService.kt` is a foreground service running in the
main process. It owns the **overlay runtime**: the persistent overlay window, the
live session state, and the Gemini Live connection. It implements the stable
Compose owners (`OverlayOwners`) so the overlay can host a real Compose
composition without an activity, and it acts as the Android-facing
`DictationHost` for the pure orchestration coordinator (key/settings resolution,
microphone + foreground capture, insertion IPC, overlay state publication). It
also owns the warm-session pool and the opt-in encrypted history repository.

The service starts with a `specialUse` foreground type so the overlay can run
before `RECORD_AUDIO` is granted; during dictation it is promoted to
`specialUse | microphone`.

### `:accessibility` process — focus / target / insertion

`platform/accessibility/WhisperTypeAccessibilityService.kt` runs in its own
process (`android:process=":accessibility"`). It sees the screen only and owns
**focus, target capture, and insertion** — never the overlay, microphone, or
Gemini. The `EditorTracker` maintains eligibility (focused editor, keyboard
visibility, secure-field exclusion) and records which display hosts the focused
editor (`displayId`); the `AccessibilityTargetGateway` snapshots a typed
`TargetSnapshot` and performs cursor-aware `commitText()` insertion via an
`InputMethod` surface returned by `onCreateInputMethod()`. On a secondary
display (Samsung DeX) the soft-keyboard eligibility gate is relaxed, matching
the physical-keyboard hotkey relaxation.

### Typed Messenger IPC

The two processes communicate over a bound `Messenger` (`platform/ipc/RuntimeIpc.kt`).
The accessibility process registers its reply messenger and pushes eligibility;
the runtime replies with insert requests and receives typed insertion results.
Every payload is packed/unpacked through `RuntimeIpc` helpers, so the wire format
is a single source of truth. No editor content is ever transported over IPC.

## Module map

All paths are under `app/src/main/java/com/whispertype/android/`.

| Module | Files | Responsibility |
| --- | --- | --- |
| `platform/runtime/` | `DictationCoordinator.kt`, `FlowRuntimeService.kt` | Pure, host-testable session orchestration (state machine, auto-stop, retry, metrics) and the foreground host that binds it to Android. |
| `platform/gemini/` | `GeminiLiveWire.kt`, `OkHttpGeminiLiveSession.kt`, `GeminiSessionFactory.kt`, `GeminiSessionConfig.kt`, `WarmLiveSessionManager.kt`, `GeminiLog.kt` | The Gemini Live engine. `GeminiLiveWire` is the pure wire codec (JSON build/parse); `OkHttpGeminiLiveSession` is the WebSocket transport; the factory builds sessions; the warm manager prewarm pool. |
| `platform/overlay/` | `PersistentOverlayHost.kt`, `WhisperTypeOverlayContent.kt`, `OverlayAppearance.kt`, `OverlayVisibility.kt`, `OverlayHostStateMachine.kt`, `OverlayOwners.kt`, `OverlayComposeContainer.kt` | The one persistent `TYPE_APPLICATION_OVERLAY` window and its Compose content: the draggable mic bubble (the app logo, round) that auto-minimizes to a mini-dot, the recording pill (`[Cancel ✕][wave][Done ✓]`) anchored so Done lands where the bubble was tapped, status capsules re-centered on the bubble, and a pure attach/detach state machine. The window follows the display hosting the focused editor (via a `createWindowContext` WindowManager), so it renders on a Samsung DeX secondary display rather than the phone screen. |
| `platform/accessibility/` | `WhisperTypeAccessibilityService.kt`, `EditorTracker.kt`, `SecurityClassifier.kt`, `EligibilityMapper.kt`, `EligibilityExplanation.kt`, `AccessibilityTargetGateway.kt`, `InsertionDecision.kt`, `InsertionVerifier.kt` | The `:accessibility` process: focus/keyboard tracking, secure-field classification, typed eligibility, target capture, and validated insertion. |
| `platform/ipc/` | `RuntimeIpc.kt` | Typed cross-process message contract (see above). |
| `core/` | `core/state/`, `core/model/`, `core/transcript/`, `core/audio/`, `core/privacy/`, `core/dictionary/`, `core/overlay/`, `core/contracts/` | Pure, framework-free logic: the dictation reducer, typed domain models, the transcript accumulator/completeness/selector, PCM16 audio framing, log redaction, dictionary correction rules, and the contracts (`DictationBridge`, `GeminiLiveSession`, `TargetGateway`, `OverlayController`) that adapters implement. |
| `data/` | `data/settings/SettingsRepository.kt`, `data/secrets/`, `data/history/EncryptedHistoryRepository.kt` | DataStore-backed settings (language, auto-stop, dictionary, bubble position, history), Keystore+AES-GCM secrets, and the encrypted, viewable history store. |
| `audio/` | `audio/AudioCapture.kt`, `audio/AudioPipeline.kt`, `audio/BoundedAudioQueue.kt`, `audio/Chunker.kt`, `audio/PreReadyAudioBuffer.kt` | Device microphone capture and the bounded realtime pipeline feeding the Gemini session. |
| `ui/` | `ui/theme/`, `ui/settings/SettingsScreen.kt`, `ui/history/HistoryScreen.kt`, `ui/dictionary/DictionaryScreen.kt`, `ui/waveform/RealTimeWaveform.kt` | Compose theme (emerald-teal, light + dark), the Settings screen, the History screen (list + history settings), the Dictionary screen, and the real-time waveform used by the recording pill. |

## How a session flows

1. `EditorTracker` marks the focused field eligible; eligibility is pushed over
   IPC to the runtime, which shows the draggable mic bubble.
2. On bubble tap, `DictationCoordinator.start()` begins a session. `FlowRuntimeService`
   resolves a session (a warm preconnected one from `WarmLiveSessionManager`, else
   a cold session built by `GeminiSessionFactory` from the Keystore API key), promotes
   the service to microphone foreground type, and starts `AudioCapture`.
3. PCM16 16 kHz audio streams to Gemini Live over the shared OkHttp WebSocket;
   the coordinator applies the auto-stop watcher (silence + hard cap, whichever
   fires first) and publishes overlay state.
4. On stop/finalize, the coordinator settles the transcript from the session
   accumulator: the transcribe model's committed final segments
   (`inputTranscription`) and revisable partials (`interimInputTranscription`)
   are the only dictation source (no echo channel), a 250 ms
   quiet window plus one 2.5 s tail backstop end settlement, and a fragment
   guard refuses a truncated transcript. Text shaping runs server-side in the
   model's `smart` mode. It then applies `DictionaryCorrections` and sends an
   insert request over IPC. The accessibility process re-validates the target
   and commits text at the cursor; a typed result flows back. When history is
   enabled, the settled transcript is recorded encrypted.
5. Failures surface as typed `DictationFailure`s with a Retry/Dismiss panel; a
   failed insertion offers an explicit Copy fallback.

## Further reading

- **`docs/GEMINI_LIVE.md`** — the Gemini Live voice engine: wire protocol, prompt,
  session/settlement state machines, the reliability findings, and the
  exact wire mechanics reference.
- **`docs/TESTING.md`** — unit-test inventory, device matrix, and the on-device
  acceptance protocol.
