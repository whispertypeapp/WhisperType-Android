# WhisperType — AI Voice to Text for Android

Voice to text for Android that keeps your normal keyboard. Stream your speech to
Google's Gemini Live API and get the transcription inserted at the cursor of
whatever text field is focused — in Messages, Gmail, Notes, WhatsApp, or any
app with a normal text field.

**Private by design:** local-first. Your API key is encrypted on-device with an
Android Keystore AES-GCM key, and transcripts and audio are never stored or
logged by default. There is no WhisperType backend server, no cloud account, and
nothing leaves your device except the audio stream to Google's Gemini Live API.

---

## Highlights

- **Draggable mic bubble** — a freely draggable microphone bubble floats above
  your keyboard. It is the app logo itself (round), and it auto-minimizes to a
  small mini-dot after a few seconds of idle; the dot starts dictation too.
  Bubble size and opacity are configurable.
- **Recording pill** — while dictating, a thin translucent capsule shows
  **[Cancel ✕] [live waveform] [Done ✓]**; Done commits, Cancel discards. The
  pill is anchored so Done sits exactly where you tapped the bubble.
- **Bluetooth headset mic** — by default dictation records from the phone mic.
  Set `Settings → Recording → Recording source` to **Bluetooth headset** and the
  connected headset's mic is used (falling back to the phone mic when no headset
  is connected).
- **Physical-keyboard hotkey** — a single hardware key (default: the grave/backtick
  key, configurable in `Settings → Recording → Keyboard shortcut`) toggles
  dictation: press once to start, again to complete. Works without the soft
  keyboard being visible; secure fields are still excluded.
- **Real-time waveform** — a flat line on silence, a dancing multi-peak skyline
  while you speak (sensitive to quiet voices).
- **Never lose a dictation** — the committed final segments from the transcribe
  model are the sole dictation source; a quiet-window settlement plus a single
  tail backstop guarantee the session always terminates, and a fragment guard
  refuses to insert a truncated transcript (reliability first: quiet-window settlement plus a tail backstop).
- **Smart transcription** — text shaping runs server-side in the transcribe
  model's `smart` mode: filler/disfluency removal, inline self-corrections,
  grammar and casing polish, structured formatting. No separate text stage.
- **English + Hinglish** — automatic language detection with a `en-US` / `hi-IN`
  hint from the speech mode; Hinglish code-mixing is handled natively by the
  model, and whatever it returns is inserted verbatim.
- **Auto-stop** — stops on silence or at a configurable hard cap
  (15 / 30 / 60 / 120 / 300 s, default 60 s).
- **Custom dictionary** — client-side correction rules with optional
  "always write as" spellings, applied at insertion.
- **Encrypted history** — a viewable History page (list, copy, delete, delete-all)
  over an encrypted store; on by default (30-day retention), switchable in Settings.
- **Dark mode** — an emerald-teal brand theme across every screen, with a light
  and a dark scheme.
- **Bottom navigation** — Home / History / Dictionary / Settings.
- **Android 13+** — `minSdk 33`, works on phones and tablets.

---

## How it works

```
App (Messages, Gmail, Notes…)            WhisperType
+---------------------------------+      +----------------------------+
| [ text field        cursor    ] |      | Mic bubble appears above the|
|                                 |      | keyboard; tap it to speak. |
+---------------------------------+      |                            |
|  QWERTYUIOP  (your keyboard)    |      | Recording pill: ✕ wave ✓  |
+---------------------------------+      |                            |
                                        Audio → Gemini Live API (TLS)
                                        Transcription → inserted at cursor
```

1. Focus any normal text field. The WhisperType bubble appears above the keyboard.
2. Tap the bubble (or the mini-dot) and speak.
3. A recording pill with a live waveform shows while you speak.
4. The validated transcription is inserted at the cursor; your keyboard returns.

**The engine.** Audio streams over TLS to a Gemini Live realtime session that
uses only the **`gemini-3.5-transcribe-live`** model — Google's dedicated
streaming transcription model. The server returns revisable partials
(`interimInputTranscription`) while you speak and committed final segments
(`inputTranscription`) when each segment ends; both feed a session-local
accumulator, and settlement waits one short quiet window (250 ms) plus a single
tail backstop. Text shaping happens in the model's `smart` mode, so there is no
echo channel, no `systemInstruction`, and no other model or endpoint to fall
back on. See `docs/GEMINI_LIVE.md` for the full engine and wire reference.

---

## Quick start

1. **Install the APK** — copy the signed release APK
   `app/build/outputs/apk/release/app-release.apk` to your phone and install it.
   On Android 13+, allow the app used to open the APK to install unknown apps.
2. **Grant permissions** — allow overlay, microphone, and notifications when
   prompted.
3. **Enable the Accessibility Service** — `Settings → Accessibility →
   WhisperType`.
4. **Add your Gemini API key** — Settings tab → Gemini account → Save key. The
   key is encrypted on-device with an Android Keystore AES-GCM key and never
   logged.
5. **Dictate** — focus a text field, tap the bubble, and speak.

See `docs/USER_SETUP.md` for the complete step-by-step walkthrough (permissions,
Accessibility, API key, daily use, and every setting).

---

## Privacy & security

WhisperType has no backend server and no cloud account.

- **API key** — the only secret the app stores. Encrypted with an Android
  Keystore AES-GCM 256-bit key, kept in an app-private no-backup file, never
  placed in DataStore, SharedPreferences, logs, or BuildConfig. Keys are
  rejected unless they start with `AIza`.
- **Transcripts & audio** — never logged; settled dictations are stored encrypted
  in local history (on by default, 30-day retention, switchable in the History
  tab). No recording is retained beyond the live session, so audio is never
  stored.
- **Accessibility** — the service detects editable fields, keyboard bounds, and
  performs final text insertion; it never reads or stores unrelated screen
  content. Secure fields (password, PIN, payment) are excluded by design.
- **Backups** — `allowBackup=false` plus extraction rules exclude every backup
  and device-transfer path.
- **Microphone** — recorded only during an active, user-initiated dictation,
  with a foreground notification; no background recording.

The full policy — key storage, transcripts, history, clipboard, logging
restrictions, and the threat model — is in `docs/SECURITY_AND_PRIVACY.md`.

---

## Documentation map

| Topic | Document |
| --- | --- |
| User setup & daily use | `docs/USER_SETUP.md` |
| Architecture (two processes, modules, session flow) | `docs/ARCHITECTURE.md` |
| Gemini Live voice engine & wire reference | `docs/GEMINI_LIVE.md` |
| Testing, device matrix & on-device protocol | `docs/TESTING.md` |
| Release process, versioning & signing | `docs/RELEASE_PROCESS.md` |
| Security & privacy policy | `docs/SECURITY_AND_PRIVACY.md` |
| Troubleshooting | `docs/TROUBLESHOOTING.md` |
| Contributing (branching, commits, privacy rules) | `docs/CONTRIBUTING.md` |
| Release history | `CHANGELOG.md` |

---

## Build & test

**Prerequisites:** JDK 17, an Android SDK with platform 36 installed (licenses
accepted), and internet access for the Gemini Live API at runtime. The Gradle
wrapper (Gradle 8.14.3, AGP 8.13.2, Kotlin 2.4.10) downloads everything else.

```bash
./gradlew :app:testDebugUnitTest   # JVM unit tests (device-free)
./gradlew :app:lintDebug            # lint; warnings are treated as errors
./gradlew :app:assembleRelease      # signed release APK
```

The signed release APK is written to
`app/build/outputs/apk/release/app-release.apk` (a debug APK is available via
`:app:assembleDebug`).

The release build is signed when `signing.properties` exists at the repository
root (see `docs/RELEASE_PROCESS.md` for signing, versioning, checksums, upgrade
tests, and the release checklist).

---

## Supported devices, keyboards & limitations

- **Android 13+** — `minSdk 33`, `targetSdk 36`, `compileSdk 36`.
- **Keyboards** — SwiftKey, Gboard, and Samsung Keyboard in standard docked
  mode (or any IME that can be the device default). Floating and split keyboard
  layouts are not supported and do not show the bubble.
- **Accessibility Service** — required; WhisperType works only while it is
  enabled.
- **Secure fields** — password, PIN, payment, and flag-secure windows never show
  the bubble; they are excluded by design.
- **One session at a time** — each session is a single utterance bounded by the
  auto-stop settings.
- **Languages** — English and Hinglish (Latin script). English mode rejects
  Devanagari; Hinglish is defensive about it.
- **Insertion** — depends on a safe input connection; unusual editors (some
  WebViews, canvas editors) fall back to an explicit Copy action.

---

## Repository layout

```
app/src/main/java/com/whispertype/android/
  platform/     Gemini Live session + wire codec, overlay host, accessibility
                service (own process), runtime coordinator + foreground service
  core/         pure logic: transcript accumulation/selection/completeness,
                dictation state machine, models, dictionary, audio framing,
                privacy redaction, contracts
  data/         settings (DataStore), secrets (Keystore + AES-GCM), encrypted
                history store
  audio/        microphone capture, PCM chunking, pre-ready buffer, session
                recording
  ui/           Compose screens (Home, History, Dictionary, Settings), theme,
                waveform
app/src/test/   JVM unit tests (device-free)
docs/           documentation hub
scripts/        build, install, and diagnostics helper scripts
```

---

## Version

Current version **1.0.0** (versionCode 1), developed on the `main` branch.
See `CHANGELOG.md` for the full release history.

## License

Licensed under the Apache License, Version 2.0 — see `LICENSE`.
