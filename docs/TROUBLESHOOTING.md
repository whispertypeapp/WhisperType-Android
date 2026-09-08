# WhisperType Android — Troubleshooting

Common issues, ordered from the most frequent cause. WhisperType never logs transcripts, API keys, or the authenticated Gemini URL. The privacy-safe in-app signal for reporting a session is the `SESSION DONE` logcat line (see "Reporting a session problem" below); the acceptable-content rules are in `docs/SECURITY_AND_PRIVACY.md`.

## Accessibility service not binding

The bubble and dictation depend on the Accessibility Service.

Check:

1. `Settings -> Accessibility -> Downloaded/Installed apps -> WhisperType` shows the service enabled.
2. The service is not listed as stopped. Android can stop accessibility services under aggressive battery management, especially on Samsung.
3. If it is enabled but the bubble never appears, toggle it off and on, then focus a text field.

The service cancels the active session when it disconnects; re-enabling restores normal behavior. The Home tab shows an "Accessibility service" status tile — if it reads Off, tap it to jump to the system Accessibility settings.

## The accessibility service keeps getting turned off

Since 0.5.2, WhisperType self-diagnoses this instead of failing silently.

Android clears an app's accessibility services whenever the app is **force-stopped**
(killing it from the app switcher, from `Settings -> Apps -> Force stop`, or via an
aggressive battery manager that puts the app "to sleep"). The OS then writes the
`enabled_accessibility_services` setting to empty, so the bubble stops appearing even
though every in-app permission is still granted. The toggle in Settings will show as
Off again, or may revert shortly after you turn it on.

WhisperType's in-app signals for this case:

- The Home tab shows a **"Why is the bubble not showing?"** card listing
  "Accessibility service is off" with a one-tap button that jumps straight to the
  accessibility settings — no adb or logcat needed.
- The runtime posts a low-importance notification ("WhisperType accessibility
  service is off — tap to re-enable it") whenever the service was once connected
  and then stops reporting. It disappears automatically once the service reconnects.

To keep the service enabled:

1. Re-enable it: `Settings -> Accessibility -> Installed/Downloaded apps ->
   WhisperType`, and confirm the disclosure dialog.
2. Never force-stop WhisperType — do not swipe it away from the app switcher
   ("Close all"), do not tap "Force stop" in `Settings -> Apps`, and lock it in the
   recents list if your launcher supports it.
3. Samsung: `Settings -> Battery -> Background usage limits -> Never sleeping apps`
   -> add WhisperType, and set the app's battery to the least restrictive option
   (`Settings -> Apps -> WhisperType -> Battery`). This prevents the battery manager
   from putting WhisperType to sleep (force-stopping it).

## The bubble does not appear

The bubble requires all of the following at once:

1. Overlay (draw over other apps) permission granted — the app prompts for it on first launch.
2. The runtime service is running (Home tab -> "Runtime service" tile -> Fix restarts it).
3. Accessibility Service enabled.
4. Microphone permission granted.
5. A Gemini API key saved (key status is shown on the Home tab and under `Settings -> Gemini account`).
6. The app is enabled: `Settings -> General -> App enabled` must be ON. When it is off, the runtime is fully stopped — bubble, notifications, and dictation are all off until you turn it back on. This is the kill switch.
7. A focused text field that is not secure (password/PIN/payment fields never show the bubble).
8. A fully visible, supported docked keyboard (no floating/split layouts).
9. The screen is unlocked.

If the keyboard was just shown, the bubble may take up to ~300 ms to appear while IME bounds settle. Re-focus the field if needed.

Since 0.5.2, the Home tab shows a **"Why is the bubble not showing?"** card whenever
the bubble is hidden: it names the exact blocking condition (accessibility service
off, no focused field, secure field, keyboard hidden, mic off, no API key, or the
App-enabled kill switch) and offers a one-tap fix where one exists. Use it before
turning to logs.

## Mini-dot / bubble

After a few seconds of idle (1-15 s, default 3 s), the bubble auto-minimizes into a small dot. Tapping the dot starts dictation exactly like tapping the bubble. A visible dot is not a fault — the bubble is just minimized. You can disable this in `Settings -> Bubble -> Mini dot`, or change the delay in `Settings -> Bubble -> Turn to dot after`.

## Microphone permission

- Grant it from the Home tab (tap the "Microphone permission" tile -> Fix) or `Settings -> Apps -> WhisperType -> Permissions -> Microphone`.
- If the microphone is revoked mid-session, WhisperType cancels the dictation immediately.
- A mic start failure shows a typed error: `runtime_mic_permission`, `MIC_INIT`, or `MIC_READ`. If another app is using the microphone, close it first.

## Notification permission & foreground service

Notification permission (`POST_NOTIFICATIONS`) is **optional**: declining or dismissing it does not block setup, does not fail system diagnostics, and does not prevent dictation.

When notifications are permitted:
- **Idle**: Ongoing silent notification `WhisperType bubble is ready` (`Your microphone turns on only when you tap the bubble.`).
- **Recording**: Active notification `Recording dictation` (`WhisperType is using your microphone.`) with direct **Stop** and **Cancel** buttons.
- **Finishing**: Transient notification `Finishing dictation` (`Your microphone is off.`) while text is finalized and inserted.

If notification permission is turned off, Android may still display an active foreground service for WhisperType in Task Manager / Active Apps because the floating overlay runs in a foreground service. You can open Android notification settings at any time via `Settings → System → Notifications`.

## API key validation

- Enter the key under `Settings -> Gemini account` and tap `Save key`. The key is encrypted with Android Keystore-backed storage and never stored in logs, preferences, or backups.
- Gemini rejects an invalid key with 400/401/403 on the live connection. Create a new key in Google AI Studio with Gemini API access.
- "Network error": no internet or a VPN/firewall/private DNS is blocking `generativelanguage.googleapis.com`.
- A session started without a stored key fails immediately with `runtime_no_api_key`.
- If the device invalidates the Keystore key, decryption fails and the app discards the stored ciphertext — re-enter the key.

## Network loss / FGS_START_DENIED

- A transport failure while the session is active surfaces as `gemini_transport`; a session cancelled by network loss reports `NETWORK_FAILURE`. No retry happens mid-session — start a new dictation when connectivity returns.
- Timeouts (`TIMEOUT`) mean the speech service did not respond in time; check your connection and try again.
- **`The Gemini connection is too slow`** (`gemini_connection_too_slow`): the pre-ready audio buffer overflowed while the Live session was still waiting for `setupComplete`. Since 1.0.8 this is a 10 s backstop (500 frames), not the primary connect timeout — if you still see it, check `SESSION DONE` for `overflow=true` and `warmClaimResult=` (a healthy tap should show `warmClaimResult=HIT overflow=false`). Retry after confirming network and API key.
- `FGS_START_DENIED` means Android refused to start the microphone foreground service. Check that the notification permission is granted, another app is not holding the mic, and the app is not in a restricted/stopped state.

## Secure fields never show the bubble

Expected behavior. Password, PIN, payment, banking-authentication, private-browsing, and flag-secure fields are excluded by design. WhisperType never appears there and never records in them.

## Insertion fails (text offered as Copy instead)

Fields without a safe input connection cannot be written to directly: unusual WebViews, canvas-based editors, remote-desktop apps, and some OEM text fields. WhisperType finishes the transcription and shows a Copy fallback instead of silently pasting. Tap `Copy`, return to the field, and paste with your keyboard.

If the text could not be committed, WhisperType surfaces `insert_ambiguous` (Copy fallback) or fails with `insert_target_stale`, `insert_target_not_safe`, or `insert_connection_unavailable` — the result was intentionally not committed because the field state changed or was protected. Tap the field and start again.

## Why a session almost never loses words

Expected behavior. Reliability comes from committed final segments plus the
fragment guard: the transcribe model's `inputTranscription` finals are the only
dictation source, the accumulator never shrinks settled text, and a fragment
guard refuses to insert a truncated long dictation. If nothing usable arrives
within the tail backstop, the session reports `gemini_no_transcript` with a
Retry affordance instead of inserting a fragment. WhisperType uses only the
`gemini-3.5-transcribe-live` live model; there is no recording
re-transcription backstop.

## No transcript could be recognized

Now rare. When it does happen, check the session's `SESSION DONE` log line:

- `reject=<rule>` — a transcript arrived but the selector rejected it; `lenient=true` means the failsafe still inserted it. Any remaining rejection is blank / punctuation-only / garbled / Devanagari-in-English.
- `inputTx=0` — the Live model returned nothing within the tail backstop; retry the dictation.
- `settle=<path>` — how the final text was chosen: `raw_only` or `none`.

## App won't install on Android 13

Android 13 is supported since 0.4.0 (minSdk 33). If the install is blocked:

1. Confirm you are installing the 0.4.0 (or newer) build, not an older APK.
2. Allow the app used to open the APK to install unknown apps.
3. On a tablet, confirm the build targets Android 13+ — the first-install flow prompts for overlay permission, then microphone and notifications.

## Permissions

- `RECORD_AUDIO` (microphone) is required for speech capture. It is requested during onboarding. If revoked, dictation cannot record and reports `runtime_mic_permission`.
- `SYSTEM_ALERT_WINDOW` (overlay) is required to draw the floating mic bubble over other apps.
- `POST_NOTIFICATIONS` is optional: declining it does not block onboarding, does not trigger a Home banner, and does not prevent dictation.

## History empty

Local history is on by default (30-day retention) and can be turned off in the History tab; transcript text is recorded only while it is enabled.

1. Open the History tab (bottom navigation).
2. Enable `Save dictation history`.
3. Dictate again — new entries appear in the list on the same page.
4. Note that enabling history does not backfill older sessions, and entries are pruned once older than the retention period (7-90 days). The Home tab stats grid is derived from this history.

## Dictionary not applied

Custom dictionary corrections match word-boundary and are case-insensitive. The dictionary is managed on its own page (Dictionary tab in the bottom navigation).

- Check the word is entered exactly as you will say it (partial/fused matches do not apply).
- Check an `Always write as` spelling is set where needed.
- Corrections are applied at insertion; the live transcript is not rewritten mid-session.

## OEM battery optimizations

- Samsung: `Settings -> Apps -> WhisperType -> Battery`; choose the least restrictive setting available. If the Accessibility Service keeps stopping, re-enable it from `Settings -> Accessibility -> Installed apps -> WhisperType`.
- Aggressive battery managers may kill the process between dictations; that is benign because WhisperType does no background recording.

## Reporting a session problem (SESSION DONE)

The relevant in-app signal is the `SESSION DONE` logcat line, logged once per session by `FlowRuntimeService` when the session finishes. It is privacy-safe: only the outcome class, aggregate counts, stage timings, and flags — never transcripts, audio, API keys, editor text, or the authenticated Gemini URL.

1. Reproduce the issue (bubble missing, dictation error, insertion failure).
2. Capture the line:

   `adb logcat -s FlowRuntimeService` (or filter for `SESSION DONE`).

3. Share the `SESSION DONE` line with the maintainers.

Useful fields: `outcome=`, `inputTx=`, `outputTx=`, `reject=`, `lenient=`, `settle=`.

## Privacy-safe reporting

When reporting an issue: share only the `SESSION DONE` line and other non-sensitive logcat, never paste API keys or transcripts, and never attach logcat output that may contain accessibility node content. The `core/privacy/LogRedactor` strips secrets from app logs. `docs/SECURITY_AND_PRIVACY.md` defines the acceptable content.
