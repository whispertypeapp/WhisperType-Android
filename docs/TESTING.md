# WhisperType Testing

## Overview

Testing has two automated tiers plus a mandatory manual tier:

1. **JVM unit tests** (`app/src/test`) — 424 tests, fast and device-free; run with `:app:testDebugUnitTest`.
2. **Lint** — `:app:lintDebug`, treated as warnings-as-errors.
3. **Manual device acceptance** — required for accessibility/overlay/insertion behavior; see the [on-device protocol](#on-device-test-protocol) below.

There are **no instrumented tests**. `app/src/androidTest` contains only a manifest; all automated coverage is JVM unit tests. Physical-device validation is therefore a manual process, never a CI artifact.

## Unit test inventory by package

| Package | Coverage |
| --- | --- |
| `com.whispertype.android.platform.runtime` | `DictationCoordinatorTest` — virtual-time orchestration (duplicate START, cancel during setup, STOP immediately, stale insertion responses, capture failure, rejected boundaries, exactly-once insertion), settlement (250 ms quiet window, 2.5 s tail backstop, provisional rejection, retained early turn-complete), pre-ready buffering (order drain, overflow → `connection_too_slow`), failsafes (lenient fallback insert, retry, persistent errors, fragment guard), auto-stop (silence threshold + hard cap), Hinglish verbatim insert. |
| `com.whispertype.android.platform.gemini` | `GeminiLiveWireTest` (exact wire codec: setup fields incl. `mode`/`languageCodes`/`omitGenerationConfig`, realtime activity builders, interim + final transcription parse), `OkHttpGeminiLiveSessionTest` (MockWebServer WebSocket: setup-first ordering, single activity start/end, audio rejection before start/after end, no `clientContent`, interim/final/echo candidates, automatic-VAD variant, setup errors, close idempotence), `WarmLiveSessionManagerTest` (prewarm/claim/backoff/idle), `ModelPolicyTest` (only `gemini-3.5-transcribe-live` may appear; no non-Live endpoints; audio only to Gemini Live). |
| `com.whispertype.android.core.transcript` | `TranscriptAccumulatorTest` (cumulative merge rules), `TranscriptCompletenessTest`, `TranscriptSelectorTest` (user-speech trust policy, `diagnose()`, long-sentence regression). |
| `com.whispertype.android.core.model` | `MutableSessionMetricsTest` (monotonic timing/counters/summary). |
| `com.whispertype.android.audio` | `AudioCaptureOrderlyShutdownTest` (producer-owned flush, zero-padded partial frame, unblocking a blocking read, timeout fallback), `PreReadyAudioBufferTest` (bounded FIFO, overflow, close). |
| `com.whispertype.android.core.state` | `DictationReducerTest` — state transitions, stale-session rejection, exactly-once consumption. |
| `com.whispertype.android.core.privacy` | `LogRedactorTest`. |
| `com.whispertype.android.data.secrets` | `SecretCipherTest`, `ClientInvalidRecoveryTest` — Keystore/AES-GCM storage, corrupt-blob recovery. |
| `com.whispertype.android.data.settings` | `SettingsRepositoryTest` — defaults and persistence. |
| `com.whispertype.android.core.dictionary` | `DictionaryCorrectionsTest` — word-boundary, case-insensitive correction rules applied at insertion. |
| `com.whispertype.android.core.overlay` | `BubblePlacementTest` — free-drag bounds, persisted bubble position, reset. |
| `com.whispertype.android.data.history` | `EncryptedHistoryRepositoryTest` — opt-in, Keystore AES-GCM, retention-days pruning, delete / delete-all, view/copy; `HistoryStatsTest`. |
| `com.whispertype.android.platform.overlay` | `OverlayHostStateMachineTest`, `OverlayPlacementTest`, `OverlayVisibilityTest`. |
| `com.whispertype.android.platform.accessibility` | `FocusedEditorTest`, `EligibilityMapperTest`, `EligibilityExplanationTest`, `SecurityClassifierTest`, `InsertionDecisionTest`, `InsertionVerifierTest`. |

All unit tests run on the JVM with no device and no live API key.

## Fake WebSocket contract tests

`OkHttpGeminiLiveSessionTest` drives `OkHttpGeminiLiveSession` against a local MockWebServer WebSocket that mirrors the Live endpoint's **binary-frame** delivery. It asserts: setup is the first client message with the exact fields (TEXT modality, `inputAudioTranscription` with mode + language hint, manual activity config, no output transcription, no `clientContent`), exactly one `activityStart` / `activityEnd`, ordered audio with `audio/pcm;rate=16000`, audio rejection before start and after end, prompt failure propagation, and that interim + final transcription emit INPUT candidates while `outputTranscription` / `modelTurn` text never emit a user candidate. No live network or API key is used. See `docs/GEMINI_LIVE.md` for the wire protocol.

## Device matrix

WhisperType's core behavior — the dock overlay, keyboard geometry, and text insertion — depends on the device, keyboard, and navigation mode in ways emulators cannot reproduce. This matrix tracks every supported phone/keyboard/navigation combination and its manual acceptance result. Each row must pass the device acceptance sequence below before a release.

### Platform baseline

0.4.x targets **Android 13+** (minSdk 33) and adds a runtime permission flow:

- `RECORD_AUDIO` and `POST_NOTIFICATIONS` are requested at runtime during first-run setup, in order (microphone, then notifications).
- Both must be granted before dictation can start.
- Android 13+ tablets are in scope; the tablet acceptance tier is not yet validated.

### ADB baseline commands

Before testing each device, capture the baseline. Replace `<serial>` with the connected device serial:

```powershell
adb -s <serial> shell getprop ro.product.manufacturer
adb -s <serial> shell getprop ro.product.model
adb -s <serial> shell getprop ro.build.version.release
adb -s <serial> shell getprop ro.build.version.sdk
adb -s <serial> shell wm size
adb -s <serial> shell wm density
adb -s <serial> shell settings get secure default_input_method
```

Record the results in the table below. **Never commit device serials or other unique identifiers**; the table records only model, Android version, keyboard, and navigation mode.

### Compatibility table

| Phone model | Android version | Keyboard | Navigation mode | Overlay result | Insertion result | Known issues | Last tested date |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Samsung Galaxy (Android 16, SDK 36) | 16 (SDK 36) | Samsung Keyboard | Portrait | Pass | Pass |  | 2026-08-06 |
| Pixel | — | Gboard | Portrait | Pending | Pending |  | — |
| Pixel | — | Gboard | Landscape | Pending | Pending |  | — |
| Pixel | — | SwiftKey | Portrait | Pending | Pending |  | — |
| Samsung Galaxy | — | Samsung Keyboard | Portrait | Pending | Pending |  | — |
| Samsung Galaxy | — | Gboard | Portrait | Pending | Pending |  | — |
| Samsung Galaxy | — | SwiftKey | Portrait | Pending | Pending |  | — |
| Android 13+ tablet (e.g. Pixel tablet) | 13+ | Gboard or Samsung Keyboard | Portrait | Pending | Pending | runtime RECORD_AUDIO + POST_NOTIFICATIONS flow | — |
| Android 13+ tablet (e.g. Pixel tablet) | 13+ | Gboard or Samsung Keyboard | Landscape | Pending | Pending | runtime RECORD_AUDIO + POST_NOTIFICATIONS flow | — |
| Samsung Galaxy (DeX) | 13+ | Hardware keyboard / DeX on-screen keyboard | DeX (HDMI or DeX for PC) | Pending | Pending | overlay must render on the DeX display, not the phone screen; validate the dedicated DeX acceptance steps below | — |

Add additional rows (e.g. three-button navigation, other keyboards, additional OEMs, DeX variants) as they are validated.

### How to record results

- Fill `Android version` and `Keyboard` with the exact values from the baseline commands (include the keyboard version where the OEM exposes it).
- `Overlay result` / `Insertion result`: `Pass` or `Fail` after the device acceptance sequence; leave `Pending` until tested.
- `Known issues`: describe any failures or workarounds, referencing the acceptance step that failed (e.g. "step 12 fails: dock flickers on rotation with Samsung Keyboard").
- `Last tested date`: use `YYYY-MM-DD`; keep it `—` until the first full run.
- Update the row for the build version tested; record which build passed (see the last step of the device acceptance sequence below).

## On-device test protocol

Run the stages in order against a real device. Each stage has a trigger and a pass/fail rule; diagnose a failure **at that stage** — do not proceed past a failed stage until it is resolved.

Watch all relevant logs live during the whole test:

```bash
adb -s <SERIAL> logcat -c
adb -s <SERIAL> logcat | grep -E "SESSION DONE|serverContent|STAGE|OkHttpGeminiLiveSession|FlowRuntimeService|WhisperTypeAccessibility"
```

The runtime logs a single aggregate line per finished session:

```
SESSION DONE outcome=<Success|Error|Cancelled> tapToCapture=…ms setup=…ms
tapToFirstAudio=…ms firstAudioToFirstTranscript=…ms stopToQuiesce=…ms
stopToActivityEnd=…ms stopToSettled=…ms stopToInsert=…ms insertToResult=…ms
captured=… accepted=… rejected=… maxQueue=… inputTx=… outputTx=…
turnComplete=… hardDeadline=… overflow=… [reject=<rule>] [lenient=true]
```

(see `docs/GEMINI_LIVE.md` for the meaning of each field).

### Preconditions (automated, no interaction)

| Check | Command | Pass |
| --- | --- | --- |
| Device connected | `adb devices` | `device` (not `offline`) |
| App version | `adb shell dumpsys package com.whispertype.android \| grep version` | matches committed `versionName`/`versionCode` |
| Mic permission | `adb shell dumpsys package com.whispertype.android \| grep RECORD_AUDIO` | `granted=true` |
| Notifications | same, `POST_NOTIFICATIONS` | `granted=true` |
| FGS special-use | same, `FOREGROUND_SERVICE_SPECIAL_USE` | `granted=true` |
| Accessibility enabled | `adb shell settings get secure enabled_accessibility_services` | contains `WhisperTypeAccessibilityService` |
| App starts w/o crash | `adb shell am start -n com.whispertype.android/.MainActivity`; then `logcat -d \| grep FATAL` | no `FATAL EXCEPTION` |
| Both processes | `adb shell pidof com.whispertype.android` and `...:accessibility` | two PIDs |

If a precondition fails: reinstall (`install -r`), grant permissions, re-enable the accessibility service, then re-run the preconditions.

### Stages

- **Bubble appears over a focused field.** Focus a text field so the keyboard is visible; the mic bubble appears. Pass: `STAGE: bubble shown (eligible target + keyboard)`. Fail: the log shows `Bubble hidden; reasons=[...]` — fix the named reason (e.g. `no_editor_focus`, `uncertain_field`, `keyboard_hidden`, `secure_field`) and retry.
- **Session connects and Gemini acknowledges setup.** Tap the bubble. Pass: `onOpen code=101` and `setupSent=true`, followed by `SESSION DONE ... setup=NNNms ...`; setup failure instead shows `outcome=Error` with `reject=` absent. A `setup=` above ~15 s implies the 15 s ready timeout.
- **Microphone produces data.** While the panel shows Listening, speak. Pass: `SESSION DONE ... captured=N accepted=N>0 ...` (audio frames reached the session). Fail (`accepted=0`): the capture loop never sent a frame — check for an `outcome=Error` with `runtime_mic_permission` or a capture-read failure.
- **The captured audio is real sound (not silence).** Speak loudly for 4–5 seconds, tap Stop. Pass: the text appears in the field. Fail (`outcome=Error`, `inputTx=0`, `reject=` absent): either the mic captured silence or Live ASR did not fire — retry the dictation to tell them apart (check `captured=`/`accepted=` for frames).
- **Server returns a transcript.** Pass: `SESSION DONE ... inputTx>=1 ... outcome=Success`. Fail (`inputTx=0`): Live-model intermittency, or a `reject=<rule>` means the transcript arrived but was rejected (see `docs/GEMINI_LIVE.md`).
- **Candidate selection.** Pass: `outcome=Success` with the spoken text inserted. Fail: `outcome=Error` + `reject=<rule>`; `lenient=true` means the failsafe inserted it anyway. Remaining rejections should be blank / punctuation-only / garbled / Devanagari-in-English.
- **Insertion at the cursor.** After a successful session, the spoken text is in the focused field. Fail: `outcome=Error` with `insert_ambiguous`, or a `runtime_no_accessibility` / `runtime_ipc_failed`-style failure → accessibility insertion path.
- **Freely draggable mic bubble.** Drag the bubble to a new position, end the session, refocus. Pass: the bubble reappears at the dragged position and persists across sessions and restarts; tap still starts a session from the new position.
- **Capsule recording UI + animated circular waveform.** Pass: the keyboard area is replaced by the capsule panel with a live animated waveform; Stop and Cancel are present; the waveform stills after Stop.
- **Auto-stop (silence + hard cap).** Set `Auto-stop timeout`, dictate, stop speaking, and do not touch the panel. Pass: for each option (15/30/60/120/300 s, default 60 s) the session stops automatically after the silence interval, and a hard cap stops any session at the cap even while audio flows.
- **Recording source / bluetooth headset mic.** With `Recording source` left at `Phone microphone` and a bluetooth headset connected, dictate: Pass: audio still comes from the phone mic (`captured=N accepted=N>0`, no failure). Then set `Recording source` to `Bluetooth headset` with the headset connected and dictate: Pass: the Settings row shows the connected device name, audio is captured from the headset (speak into the headset mic; the waveform responds and the transcript matches). Unplug the headset and dictate again: Pass: dictation still works using the phone mic (graceful fallback, no error).
- **Physical-keyboard hotkey.** Connect a physical (hardware) keyboard. With the default hotkey (grave/backtick) and no soft IME showing, focus a safe text field and press the hotkey: Pass: dictation starts (session `Starting`/`Listening`). Speak, press again: Pass: the turn finalizes and inserts (same path as tapping Done). Press the hotkey outside any editor: Pass: nothing starts. Select `Off` in `Settings → Recording → Keyboard shortcut`: Pass: the key no longer toggles dictation and types normally. Switch the shortcut to `F9`: Pass: F9 toggles and the grave key types normally. In a password field: Pass: the hotkey does not start dictation.
- **Smart-mode shaping.** Dictate a phrase with filler words and a mid-sentence self-correction. Pass: the inserted transcript has the fillers removed and the correction resolved (server-side `smart` mode; no polish setting exists anymore).
- **Interim partials stream live.** During a long dictation, Pass: the recording pill's transcript area shows the partial text revising while you speak, and the final insert matches the last committed segment.
- **Custom dictionary corrections.** Add a word plus an optional `Always write as` correction in the Custom dictionary page; dictate the uncorrected form. Pass: the inserted text uses the corrected spelling (applied at insertion, word-boundary, case-insensitive); the live transcript is not rewritten mid-session.
- **History list / copy / delete / delete-all.** Enable `Local history`, complete a dictation, open the History tab. Pass: the transcript appears; Copy copies the text; Delete removes one entry; `Clear all history` empties the list; entries respect the retention period.
- **Mini-dot auto-minimize.** With the mini-dot option enabled and an idle bubble, Pass: the bubble shrinks to a small dot after the configured delay and the dot still starts dictation.
- **Kill switch / app-enabled.** Disable the app-enabled setting. Pass: the runtime fully stops — the "WhisperType is listening" foreground notification disappears, the system "displaying over other apps" notification disappears, the bubble/overlay is gone, and a live dictation is aborted. Re-enabling restores the bubble, notifications, and dictation.
- **Kill switch / no resurrection.** With the app disabled, force-stop the app and reopen it (or restart the Accessibility Service). Pass: the runtime stays off while `App enabled` is OFF; only turning it back on restarts the runtime.
- **Dark mode.** Toggle dark mode: Pass: app screens render in the dark theme and the overlay remains legible.

### Decision table

| Mic data | Real sound | Transcript | Selection | Diagnosis |
| --- | --- | --- | --- | --- |
| FAIL (`accepted=0`) | — | — | — | `AudioCapture` never ran (capture path) |
| PASS | FAIL (no text) | FAIL (`inputTx=0`) | — | Mic captures silence, or Live ASR silent this session (retry) |
| PASS | PASS | PASS | `reject=<rule>` | Transcript arrived but selector rejected it |
| PASS | PASS | PASS | `outcome=Success` | Full pipeline works; continue the acceptance matrix |

### Device acceptance sequence

Run this sequence on every supported phone/keyboard combination before declaring it compatible:

1. Install or upgrade the current debug/release candidate.
2. Open WhisperType and verify permissions and Accessibility status.
3. Confirm the intended third-party keyboard is still the default IME.
4. Focus a normal single-line text field and verify the dock appears.
5. Start dictation and verify the keyboard-covering voice panel.
6. Speak an English test phrase and verify exactly one insertion.
7. Speak a Hinglish test phrase and verify the code-mixed output is usable (romanized Latin expected from the model's native code-mixing).
8. Replace a selected text range.
9. Cancel a dictation and verify nothing is inserted.
10. Change focus during finalization and verify stale text is not inserted.
11. Test a password/PIN field and verify the dock never appears.
12. Rotate during idle and recording states.
13. Switch apps during recording and verify safe cancellation or the documented behavior.
14. Lock and unlock the phone during recording.
15. Test gesture navigation and three-button navigation where available.
16. Test notification and microphone permission denial/recovery.
17. Test invalid API key and temporary network loss.
18. Run 20 consecutive start/stop dictations and check for service, audio, overlay, or insertion leaks.
19. Reboot the phone and confirm the documented post-reboot behavior.
20. Record pass/fail, build version, device model, Android version, keyboard version, and notes in the compatibility table above.

### Samsung DeX acceptance

Run in DeX mode (HDMI/DeX station and, where available, DeX for PC) with a text
field focused on the DeX display. Pass for each:

- **Bubble on the DeX display.** Focus a text field in DeX: the bubble renders
  on the DeX display, not on the phone screen. With no editor focused it is
  hidden as usual.
- **Hardware-keyboard eligibility.** With a physical keyboard (no DeX
  on-screen IME), a safe focused editor still shows the bubble.
- **Recording pill on the DeX display.** Tap the bubble; the recording pill
  (and Done button) appears on the DeX display and insertion lands in the
  focused field.
- **Follow focus across displays.** Focus a field on the phone screen while
  still in DeX: the bubble moves to the phone display; focus a DeX field again
  and it moves back, clamped on-screen each time.
- **Drag and drop-to-dismiss** work on the DeX display.
- **DeX disconnect.** Disconnect DeX: the overlay returns to the phone display
  with no crash and no lingering window.

### Acceptance matrix

| Scenario | Runs | Expected |
| --- | --- | ---: |
| Short English, 1–3 words | 10 | ≥9 insert |
| Short opener ("Okay so…", "Yes…", "Of course…") | 10 | ≥9 insert |
| Normal English sentence | 10 | ≥9 insert |
| Long English sentence | 10 | ≥9 insert |
| Short Latin-script Hinglish | 10 | ≥9 insert, Latin script |
| Normal Latin-script Hinglish | 10 | ≥9 insert, Latin script |
| Speak immediately after tap | 10 | captured>0, insert |
| STOP immediately after speech | 10 | insert or clean retryable error |
| Cancel during connection | 5 | `outcome=Cancelled`, no insert |
| Rapid consecutive sessions | 10 | no stale-candidate errors |
| Retry button on an error | 5 | a fresh session starts |
| Bubble dragged position persists | 5 | bubble stays at the new position after refocus and restart |
| Tap starts from a dragged position | 5 | session starts (`SESSION DONE`) |
| Auto-stop silence (each 15/30/60/120/300 s option) | 5 | automatic `outcome=Success` at the interval |
| Auto-stop hard cap | 5 | session stops at the cap even while speaking |
| Smart-mode polish (filler removal + self-correction) | 5 | inserted text is cleaned, not verbatim |
| Hinglish code-mixing (0.10.0) | 10 | ≥9 insert, usable romanized output |
| Dictionary correction applied at insertion | 5 | corrected spelling in the inserted text |
| History list / copy / delete / delete-all | 5 | all operations behave |
| Mini-dot auto-minimize + dot tap | 5 | bubble shrinks to dot; dot starts dictation |
| Kill switch stop / restore | 5 | off = runtime fully stopped (no FGS/overlay notifications, no bubble, dictation aborted); on = fully restored |
| Kill switch no-resurrection | 5 | force-stop while disabled; runtime stays off until re-enabled |
| Dark mode rendering | 5 | screens and overlay legible in dark theme |
| Android 13 tablet first install | 3 | installs; both runtime permissions granted |
| DeX bubble + pill on secondary display | 3 | bubble and recording pill render on the DeX display |
| DeX hardware-keyboard eligibility | 3 | safe focused editor shows the bubble with no IME |
| DeX follow-focus / disconnect | 3 | bubble moves displays with focus; clean return on disconnect |

## Physical-device requirement

Physical-device validation is mandatory for Accessibility Service behavior, third-party keyboard geometry, overlay layering, microphone foreground-service startup, text insertion, OEM behavior, and release installation. Emulators are insufficient for these paths, and a green CI run on JVM unit tests alone is never sufficient evidence of compatibility.

## 0.10.0 transcribe-live device gates

The model switch cannot be fully verified on the JVM (no live key in tests).
A **live wire probe (2026-08-27)** against the real endpoint — the app's exact
setup: TEXT modality, `smart` mode, `languageCodes: ["en-US"]`, manual
activity signaling — already confirmed: setup accepted; 10 interims streamed;
one final segment committed after `activityEnd`; `generationComplete` fired;
text arrived smart-formatted. Automatic VAD (no manual boundaries) produced no
transcripts. Remaining on-device (S25) checks before claiming 0.10.0 verified:

- **Full-app dictation.** Run the acceptance matrix with the real mic: interims
  revise live while speaking; the insert equals the last committed segment.
- **Final flush after `activityEnd`.** Release Done mid-speech: the final
  `inputTranscription` must arrive promptly (settlement ~250 ms quiet +
  backstop). The probe confirms `activityEnd` flushes; confirm in-app timing.
- **`languageCodes` hint.** Probe confirms `["en-US"]` is accepted with no
  setup error; verify the `hi-IN` Hinglish path and code-mixed output.
- **Session cap.** A warm or long session may hit the preview's ~10-minute
  continuous-session limit; verify the `goAway`-based teardown and that a fresh
  session starts cleanly.
- **Smart-mode quality.** Filler removal, self-corrections, and formatting on a
  noisy dictation; Hinglish code-mixing acceptable.
- **Preview rate limits.** Public preview; confirm the owner's key quota holds
  for the acceptance matrix runs.

## Release checklist

Before each release:

- All unit tests pass (`:app:testDebugUnitTest`).
- Lint passes with no warnings (`:app:lintDebug`).
- Manual matrix passes on at least one physical device.
- Remaining device matrix rows are `Pending` or `Pass`, never silently ignored.
- No secrets scan matches.
- No forbidden logging matches.
- Accessibility disclosure is current.
- Version name and version code are updated.
- Changelog entry exists.
- Release APK is signed.
- SHA-256 checksum is recorded.
- Installation and upgrade are tested.
- User setup guide matches the current UI.
- Known issues are documented.

## Running the tests

```powershell
./gradlew :app:testDebugUnitTest :app:lintDebug
```

- `:app:testDebugUnitTest` (JVM unit tests) and `:app:lintDebug` require no device.
- There is no `connectedDebugAndroidTest` step: there are no instrumented tests. On-device validation is performed manually against the protocol above.
