# WhisperType Android — Release Process

This document covers versioning, signing, APK generation, checksum generation, installation, upgrade testing, rollback/uninstall, and release notes for WhisperType Android. Manual command equivalents are given so the process is reproducible end to end.

## Versioning

- The project follows semantic versioning: `MAJOR.MINOR.PATCH`.
- The current line is 0.4.x. The version lives in `app/build.gradle.kts`:

```kotlin
defaultConfig {
    applicationId = "com.whispertype.android"
    minSdk = 33
    targetSdk = 36
    versionCode = 66
    versionName = "1.2.0"
}
```

- `versionName` is user-facing; `versionCode` is a strict integer that must increase monotonically, including for downgrade-prevention. Bump both for every release and add a `CHANGELOG.md` entry.

## Signing

The release keystore is the single artifact that must never enter the repository.

1. Create the keystore once, outside the repo, and back it up securely:

```powershell
keytool -genkeypair -v -keystore C:\secure\whispertype-release.keystore `
  -alias whispertype -keyalg RSA -keysize 2048 -validity 10000
```

2. Create `signing.properties` at the repository root (never commit it; add it to `.gitignore`):

```text
storeFile=C:/secure/whispertype-release.keystore
storePassword=<store password>
keyAlias=whispertype
keyPassword=<key password>
```

`app/build.gradle.kts` reads exactly these four properties (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) and creates the `release` signing config only when the file is present. Without it the release APK is unsigned and fails signature verification, so the release workflow fails clearly with a missing-file error before building.

## APK generation

Debug (unsigned, installable with ADB):

```powershell
.\gradlew.bat :app:assembleDebug
```

Release (signed when `signing.properties` exists):

```powershell
.\gradlew.bat :app:assembleRelease
```

Full pre-release verification (unit tests, lint with warnings-as-errors, assemble release):

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Outputs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

A privacy review is part of every release: the source tree is checked for API key literals, authenticated URL literals, sensitive log lines, and committed keystore/secret files (see `docs/CONTRIBUTING.md`).

## Checksum generation

Record the SHA-256 of every distributed APK. On Windows:

```powershell
Get-FileHash -Algorithm SHA256 -Path "app\build\outputs\apk\release\app-release.apk"
```

Store the checksum next to the APK in the versioned distribution directory and this is verified before installation by `install-release.ps1`.

## GitHub release

Each tagged version is published as a GitHub Release carrying the signed release
APK, so testers download one checksummed artifact (see `docs/TESTING.md`).

**Tags vs releases**

| | **Git tag** | **GitHub Release** |
| --- | --- | --- |
| What it is | A permanent label on a git commit (`v1.0.8`) | A GitHub page with notes + downloadable APK assets |
| Where it lives | Git history (local + remote) | github.com/…/releases |
| Required? | Yes — every published version must be tagged | Yes — every user-facing APK must have a release |
| Naming | Always `v` + semver: `v1.0.8` | Title without `v`: `1.0.8` |

**Cleanup rule:** every installable `v*` tag on `main` should have a matching
GitHub Release with an APK. Delete orphan tags (tagged but never released and
superseded by a later release) so the tag list matches the release list.

1. **Tag the release** on `main` with an annotated tag and push branch + tag:

   ```bash
   git tag -a v1.0.8 -m "release(1.0.8): <one-line summary>"
   git push origin main
   git push origin v1.0.8
   ```

2. **Create the release and attach the APK.** With the GitHub CLI:

   ```bash
   gh release create v1.0.8 \
     --title "1.0.8" \
     --notes-file CHANGELOG.md \
     app/build/outputs/apk/release/app-release.apk#app-release.apk
   ```

   or over the REST API with a personal access token (the `origin` remote of
   this repo already embeds one; never print it, and keep it out of logs and
   commit messages):

   ```bash
   curl -X POST -H "Authorization: token <TOKEN>" -H "Accept: application/vnd.github+json" \
     https://api.github.com/repos/<owner>/<repo>/releases \
     -d '{"tag_name":"v1.0.8","name":"1.0.8","body":"<release notes>"}'
   # upload the asset (replace <id> with the "id" from the create response):
   curl -X POST -H "Authorization: token <TOKEN>" \
     -H "Content-Type: application/octet-stream" \
     --data-binary @app/build/outputs/apk/release/app-release.apk \
     "https://uploads.github.com/repos/<owner>/<repo>/releases/<id>/assets?name=app-release.apk"
   ```

3. **Share link** — download link for the published release:

   `https://github.com/<owner>/<repo>/releases/download/<tag>/app-release.apk`

   The repository is public, so the link is accessible directly for automatic
   and browser downloads without requiring authentication.

4. **Verify the upload** — GitHub records the SHA-256 of every uploaded asset.
   Compare it against the local build (and the checksum from the Checksum
   generation section) via the asset endpoint:

   ```bash
   curl -s -H "Authorization: token <TOKEN>" \
     https://api.github.com/repos/<owner>/<repo>/releases/assets/<asset-id> \
   # the "digest" field (sha256:…) must equal `sha256sum app-release.apk`
   ```

## Installation

Preflight and install via the documented scripts:

- `scripts/adb-preflight.ps1` — locate ADB, list devices, require an explicit serial when multiple targets exist, print non-sensitive device/build info.
- `scripts/install-debug.ps1` — build/install the debug APK and optionally launch it.
- `scripts/install-release.ps1` — verify the APK and its checksum, select exactly one authorized device, install with `adb install -r`, and launch.

Manual equivalents:

```powershell
adb -s <serial> install -r app\build\outputs\apk\release\app-release.apk
adb -s <serial> shell am start -n com.whispertype.android/.MainActivity
```

The scripts must stop on errors, quote Windows paths safely, never default to every connected device, never print secrets, and never uninstall or clear data without an explicit switch plus confirmation.

## Upgrade testing

Before shipping a new version:

1. Install the previous version and complete onboarding.
2. Install the new version with `adb install -r` (upgrade path, no data wipe).
3. Verify: app opens, onboarding state is preserved, Accessibility Service stays enabled, API key still present and usable, dock appears over a supported keyboard, one dictation inserts correctly, and history (if enabled) is retained.
4. Also verify a clean install on a fresh device: onboarding completes, permissions request in order, and the first dictation works.

## Rollback / uninstall

- Rollback: uninstall the newer version, then install the older APK (`adb uninstall com.whispertype.android`, then `adb install` of the older artifact). Local-only user data (API key, settings, optional history) is lost on uninstall by design — the app is excluded from backups.
- Scripts never uninstall or clear data without an explicit switch and confirmation.

## Release notes

Each release entry in `CHANGELOG.md` must record:

- Version, date (or `Unreleased`), and a short summary.
- New features and fixed issues grouped by area (`accessibility`, `overlay`, `dictation`, `gemini`, `validation`, `security`, `settings`, `history`, `onboarding`, `diagnostics`).
- Any behavior changes and known limitations for that release.
- Do not include secrets, transcripts, audio, unique device identifiers, or sensitive diagnostic artifacts.

## Release gate

A deployment is complete only when: preflight works in all device states; a signed non-debug APK exists with the expected application id/version; `apksigner` verification succeeds against the recorded certificate; clean-install and upgrade tests pass; the versioned `dist` directory contains the APK, SHA-256 checksum, install guide, and release notes; at least one additional personal device install is tested or explicitly marked pending; and the release keystore plus credentials remain outside the repository.