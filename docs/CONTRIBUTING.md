# Contributing to WhisperType Android

Guidelines for working on this repository: branch workflow, scope, tests, documentation, commit style, and the privacy rules that apply to every change.

## Repository layout

- `app/src/main/java/com/whispertype/android/` — grouped by layer: `platform/` (runtime, gemini, overlay, accessibility, ipc), `core/` (state, model, transcript, audio, dictionary, privacy, overlay, contracts), `data/` (settings, secrets, history), `audio/`, `ui/` (theme, settings, history, dictionary, waveform).
- `docs/` — architecture, design, protocol, security, testing, release, and troubleshooting references.
- `app/build.gradle.kts` — SDK levels, signing wiring, lint policy; `gradle/libs.versions.toml` — dependency versions.

## Git workflow

Local Git with a stable `main` and short-lived feature branches:

1. Feature branches start from the latest verified integration commit on `main`; never branch from an unverified state.
2. Each branch has exactly one clear scope (one feature or one bug).
3. Every feature ships with tests and documentation for the behavior it adds.
4. Avoid unrelated formatting changes; keep diffs focused.
5. The lead agent reviews every branch before integration.
6. A branch merges only after its focused tests pass.

Suggested branch names: `feature/dock-previews`, `fix/insertion-stale-token`, `docs/release-process`.

## Commit style

Scope-qualified, imperative, lowercase:

```text
feat(accessibility): detect active IME bounds
feat(overlay): add docked microphone control
feat(dictation): add Gemini Live foreground session
fix(insertion): reject stale input targets
test(gemini): cover activity-end ordering
docs(setup): document accessibility onboarding
```

Use `feat(...)`, `fix(...)`, `test(...)`, `docs(...)`, `refactor(...)` prefixes. One logical change per commit.

## Versioning

Every commit that changes app behavior bumps the app version **in the same commit** (`app/build.gradle.kts`):

- `versionCode` — increment by exactly 1 (Android requires a strictly increasing integer to update an existing install).
- `versionName` — bump in lockstep, at minimum a patch increment (e.g. `0.2.0 -> 0.2.1`); reserve minor bumps for features and major for breaking changes.

Keep the bump in the same commit as the behavior change so the built APK always carries a `versionName`/`versionCode` that matches the committed source. Do not commit APKs, keystores, or signing.properties.

Current baseline: `1.0.0` (versionCode `1`).

## Build and test commands

Run everything from the repository root. On Windows:

```powershell
.\gradlew.bat :app:testDebugUnitTest   # JVM unit tests (no device needed)
.\gradlew.bat :app:lintDebug            # lint; warnings are errors
.\gradlew.bat :app:assembleDebug        # debug APK
.\gradlew.bat :app:assembleRelease      # signed release APK
```

With a device connected:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Instrumented tests that depend on the Accessibility Service and a docking IME skip via `Assume` when those prerequisites are absent (see `docs/TESTING.md`).

## Lint and warning policy

- `lint.warningsAsErrors = true` and `abortOnError = true` in `app/build.gradle.kts`: zero warnings are acceptable and CI must not pass with warnings.
- `kotlin.allWarningsAsErrors` (`compilerOptions.allWarningsAsErrors`) is enabled for Kotlin compilation as well.
- A privacy review is part of every change: never commit API key literals, authenticated URL literals, sensitive log lines, or keystore/secret files, and never log transcripts or editor content (see the Privacy rules below).

## Tests

- Put pure-logic tests under `app/src/test` (JVM): state machine transitions, validation rules, protocol encoding, geometry, secret storage, redaction.
- Put UI/service/instrumented behavior under `app/src/androidTest`, using `TestHostActivity` for the host scenarios and `Assume` gating for physical-device tests.
- Name contracts after the behavior: `GeminiLiveClientContractTest`, `SecureFieldClassificationTest`, `CoordinatorFlowTest`.
- Do not write any test or CI step that requires a live Gemini API key.

## Privacy rules

These are non-negotiable and enforced by review and audit tests:

- Never commit the release keystore, signing passwords, Gemini API keys, private transcripts, real audio, generated APKs, or device logs containing user data.
- Never log API keys, the authenticated Gemini URL, complete transcripts, AccessibilityNode trees, or editor content. Only typed codes and aggregate timing appear in logs and diagnostics.
- When copy, use the explicit `SensitiveClipboard` path; never read the clipboard.
- Redact anything sensitive from issues and PR descriptions with `SecretRedactor` semantics before posting: keys are `AIza...`, query secrets, authorization values, and long base64/hex runs.

## Review checklist

Before opening a merge request:

- Scope matches the branch title and touches only the intended files.
- Unit tests and lint pass locally (`:app:testDebugUnitTest :app:lintDebug`).
- Documentation referenced in this repo is updated for behavior changes.
- No new secrets, transcripts, or privileged logs are introduced.
- `CHANGELOG.md` entry added for user-visible changes.