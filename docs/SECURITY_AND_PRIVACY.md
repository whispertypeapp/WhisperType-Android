# WhisperType Android — Security and Privacy

WhisperType Android is a private, local-first dictation app. Its security model is centered on keeping the Gemini API key encrypted on the device, keeping transcripts and audio out of storage and logs by default, and keeping the Accessibility Service's reach minimal. This document is the security and privacy reference for the app and describes how it handles secrets, transcripts, history, the clipboard, logging, Accessibility access, and the microphone.

## Vulnerability reporting

This project ships to trusted personal devices only. If you believe a change creates a security or privacy issue:

1. Report privately to the repository maintainers.
2. Never post secrets, transcripts, audio samples, or device logs that contain user data in a public issue.
3. Describe the issue with only the minimum non-sensitive details: failure codes, device class (for example Pixel or Samsung), Android version, keyboard, and the component involved.
4. There is no public bug bounty program.

Reasonable reports are acknowledged and triaged as a normal PR with the `privacy` scope label.

## Supported versions

| Version | Support |
| --- | --- |
| 0.4.x | Current line; developed on the `feature` branch. |

Only the current minor line is actively maintained. Devices on an older line than the currently tested one receive security fixes by upgrading to the current release.

## App threat model summary

- **API key encryption.** The Gemini API key is encrypted with an Android Keystore AES-GCM 256-bit key (alias `whispertype_api_key`) and stored as ciphertext plus a random 12-byte IV in an app-private no-backup file. The plaintext key is never written to DataStore, SharedPreferences, Room, resources, BuildConfig, or logs; it exists only transiently in memory. Incoming keys are rejected unless prefixed `AIza`. If the Keystore reports the key invalidated, the stored ciphertext is discarded and the user is asked to re-enter the key.
- **No key in Git.** Keystore files, `signing.properties`, API keys, and transcripts are blacklisted for the repository (text node). The optional local history is encrypted with a separate AES-GCM Keystore key (`whispertype_history`).
- **Transcripts are kept only in encrypted local history.** Transcript candidates exist only in memory for the duration of a session and are cleared on insertion, copy fallback, cancellation, or failure. Settled dictations are stored encrypted in local history, which is on by default (30-day retention) and can be turned off in the History tab. Transcripts are never logged.
- **No backend server.** Audio streams directly from the device to the Gemini Live API over TLS. There is no WhisperType cloud account or backend.
- **Backup exclusions.** `android:allowBackup="false"` plus `data_extraction_rules.xml` exclude every backup/device-transfer path (root, database, sharedpref, file, external).
- **Diagnostics redaction.** `DiagnosticsExporter` keeps at most 256 typed events with aggregate timing. Exports never include transcripts, audio, API keys, the authenticated Gemini URL, editor text, or app package data. Logging tags are stable and non-sensitive (`WT-Accessibility`, `WT-Gemini`, `WT-Dictation`, `WT-Settings`), and `LogRedactor` is applied to logs and exceptions.
- **Accessibility disclosure.** The service requests only editable-field detection, keyboard-window bounds, input connection access, and final text insertion, and never reads or stores unrelated screen content. Secure fields are excluded.
- **Microphone.** Recorded only during an active dictation session initiated by the user. A foreground notification with Stop and Cancel actions is shown while recording; there is no background recording.

## Security-critical code

- `data/secrets/` — `AesGcmCipher`, `KeystoreKeyStore`, `KeystoreKeyProvider`, `KeyProvider`, `SecretStore`, `BlobStore`, `SensitiveClipboard`
- `platform/accessibility/` — `AccessibilityTargetGateway`, `SecurityClassifier`, `InsertionDecision`, `InsertionVerifier`, `EditorTracker`
- `platform/gemini/OkHttpGeminiLiveSession.kt` (never logs the authenticated URL or keys)
- `data/history/EncryptedHistoryRepository.kt`
- `core/privacy/LogRedactor.kt` (applied to all log and exception output)

## API key storage

The Gemini API key is the only secret the app stores.

- The key is encrypted with an Android Keystore AES-GCM 256-bit key under the alias `whispertype_api_key`.
- Every encryption uses a random 12-byte IV; no IV is reused.
- The ciphertext and its IV are stored in an app-private no-backup file inside `getNoBackupFilesDir()`.
- The plaintext key is never placed in DataStore, SharedPreferences, Room, resources, BuildConfig, environment variables, or logs.
- Plaintext key material is cleared from memory after each use.
- Incoming keys are rejected if they do not start with the `AIza` prefix (invalid-key rejection at entry).
- If the Keystore reports `KEY_INVALIDATED` (for example after device lock-state or key-access changes), the stored ciphertext is discarded and the recovery path asks the user to re-enter the key.
- Keys are not bound to user authentication; a lock-screen credential is not required to use the key.

## Backup exclusion

- `android:allowBackup="false"` is set in the application manifest.
- Secrets and history live under `getNoBackupFilesDir()`.
- Secret and history files are never backed up to Google or Samsung cloud, and never transferred between devices.

## Transcript handling

- Transcript candidates exist only in memory for the duration of a dictation session.
- Candidate memory is cleared after successful insertion, copy fallback, cancellation, or fatal failure.
- Transcripts are never logged.
- Transcripts are never written to disk unless the user has enabled optional local history.

## Audio is never stored

Audio exists only in memory for the duration of a live session and is never
retained or stored. The former audio-recovery failsafe — which wrote a temporary
WAV to the app cache directory (`wt_recovery_<session>.wav`) while
re-transcribing a retained recording — was removed: the app uses only the
`gemini-3.5-transcribe-live` live model, so no recording is ever kept for
re-transcription. No transcript or audio content is ever logged.

## Optional history

- Local history is **on by default** with a 30-day retention; it can be turned off in the History tab, and transcript text is recorded only while it is enabled.
- When enabled, completed valid dictations are stored in an encrypted file store using a Keystore AES-GCM key under the alias `whispertype_history`.
- The history store lives in no-backup storage.
- Retention is capped by a retention-days setting; entries older than the chosen period are pruned automatically.
- History is viewable and deletable in-app via the **History** tab in the bottom navigation: copy an entry, delete a single entry, or clear all.
- `Clear all history` removes the stored records.
- Audio is never stored.
- API keys are never stored.
- Full editor context is never stored.
- The app package is stored only when the user explicitly enables history metadata.
- When history is disabled, no database or transcript file is created.

## Custom dictionary

- The custom dictionary (correction rules) has its own page in the app and is stored locally in app-private storage; it is never uploaded, backed up, or sent to Gemini.
- Correction rules are applied at insertion time — they are not prompt injection and are never included in any session configuration.
- Dictionary entries are never logged.

## Clipboard behavior

- WhisperType never reads the clipboard.
- The clipboard is written only on an explicit user Copy action.
- Written content is marked sensitive using `ClipDescription.EXTRA_IS_SENSITIVE` where supported.
- After copying, the app shows `Copied — paste with SwiftKey/Gboard` guidance plus a Dismiss action, and clears the in-memory result.

## Logging restrictions

- Logging uses stable, non-sensitive tags only: `WT-Accessibility`, `WT-Gemini`, `WT-Dictation`, `WT-Settings`.
- `LogRedactor` is applied to all log and exception output.
- The app never logs: API keys, authenticated Gemini URLs, complete transcripts, audio, AccessibilityNode trees, clipboard content, or custom dictionary entries.
- Crash reports and diagnostics carry only typed error codes and aggregate timing metadata.

## Accessibility disclosure

The exact service description shown to the user in Android's Accessibility settings (from `app/src/main/res/values/strings.xml`) is:

> WhisperType detects when a text field is focused and the keyboard is visible so it can place a small microphone control at the keyboard boundary. It inserts the dictated text at the cursor when you tap Stop. It does not read or store unrelated screen content.

The service is limited to:

- editable-field detection,
- keyboard-window bounds,
- input connection access,
- final text insertion.

It does not inspect unrelated screen content.

## Microphone

- `RECORD_AUDIO` is used only during an active dictation session.
- A foreground notification with Stop and Cancel actions is shown while recording.
- Android's privacy indicator (the green dot) is visible while recording.
- There is no continuous background recording.

## Security test checklist

The following tests must pass:

- Store and retrieve key.
- Replace key.
- Delete key.
- Corrupted ciphertext.
- Keystore unavailable.
- Device lock state.
- Backup exclusion.
- Log scanning for key patterns.

## Data flow

Tap the dock → audio streams directly from the device to Gemini Live over TLS → the final validated text is inserted at the cursor, or offered as a copy fallback. There is no WhisperType backend server and no cloud account.
