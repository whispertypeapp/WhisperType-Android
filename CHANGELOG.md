# Changelog

All notable changes to WhisperType Android are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.3] - 2026-09-08

### Changed

- **Default transcription mode (`settings`, `gemini`)** — changed the default transcription shaping mode to **Smart** (`TranscriptionMode.SMART`), enabling server-side disfluency cleanup, self-correction handling, and polished formatting out of the box while allowing users to select Verbatim anytime in Settings.

## [1.2.2] - 2026-09-08

### Added

- **Transcription mode setting (`settings`, `runtime`, `gemini`)** — added a new setting under Dictation allowing users to choose between **Verbatim** and **Smart** transcription modes for Gemini Live (`gemini-3.5-transcribe-live`), defaulting to **Verbatim**. Includes clear in-app explanations for both modes: Verbatim captures speech literally with filler words and repetitions preserved, while Smart performs server-side shaping (disfluency removal, self-corrections, and structured punctuation/formatting). Session configuration and warm pool profiles now propagate the selected mode dynamically.
- **GitHub release update checker (`updates`, `ui`, `notifications`)** — added automated checking against GitHub releases API (`api.github.com/repos/chaosmanage/WhisperType-Android/releases/latest`). Includes:
  - Background update check on app startup with semantic version comparison (`vX.Y.Z`).
  - System notification when a newer release is published, deep-linking directly to the release APK download URL to auto-trigger download in the browser.
  - Home screen update banner with release version and one-tap "Download" action.
  - "Check for updates" row in General settings with live check status, showing "WhisperType is up to date" or launching direct APK download if a new build is found.

## [1.2.1] - 2026-09-07

### Changed

- **Default speech mode (`settings`)** — default out-of-the-box speech mode is now English (`LanguageMode.ENGLISH`), with fallback for unknown or corrupted stored values defaulting to English.
- **Smaller bubble sizing (`overlay`, `settings`)** — allowed the floating mic bubble to scale down to 12 dp (expanded range from 24–72 dp to 12–72 dp). Removed the hardcoded 48 dp floor in the overlay layout and positioning calculation, enabling true compact bubble sizes on screen. The settings slider now indicates the exact dp dimension alongside the size label (e.g. "Tiny · 12 dp").

## [1.2.0] - 2026-09-07

### Added

- **Home dashboard redesign (`ui`)** — new visual hierarchy featuring a Weekly Hero Card with 7 daily rounded vertical bars aligned to a common baseline, an adaptive 2-column or stacked 1-column At-a-Glance metric grid (Words / min, Words / session), a compact content-height Recent dictation card with direct history link, and an action-oriented setup banner.
- **Custom dictionary rule editor (`dictionary`)** — full modal bottom sheet for creating and editing rules with live replacement preview, outer-whitespace trimming while preserving internal spaces and casing, multiword support, and case-insensitive identical text detection with explicit confirmation.
- **Dictionary entry update & legacy safety (`core`)** — explicit `updateDictionaryEntry` API with deduplication, and safe no-op handling for legacy blank replacement entries so matched text is never accidentally deleted.
- **Stateful foreground notification (`runtime`)** — state-specific copy for idle ("WhisperType bubble is ready"), recording ("Recording dictation" with active Stop and Cancel actions wired to `DictationCoordinator`), and finishing ("Finishing dictation"). Prompt FGS type demotion back to `specialUse` as soon as microphone capture ends.
- **Non-blocking notifications (`platform`)** — notification permission is optional: declining it never blocks onboarding, does not trigger a Home banner, and does not fail system diagnostics. Added a direct Settings row opening Android notification settings with Task Manager explanatory copy.

## [1.1.10] - 2026-09-06

### Fixed

- **Recording pill (`overlay`)** — silence always shows a solid center line;
  speaking draws full-height bars (mic RMS no longer scales bar height to
  invisible dots).

## [1.1.9] - 2026-09-06

### Fixed

- **Dictation (`runtime`)** — extend the post-stop transcription tail to 6 s
  and, if Gemini never commits a final segment, insert the last revisable
  interim instead of reporting `gemini_no_transcript`.

### Changed

- **Recording pill (`overlay`)** — flat resting line while silent; taller
  animated bars when speaking; fixed 96dp waveform lane width.

## [1.1.8] - 2026-09-06

### Changed

- **Recording pill (`overlay`)** — show a flat resting line while silent; animate
  full-height bars only when voice is detected. Slightly taller waveform lane.

## [1.1.7] - 2026-09-05

### Fixed

- **Recording pill (`overlay`)** — restore a fixed 96dp waveform lane; the pill
  had collapsed to a hairline after switching to Row weight without a width.

## [1.1.6] - 2026-09-05

### Fixed

- **Release artifact (`release`)** — rebuild and re-publish the signed release
  APK on GitHub (1.1.5 asset was stale for some download paths).

## [1.1.5] - 2026-09-05

### Changed

- **Studio typography (`ui`)** — bump body, label, settings-row, metric-chip,
  tab, and snippet sizes across Home, History, Dictionary, Settings, and
  onboarding; hero numeral size unchanged.
- **Recording pill (`overlay`)** — taller waveform bars that fill more of the
  expanded pill height.

## [1.1.4] - 2026-09-05

### Fixed

- **Launch crash (`ui`)** — Home and onboarding no longer load the adaptive
  launcher icon through Compose; they use the `ic_bubble_logo` PNG instead.

## [1.1.3] - 2026-09-05

### Changed

- **Onboarding (`ui`)** — Gemini key step links to Google AI Studio API keys
  and notes the free-tier allowance for Gemini 3.5 Transcribe Live; overlay,
  microphone, and notification steps auto-advance again after you grant them.

## [1.1.2] - 2026-09-05

### Changed

- **Studio UI polish (`ui`)** — drop display serif for Instrument Sans on
  greetings and headlines; use the real app icon on Home and onboarding;
  center onboarding content vertically with actions pinned to the bottom.

## [1.1.1] - 2026-09-05

### Changed

- **Studio visual fidelity (`ui`)** — Instrument Serif/Sans, 72sp hero numeral,
  hairline cards, grouped Settings drill-downs, editorial onboarding, and a
  P1+P3 hairline pill. The in-app shell now follows the locked HTML phones.

## [1.1.0] - 2026-09-05

### Changed

- **Studio in-app UI (`ui`)** — dark shell by default (no theme toggle), four-tab
  navigation (Home / History / Dictionary / Settings), week hero with sparkline,
  setup-only Home banner, grouped Settings with System and Privacy pages,
  pager onboarding including Restricted settings guidance, and a hairline glass
  recording pill with centered teal soundwave.

## [1.0.8] - 2026-09-05

### Fixed

- **Dictation no longer dies ~3 s after tap while Gemini is still connecting
  (`runtime`, `gemini`)** — the warm Live session was torn down on every tap
  before it could be claimed (`Starting` made the pool ineligible), forcing a
  cold WebSocket that often lost the 3 s pre-ready buffer race. The pool now
  stays eligible during `Starting` and retryable `Error`; taps adopt an
  in-flight prewarm via `claimOrAwait` instead of opening a duplicate socket;
  capture and session resolution run in parallel; the pre-ready buffer backstop
  is 10 s (500 frames); warm idle timeout is 180 s. `SESSION DONE` now records
  `warmClaimResult=`.

## [1.0.7] - 2026-08-27

### Changed

- **The smart final is the only dictation source (`runtime`)** — committed
  `inputTranscription` segments are the only text ever inserted. Interim
  partials (`interimInputTranscription`) are preview-only: they keep the quiet /
  tail barriers open while the model is still transcribing, but they can never
  settle or be inserted. If no final segment arrives, the session fails with a
  retry instead of inserting a provisional partial.

## [1.0.6] - 2026-08-27

### Fixed

- **Dictations no longer cut the last words (`runtime`, `transcript`)** — two
  follow-up fixes after 1.0.1's tail-final gate:
  - Committed final `inputTranscription` segments are now **authoritative**:
    a `smart`-mode final can be shorter than the last interim (fillers removed,
    no shared leading edge), and the revision heuristics previously dropped it —
    settling on the partial interim instead.
  - The tail backstop is **rearmed on every post-`activityEnd` revision**, so a
    model still streaming the tail (or a slow final segment) is never cut off by
    the old fixed boundary timer.

## [1.0.5] - 2026-08-27

### Fixed

- **Quick Settings logo fills the tile (`quicksettings`)** — the logo artwork
  now fills ~94% of the icon canvas (the 1.0.4 recentering had shrunk it to 80%,
  making it look small inside the circle).

## [1.0.4] - 2026-08-27

### Fixed

- **Quick Settings tile uses the owner-designed logo (`quicksettings`)** — the
  tile icon is now the transparent, monochrome mic + keyboard logo the owner
  provided (`whispertype_transparent_logo.png`), centered on a 512 px canvas, so
  the button shows the actual logo mark instead of a solid circle or square.

## [1.0.3] - 2026-08-27

### Fixed

- **Quick Settings tile shows the app logo (`quicksettings`)** — the tile icon
  was the square `ic_bubble_logo` bitmap, which Android rendered as a generic
  square. The tile now uses a dedicated circular icon generated from the logo
  artwork (`@drawable/ic_qs_tile`) — the white mic + keyboard on the blue-teal
  gradient — so the button renders as the round app logo.

## [1.0.2] - 2026-08-27

> **Device-test build.** Fresh build number on an unchanged 1.0.1 tree so the
> on-device build is identifiable. No behavior changes.

## [1.0.1] - 2026-08-27

> **Cut-off dictations fixed + Quick Settings tile.**

### Added

- **Quick Settings tile (`quicksettings`)** — add **WhisperType bubble** to
  Android's Quick Settings panel (app-logo icon) to turn the overlay bubble on
  or off. It reads and writes the same `App enabled` setting as the Settings
  screen, starts the runtime when enabling (overlay permission check; opens the
  permission screen if missing), and uses the existing kill switch when
  disabling.

### Fixed

- **Settlement waits for the tail final (`runtime`)** — quiet alone is no longer
  enough to settle: the session now waits for a committed final
  `inputTranscription` segment that arrives after `activityEnd` (or a server
  done-hint — `turnComplete` / `generationComplete`) before inserting. The
  2.5 s tail backstop remains the only path that settles on a partial, so words
  are never cut off under normal conditions.

## [1.0.0] - 2026-08-27

> **First stable release.** WhisperType 1.0.0 marks the Gemini 3.5 Transcribe
> Live engine (see 0.10.0) as the stable, supported dictation engine.

## [0.10.0] - 2026-08-27

> **Gemini 3.5 Transcribe Live + raw transcription engine.** The app now runs on
> Google's dedicated streaming transcription model `gemini-3.5-transcribe-live`
> (Live API, public preview), and the echo-era text stage is gone: no
> systemInstruction, no echo channel, no Groq — text shaping happens server-side
> in the model's `smart` transcription mode.

- **New model (`gemini`)** — the sole Gemini model is now
  `gemini-3.5-transcribe-live`: real-time streaming STT with interim partials
  (`interimInputTranscription`) and committed final segments
  (`inputTranscription`), sub-second latency, and automatic language detection /
  code-mixing. The setup requests TEXT modality, `smart` transcription mode, and
  a BCP-47 language hint (`en-US` / `hi-IN`) matching the speech mode. Covered at
  no cost by the owner's Google Pro API key. Model policy enforcement
  (`ModelPolicyTest`) is re-established on this base and pins the new model.
- **Echo channel deleted (`runtime`, `gemini`)** — dictation is now
  inputTranscription-only. The systemInstruction (which steered the model's
  spoken reply and made MEDIUM restructure speech) and the whole echo barrier
  stack (900 ms echo quiet + 2.5 s stall + 2 s source grace + 20 s deadline) are
  gone. Post-stop settlement is one 250 ms ASR quiet window plus a single 2.5 s
  tail backstop.
- **Groq removed entirely** — the Groq text-polish backend, the polish-level
  setting, the Groq API key field, key storage, validation, prompts, guard and
  their tests are all deleted. There is no third-party text stage: `smart` mode
  handles disfluency removal, self-corrections, formatting, and Hinglish
  code-mixing server-side.
- **Hinglish (`runtime`)** — the script-repair path is gone. The transcribe
  model's native code-mixing (with the `hi-IN` hint) produces the output, and
  whatever text it returns is inserted verbatim — the dictation never hard-fails
  on script.
- **Live wire (`gemini`)** — `interimInputTranscription` is parsed and emitted
  as INPUT candidates alongside finals; `maxOutputTokens` is omitted so no
  server-side cap can truncate a long transcript; an `omitGenerationConfig`
  flag exists for the preview-endpoint quirk where `responseModalities:["TEXT"]`
  suppresses final segments (device-verified; see `docs/GEMINI_LIVE.md`).

> **Note:** built on a hard reset of `main` to the 0.6.2 (37) lineage — the last
> release without Groq — so version codes 38–43 are intentionally skipped.
> The 0.7.0–0.9.x commits remain recoverable via the git reflog.

## [0.6.2] - 2026-08-12

> **Long-dictation truncation fix** — the app was settling before the model
> finished speaking its reply, cutting long dictations down to a fragment. It now
> waits for the generation to actually end.

- **No settlement while generation is in flight (`runtime`)** — settlement no
  longer fires after a short echo gap. The echo quiet window is 900 ms (above the
  measured 300–600 ms gaps of the streaming reply) and, when the server sends no
  completion signal, a 2500 ms stall backstop ends the reply. `turnComplete` /
  `generationComplete` end the wait early; an interrupted generation re-arms it.
  This is the fix for "long texts randomly become two words": the reply is never
  cut off mid-sentence again.
- **Explicit output budget (`gemini`)** — `maxOutputTokens = 8192` is sent in the
  setup so an unknown server-side output cap cannot truncate a long spoken reply.

> **Trade-off:** finalization is ~650 ms slower when a completion signal arrives
> and up to ~2.5 s when the server sends none — the deliberate price of never
> truncating. **Settings → Segment at pauses** is what buys the latency back.

## [0.6.1] - 2026-08-12

> **Long-dictation reliability + finalization fixes** — a long dictation whose
> echo condensed to a few words is no longer silently inserted as a fragment; the
> model's echo restart (the first words replaying at the end) is suppressed; and
> the recording waveform animates at ~20 Hz instead of 60 fps (battery + logcat).

- **Fragment guard (`runtime`)** — when a long recording settles on a text far
  too short to be the whole dictation (the echo condensed and the repair could
  not recover it), the app now shows a retryable "too long to transcribe fully"
  error instead of inserting a two-word fragment. Short genuine utterances are
  unaffected.
- **Echo restart guard (`transcript`)** — a long echo that re-emits the
  accumulated text plus a replay of its own beginning no longer duplicates the
  first words at the end; the replayed tail is suppressed until new content
  diverges.
- **Waveform (`ui`)** — the live waveform phase advances at ~20 Hz instead of a
  60 fps infinite animation: lower battery use, and no more
  `View.setRequestedFrameRate` logcat flood during recording (which was wiping
  the `SESSION DONE` diagnostics out of the buffer).

> **Device-pending:** the fragment and restart guards are JVM-verified; confirm
> on a physical device per `docs/TESTING.md` with a long dictation and read the
> `SESSION DONE` line.

## [0.6.0] - 2026-08-12

> **Instant recording start + latency overhaul** — tapping the bubble starts
> recording immediately (the Bluetooth source no longer blocks capture for up to
> 2 seconds when no headset is present), and finalization latency drops on every
> dictation. Long-dictation echo time is cut via segmented activities behind an
> experimental setting.

- **Tap-to-record (`audio`, `runtime`)** — capture starts on the tap before
  session resolution, and the overlay shows Listening as soon as the mic is hot
  (`capture -> resolveSession`, not the reverse). The Bluetooth SCO path skips
  its poll loop entirely when no bluetooth audio device is present and reduces
  the wait budget 2 s → 400 ms. Warm-prewarm no longer requires a visible
  keyboard, and the disk-reading `hasKey` check no longer runs during an active
  dictation.
- **Transcription correctness (`core`)** — the streamed-echo accumulator no
  longer misclassifies a mid-stream correction as new content once the text
  passes 4 words; duplicate-tail insertions are fixed.
- **Settlement timing (`runtime`)** — settle debounce reduced 600 → 250 ms,
  and the next dictation starts immediately after a successful insert instead
  of waiting out the 1.2 s idle timer.
- **Segmented activities (`experimental`, off by default)** — the recording is
  split at pauses so the model echoes each segment while the user keeps talking;
  only the last segment is outstanding at STOP. Enables with a setting; requires
  on-device validation.
- **Main-thread hygiene (`audio`, `data`)** — `AudioRecord.stop()/release()`
  move off the main thread; the history-blob write moves to `Dispatchers.IO`;
  the finalize audio-drain join is bounded.

> **Device-pending:** overlay, insertion, and audio-timing changes in this
> release are validated by JVM tests and a clean build only. Confirm on a
> physical device per `docs/TESTING.md` and read `SESSION DONE tapToCapture=
> … stopToSettled=` before trusting the latency numbers.

## [0.5.8] - 2026-08-08

> **More polished dictation + clipboard fallback** — the Medium and High
> output-polish levels are strengthened (High is fully rewritten), and when the
> transcript cannot be committed to a focused text field it is copied to the
> clipboard instead of being lost.

- **Output polish (`gemini`, `core`)** — Medium now targets exceptional,
  publication-grade writing (cut fillers/redundancy, tighten wording, vary
  rhythm, restructure freely); High is fully rewritten to transform speech into
  masterfully crafted, publication-grade prose with headings, lists, and
  paragraphs where they sharpen clarity. Both Medium and High additionally
  guarantee that facts, names, numbers, dates, and quoted phrases are never
  changed, invented, or dropped, and that the output stays close to the user's
  length.
- **Clipboard fallback (`dictation`, `accessibility`, `overlay`)** — when
  insertion fails because no text field is focused, the focused field's input
  connection cannot be reached, or the field changed mid-session, the settled
  transcript is copied to the clipboard (sensitive-marked on Android 13+) and a
  "Copied to clipboard" pill is shown before returning to idle. Protected or
  uncertain (secure) fields never copy.

## [0.5.7] - 2026-08-08

> **Samsung DeX support** — the bubble (and recording pill) now follows the
> display hosting the focused text field, so dictation works while your phone is
> in DeX mode. A safe editor focused on an external/DeX display is eligible even
> without a visible soft keyboard (hardware-keyboard setups included).

- **Display-aware overlay (`overlay`, `accessibility`, `ipc`)** — the
  accessibility service already tracked which display the focused editor lives
  on; that `displayId` now flows over IPC into the runtime, and the overlay
  window is re-parented onto that display's WindowManager. The bubble, drag
  drop-target, and recording pill all render on the DeX screen instead of the
  phone's. Phone behavior is unchanged (the phone is always the default display).
- **DeX eligibility (`core`)** — on a non-default display the soft-keyboard
  gate is relaxed: a safe, focused text field is enough, mirroring the existing
  physical-keyboard hotkey relaxation. The keyboard heuristic also scans every
  display (`getWindowsOnAllDisplays`) so a DeX on-screen keyboard counts.

## [0.5.6] - 2026-08-08

> **Bluetooth headset mic + physical-keyboard hotkey** — two new recording
> controls. Dictation can now record from a connected Bluetooth headset's mic,
> and a single hardware key toggles dictation start/complete.

- **Recording source (`settings`, `audio`)** — new `Settings → Recording →
  Recording source` option. Default remains the phone microphone; selecting
  **Bluetooth headset** records from the connected headset's mic, falling back
  to the phone mic when no headset is connected. The Settings row shows the
  connected device name (or a fallback notice).
- **Physical-keyboard hotkey (`accessibility`, `overlay`)** — a single
  physical key (default: grave/backtick, configurable in `Settings → Recording →
  Keyboard shortcut`, including a Ctrl/Alt/Shift/Meta combo captured by
  press-to-set) toggles dictation: press once to start, again to complete. Works
  without the soft keyboard visible; secure fields are still excluded.

## [0.5.5] - 2026-08-07

> **Self-healing kill switch** — toggling "App enabled" off and back on can no
> longer leave the bubble permanently missing. The accessibility process now
> re-reads the on-disk setting directly every couple of seconds, so re-enabling
> the app restores the bubble on its own, in-app, with no device fiddling.

## [0.5.4] - 2026-08-06

"App enabled" is now a genuine kill switch.

- **Off fully stops the runtime** — toggling `Settings -> General -> App enabled`
  off no longer just hides the bubble: it aborts any active dictation, removes
  the overlay window (clearing the system "displaying over other apps"
  notification), removes the "WhisperType is listening" foreground notification,
  and terminates the runtime service. Turning it back on restarts everything.
- **Accessibility Service stays registered but inert** — Android does not let an
  app disable its own system accessibility service, so while the app is off the
  service stops tracking, stops pushing eligibility, and releases its runtime
  binding; it resumes when the app is re-enabled.

## [0.5.3] - 2026-08-06

Hinglish mode stops translating English speech.

- **English stays English** — Hinglish mode previously let the model convert
  English dictation into Hinglish (e.g. "Let's meet tomorrow" becoming
  "kal milte hain"). The Hinglish instruction now carries an explicit
  no-translate rule: English spoken by the user is echoed in English, and only
  Hindi words the user actually says are romanized to Latin script.

## [0.5.2] - 2026-08-06

Self-mitigating "bubble missing" diagnostics.

- **Home "Why is the bubble not showing?" card** — the Home tab now explains
  exactly which conditions are hiding the bubble (accessibility service off,
  no focused field, secure field, keyboard hidden, mic off, no API key, app
  disabled), with a one-tap "Open accessibility settings" button when the
  accessibility service is the blocker. The same diagnostic that was previously
  logcat-only is now visible in-app.
- **Accessibility watchdog** — if the accessibility service was once connected
  and Android silently clears it (which happens when an app is force-stopped,
  and which made the bubble vanish with no signal), the runtime posts a
  low-importance notification that deep-links to accessibility settings and
  dismisses itself when the service reconnects.
- **Overlay status tile truth** — the Home "Overlay permission" tile now reads
  the real system setting instead of always showing green.
- **Docs** — troubleshooting guidance for why the accessibility service gets
  cleared (never force-stop the app; Samsung "Never sleeping apps") and how the
  new Home card + watchdog surface the fix.

## [0.5.1] - 2026-08-06

Follow-up to 0.5.0.

- **Live-model-only enforcement** — the app now uses only `gemini-3.1-flash-live-preview`.
  The REST `:generateContent` audio-recovery failsafe (which used `gemini-3.6-flash`)
  and the user-facing "Gemini model" override were removed. Reliability is now the
  echo completeness gate + raw ASR salvage (no recording re-transcription backstop).
- **Hinglish output is always Latin** — Hinglish settles echo-only (the raw ASR is
  never used for Hindi since it transcribes in Devanagari); when the Latin echo is
  missing or partial, the Devanagari text is transliterated to Latin server-side via
  the live model's text channel. Verified by probes: raw ASR = Devanagari, hardened
  echo = Latin for short and long dictations.
- **Polish prompts** — MEDIUM may rephrase/reorder; HIGH may fully rewrite, reorder,
  and add structure (bullets/paragraphs) while preserving every point; NONE/LOW stay
  verbatim.
- **Logo** — `app-logo.png` (blue-to-teal gradient) is the logo everywhere; the idle
  bubble and mini-dot are squircle-shaped instead of circles.

## [0.5.0] - 2026-08-06

First tagged release. Includes the 0.4.2 reliability + Wispr-style UI work (see the
0.4.2 section below for the full "never lose a dictation" details) plus:

- **Live-model-only enforcement** — the app uses exclusively the
  `gemini-3.1-flash-live-preview` live model. The audio-recovery REST backstop
  (`gemini-3.6-flash` `:generateContent`) and the user-facing "Gemini model"
  override are removed; no recording is retained for re-transcription.
- **Hinglish Latin guarantee** — Hinglish settles only from the instructed echo
  (never the raw ASR, which transcribes Hindi in Devanagari), with a live-model
  transliteration fallback; the hardened system prompt forbids Devanagari.
- **First-run onboarding** — a guided setup screen replaces the plain overlay
  screen: "How it works" steps, a live permission checklist (overlay,
  microphone, notifications, accessibility), and an inline **Gemini API key**
  entry; it waits for an explicit "Get started" and re-reads every permission
  on resume.
- **Bottom navigation** — Home / History / Dictionary / Settings tabs.
- **Dark mode** toggle; **card-grouped Settings**; history settings moved onto
  the History page; Dictionary spun out into its own page.
- **Status-pill anchoring** — Finalizing/Inserting/Recovering pills now center
  on the bubble instead of drifting off to the left. (The Recovering pill was
  later removed with the audio-recovery backstop.)
- **New default settings** — history on (30-day retention), Hinglish speech
  mode, bubble opacity 80%, mini-dot after 5 s, bubble size 38 dp.
- **Docs consolidation** — 19 markdown files reduced to a README hub + docs/;
  stale planning/failure-report docs deleted.
- **Release build signing** configured (keystore lives outside the repository);
  the signed release APK is tracked and the debug APK is no longer tracked.

## [0.4.2] - 2026-08-06 (in development, feature branch)

"Reliability first" — fixes the lost-transcription bug (first-few-words / 1-2 word summary / last-word-only) plus the Wispr-style UI round. Calibration evidence and the full reliability write-up are in `docs/GEMINI_LIVE.md` §15.

### Reliability — never lose a dictation

- **Delta-echo accumulation** — probes proved `outputTranscription` streams as word-level deltas (and the model condenses long turns): the echo accumulator now appends deltas (fixes "just the last word"), and neither accumulator ever shrinks to a strictly shorter revision.
- **Completeness gate** — the polished echo is inserted only when its content-word ratio covers the raw ASR; a truncated/summarized echo salvages the complete raw instead of inserting a fragment (no more silent partial output).
- **Audio-recovery failsafe** — the session recording is retained; when the settled text is far below the duration-derived expected words, the recording is re-transcribed via REST (`gemini-3.6-flash`, verified verbatim) and that full text is inserted. Never loses the user's words even when both live sources fail. (Since removed: the app now uses only the live model, so the recording backstop and its REST endpoint are gone.)
- **Settlement hardening** — settle debounce raised to 600 ms (above measured delta gaps); the raw fallback starts only after `activityEnd` so it is always the final ASR; never a retry because the echo was incomplete.
- **Instruction hardening** — explicit "never summarize, never shorten, never omit the end" clause in every polish style.
- **Diagnostics** — per-session settle path (`ECHO_COMPLETE` / `ECHO_PARTIAL_RAW` / `RAW_ONLY` / `ECHO_ONLY` / `NONE`) + word counts + recovery flag in the `SESSION DONE` log line (counts only, never transcript/audio; the recovery flag was removed with the backstop).

### Wispr-style UI

- **Recording pill** — thin translucent capsule: Done (green check) at the bubble anchor, live waveform center, Cancel (red X).
- **Real-time waveform** — flat on silence, rolling wave while speaking (replaces the circular pulse).
- **Mini-dot bubble** — the idle bubble auto-minimizes to a small dot (~3 s, 12 dp visual / 48 dp touch) and the dot starts dictation; toggleable in Settings.
- **Bubble appearance** — size (24-72 dp), opacity (10-100%); the bubble IS the app logo (`newapplogo.png`), round-clipped with no background circle; emerald-teal theme shared by every screen; launcher icon from `newapplogo.png`.
- **Settings sections** — labeled Recording / Bubble / Dictionary / History / Gemini account sections.
- **Home stats** — sessions, words, today's words, and WPM from history (with an enable hint when history is off); back returns Home from Settings/History.
- **X drop-target fix** — drop is checked before the target is hidden.

## [0.4.1] - 2026-08-06 (in development, feature branch)

Follow-up to 0.4.0 from on-device testing + host probes. See `docs/GEMINI_LIVE.md`.

- **Echo transcription engine** — the dictation source is now the model's instructed spoken reply (`outputTranscription`): the `systemInstruction` tells the model to repeat the user's speech back verbatim with the selected polish level (and, for Hinglish, in Roman/Latin script). Host probes proved `outputTranscription` works even with a `systemInstruction`, controls Latin script and polish, and streams in ~0.6 s (sentence) to ~8 s (100 words). `inputTranscription` (raw ASR) is the 2 s fast fallback; settlement prefers the echo (always waits for it, 20 s cap).
- **Bubble simplified** — one `TYPE_APPLICATION_OVERLAY` window, always `TOP|START` with an absolute pixel position and real-measured-size clamp; drag shows an X drop-target that hides the bubble until the next eligible field. Removed the gravity-switching placement logic that caused the jump-to-corner bug.
- **Long-dictation insertion fix** — surrounding-text verification window enlarged (5000/500) and a truncation-robust tail match, fixing "Could not confirm the text was inserted / Use Copy" on long dictations.
- **Docs** — the consolidated `docs/GEMINI_LIVE.md` records the verified echo mechanics.

## [0.4.0] - 2026-08-06 (in development, feature branch)

"Experience & Reach" — see the CHANGELOG entries below.

- **Android 13+ support** — `minSdk 33` so the Android 13 tablet can install the app.
- **Draggable bubble** — the mic bubble is now freely draggable to any position and the position is remembered.
- **Capsule UI + circular waveform** — compact recording capsule (Stop + Cancel) with an animated circular waveform, replacing the old full-width voice panel.
- **Auto-stop timeout** — silence-based auto-stop and a hard recording cap (whichever fires first); user-selectable 15/30/60/120/300 s, default 60 s.
- **Output-polish levels** — four transcription styles (`NONE`/`LOW`/`MEDIUM`/`HIGH`, default `MEDIUM`) passed through the Gemini `systemInstruction`.
- **Custom dictionary** — client-side correction rules with optional "always write as" spellings, applied at insertion.
- **Encrypted history screen** — viewable, encrypted transcript history: list, copy, delete one, delete all, retention-days respected.
- **Docs overhaul** — removed obsolete/historical docs and rewrote `docs/ARCHITECTURE.md` for the current two-process layout; see also the consolidated `docs/GEMINI_LIVE.md`.

## [0.3.1] - 2026-08-06

Voice-to-text reliability and Hinglish. See `docs/GEMINI_LIVE.md`.

### Transcription (inputTranscription is user speech)

- `TranscriptSelector` now uses a **user-speech trust policy**: only blank,
  punctuation-only, garbled, and Devanagari-in-English are rejected. Removed the
  model-preamble / greeting / acknowledgment / repetition rejections that were
  discarding real speech ("Okay so…", "Yes…", "Of course…", long sentences).
- Added `TranscriptSelector.diagnose()` → rejection-rule logging (never text).
- **Hinglish** now reaches the model via a Hinglish `systemInstruction` that
  biases transcription to **Latin script** (romanized Hindi), never Devanagari.
  A `languageCode` on `inputAudioTranscription` was tried and rejected by the
  Live API ("unknown name language code"), so none is sent.

### Failsafes / retries

- Lenient fallback: a rejected transcript is still inserted unless blank,
  garbled, or a clearly provisional fragment at the hard deadline.
- Retryable errors persist until the user taps **Retry** or **Dismiss**
  (`OverlayIntent.RETRY`, coordinator `retry()` / `dismiss()`); Retry button on
  the error panel.
- `SESSION DONE` now logs `reject=<rule>` and `lenient=true`.

### Tests

- TranscriptSelector reworked + new `diagnose()` coverage; coordinator tests for
  lenient fallback, retry, persistent errors; wire test for the no-languageCode
  setup; new `LanguageModeTest`.

## [0.3.0] - 2026-08-05

- Consolidated the A–F remediation into one release (documented in `docs/GEMINI_LIVE.md`).
- Home screen shows `Version <name> (<code>) · commit <git hash>` (BuildConfig
  GIT_COMMIT embedded at build time).

## [0.2.12] – [0.2.18] - 2026-08-05 (remediation releases A–F)

- **A (0.2.12)** — monotonic session metrics/counters + experimental realtime
  wire builders (activityStart / activityEnd / audioStreamEnd).
- **B (0.2.13)** — removed the dictation text prime; `inputTranscription` is the
  only dictation source; manual activity signaling; transport state machine
  `Connecting→Ready→ActivityStarted→ActivityEnded→Closed`; fail promptly on
  rejected boundaries.
- **C (0.2.14)** — host-testable `DictationCoordinator` + per-session holder;
  duplicate-START rejection; session-identity validation; insertion-result
  correlation; producer-owned `Chunker` orderly shutdown; capture-failure
  teardown; race tests.
- **D (0.2.15)** — key/`AudioRecord`/base64/JSON work off main; cached settings
  snapshot; shared `OkHttpClient`; amplitude throttled; removed raw-frame logs.
- **E (0.2.16)** — `TranscriptAccumulator` merge rules; one absolute 3 s
  deadline + 250 ms settle debounce; provisional-fragment rejection; retained
  early `turnComplete`; virtual-time tests.
- **F (0.2.17)** — `WarmLiveSessionManager` prewarm pool (30 s idle, 1/2/5/10 s
  backoff); immediate capture with a bounded 150-frame pre-ready buffer;
  `connecting` UI state.
- **(0.2.18)** — per-session `SESSION DONE outcome=… <metrics>` diagnostics.

## [0.1.0] - Initial private prototype (unreleased)

The foundation build of WhisperType Android. Requires Android 14+ (minSdk 34) and targets Android 16 (API 36).

### Accessibility

- `WhisperTypeAccessibilityService` detects focused editable fields and the on-screen IME window.
- `InputTargetTracker` snapshots the focused editor (package, window, field id, inferred input type) and increments an input generation on every focus change.
- `InputMethodWindowTracker` reports validated, debounced IME bounds (bottom-attached, keyboard-shaped) for dock placement.
- `SecureFieldClassifier` excludes password/PIN input types and flag-secure windows from ever showing the dock.
- Cancels the active session on focus change, IME hide, screen off, or mic-permission revocation.

### Overlay

- `OverlayWindowController` renders a docked circular mic button and a keyboard-covering voice panel as `TYPE_ACCESSIBILITY_OVERLAY` windows (non-focusable, touch-transparent outside bounds).
- `OverlayGeometryCalculator` places the dock by position, size, overlap mode, and opacity, clamped to the display.
- Docked mic UI honors system/light/dark theme, accent color, haptics, elapsed-time visibility, and cancel-side preference.

### Dictation

- `DictationCoordinator` runs exactly one dictation session at a time through a typed state machine (`Starting`, `Listening`, `Finalizing`, `Inserting`, `Success`, `CopyAvailable`, `Error`, `Cancelled`).
- 20 ms PCM16 mono capture via `AudioCapture`, bounded `AudioQueue`, and waveform amplitude for the panel.
- `DictationForegroundService` keeps the session alive with a microphone-type foreground service and a Stop/Cancel notification.
- Finalization drains the audio queue, sends a single activity-end boundary, and auto-dismisses the succeeded state after 900 ms.
- Cancellation discards the result; insertion is guarded by stale-target and secure-field checks.

### Gemini Live

- `GeminiLiveClient` streams audio to Gemini Live over a single WebSocket per session (`gemini-2.0-flash-live-001`, 16 kHz PCM16 mono).
- Raw and cleaned transcript candidates assembled by `GeminiTranscriptAssembler`.
- Typed, non-sensitive failure mapping (`AUTH_ERROR`, `NETWORK_LOST`, `TIMEOUT`, `PROTOCOL_ERROR`, `SERVER_ERROR`, `INTERRUPTED`); no retry after audio transmission.

### Validation and quality chain

- `CleanTranscriptValidator`/`TranscriptValidator` corruption rules (empty/symbol-only/preamble/repeated runs/implausible length).
- `CleanedTranscriptValidator` enforces cleaned <= 3x raw and rejects asterisk-marked output.
- `HinglishValidator` enforces Latin-script-only output in both English and Hinglish modes (Devanagari and other non-Latin scripts rejected).
- `CandidateSelector` prefers cleaned over raw, inserts exactly one candidate or the explicit Copy fallback.

### Security and privacy

- Gemini API key encrypted at rest with an Android Keystore AES-GCM key and stored in no-backup storage; rejected if not prefixed `AIza`.
- `SecretRedactor` applied to all log and exception output; no keys, transcripts, or authenticated URLs logged.
- `SensitiveClipboard` writes are explicit-only and marked `EXTRA_IS_SENSITIVE`.
- Backups fully excluded (`allowBackup=false` + `data_extraction_rules.xml`).

### Settings and onboarding

- DataStore-backed settings: speech mode (English/Hinglish), dock appearance, disabled-apps list, optional encrypted local history (off by default), onboarding completion flag.
- Step-by-step onboarding: intro, microphone, notifications, accessibility, API key, language, dock preview, demo.
- Compatibility test screen against a sample text field.

### Privacy fix (plan §16)

- History now stores the originating app package only when the user explicitly opts in via a new "Record originating app" toggle in `Privacy` settings, off by default. The application-scoped `HistoryRepository` is wired to this opt-in instead of hardcoding metadata inclusion.
- Added `scripts/verify-secrets.ps1` for the plan-required secrets audit (exits non-zero on real Gemini keys or forbidden signing/manifest files).
- Hardened `.gitignore` with emulator snapshot, transcript-fixture, and general Gemini-key patterns.

### Diagnostics

- `DiagnosticsExporter` in-memory 256-event ring buffer of typed codes and timing; export is privacy-safe.

### Known limitations

- Single utterance session per dictation.
- English and Latin-script Hinglish only.
- No transcript history browsing UI in v0.1 (history can be enabled and cleared in Settings).
- Physical-device acceptance is tracked in `docs/TESTING.md`.