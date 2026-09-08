# AGENTS.md

WhisperType: an Android voice-to-text app (Gemini Live) with a floating mic bubble.
Single Gradle module `:app`, Kotlin + Jetpack Compose. `docs/` is the authoritative
reference (README is just a hub). Start with `docs/CONTRIBUTING.md` before changing
code.

## Build & verify

**MODEL POLICY (non-negotiable):** the app may call **exactly one** Gemini model —
`gemini-3.5-transcribe-live` (the Gemini Live transcription model, over
`BidiGenerateContent`), pinned as `GeminiSessionFactory.LIVE_MODEL`. It is
covered at no cost by the owner's Google Pro API key; every other Gemini model,
endpoint, or provider is explicitly refused. Audio goes only to Gemini Live;
text shaping runs server-side in the model's `smart` mode. Enforced by
`ModelPolicyTest` — the build fails on any violation.

Requires JDK 17 and Android SDK platform 36. Run everything from the repo root:

```bash
./gradlew :app:testDebugUnitTest   # JVM unit tests, no device
./gradlew :app:lintDebug            # lint
./gradlew :app:assembleDebug        # debug APK
```

- **Warnings are errors everywhere**: `lint.warningsAsErrors=true` AND Kotlin
  `allWarningsAsErrors=true` in `app/build.gradle.kts`. Any warning fails the build.
- There are **no instrumented tests** — `app/src/androidTest` holds only a manifest.
  Overlay/accessibility/insertion behavior is validated manually on a physical device
  per `docs/TESTING.md` (device matrix + acceptance protocol). Never claim a change is
  verified without a device run for those paths.
- Never write a test or CI step that needs a live Gemini API key (MockWebServer is used
  instead). Run a single test with `./gradlew :app:testDebugUnitTest --tests "…Test"`.

## Architecture

Two processes, split by responsibility (see `docs/ARCHITECTURE.md`):

- **Main process** — `platform/runtime/FlowRuntimeService.kt`: overlay runtime, the
  Gemini Live session, microphone, orchestration. Foreground service with
  `specialUse|microphone` type.
- **`:accessibility` process** — `platform/accessibility/WhisperTypeAccessibilityService.kt`
  (`android:process=":accessibility"`): focus/keyboard tracking, secure-field exclusion,
  target capture, and text insertion only. Never give it overlay/mic/Gemini work.
- The two processes talk over a typed `Messenger` contract in `platform/ipc/RuntimeIpc.kt`
  (single source of truth for the wire format). No editor content crosses IPC.

Layer rules:
- `core/` is **pure, framework-free logic** (dictation state machine, transcript
  accumulate/completeness/select, audio framing, dictionary, privacy redaction, domain
  models, and `core/contracts/` interfaces like `DictationBridge`, `GeminiLiveSession`,
  `TargetGateway`, `OverlayController`). Platform code in `platform/` implements those
  contracts. Keep `core/` Android-free so it stays JVM-testable.
- `data/` = DataStore settings, Keystore+AES-GCM secrets, encrypted history.
- `audio/` = mic capture + bounded realtime pipeline.
- `ui/` = Compose screens + theme.

## Conventions

- **Version bump in the same commit** as any behavior change: `versionCode` +1 and
  `versionName` bump in `app/build.gradle.kts` (currently 1.2.1 / 67). Add a
  `CHANGELOG.md` entry for user-visible changes. The README version line lags the real
  version — trust `app/build.gradle.kts`.
- **Every push to the test device carries a fresh build number** — bump
  `versionCode` +1 and `versionName` on each device push, even when the code is
  unchanged, so the on-device build is always identifiable.
- Commit style: scope-qualified, imperative, lowercase —
  `feat(overlay): …`, `fix(insertion): …`, `test(gemini): …`, one logical change per
  commit. Branches: short-lived `feature/…` branches; integration happens on the
  `feature` branch → `main`.
- Never log transcripts, editor content, API keys, or the authenticated Gemini URL —
  only aggregate metrics (`SESSION DONE …` line) and typed codes. Use `SensitiveClipboard`
  for copy and `LogRedactor` semantics when writing anything sensitive.
- Docs are updated in the same change for behavior changes (see CONTRIBUTING review
  checklist).

## Gotchas

- **The release signing keystore `keystore/whispertype-release.keystore` and
  `signing.properties` are committed to git** (done by explicit user request; the keystore
  has no real password protection). This contradicts `docs/CONTRIBUTING.md` / `RELEASE_PROCESS.md`
  which say never to commit them. Don't "fix" this, don't leak/expose the files or their
  contents, and don't add real credentials anywhere. `app/build.gradle.kts` wires up the
  `release` signing config automatically when `signing.properties` exists.
- `BuildConfig.GIT_COMMIT` is embedded at build time via `git rev-parse --short HEAD`
  (`app/build.gradle.kts`), so builds run outside the git repo report "unknown".
- `app/build/outputs/apk/release/app-release.apk` (and previously the debug APK) is a
  **tracked artifact**; rebuilds change it, so be deliberate about staging it.
- `scripts/*.ps1` are PowerShell and Windows-oriented (ADB installs, diagnostics, secret
  scanning). On this Linux box use `./gradlew` directly.
- Release builds are signed only when `signing.properties` exists; without it the release
  APK is unsigned (`docs/RELEASE_PROCESS.md`).
