## WhisperType Android setup

WhisperType Android keeps your existing SwiftKey, Gboard, or Samsung Keyboard active. It does not replace your keyboard.

When you focus a normal text field, a round mic bubble (the app logo) appears near it. The bubble is freely draggable and, after a few seconds of idle, auto-minimizes into a small dot. Tapping the bubble or the dot starts dictation. While you speak, a compact recording pill appears with Cancel (red X), a live waveform, and Done (green check) — Done commits the text, Cancel discards it.

## Requirements

- Android 13 or newer (0.4.x targets Android 13+ with a runtime RECORD_AUDIO + POST_NOTIFICATIONS permission flow).
- A supported Pixel or Samsung device, or an Android 13+ tablet.
- Gboard, SwiftKey, or Samsung Keyboard in standard docked mode.
- Internet access for Gemini Live.
- A Gemini API key.
- Permission to use the microphone.
- Overlay (draw over other apps) permission.
- WhisperType Accessibility Service enabled.

## Install the APK

1. Copy the signed WhisperType APK to your phone.
2. Open the APK.
3. If Android blocks installation, open the displayed settings page and allow the application used to open the APK to install unknown apps.
4. Return to the APK and install it.
5. Open WhisperType.

For development installation from a computer:

```powershell
adb install -r WhisperType-Android-1.0.0.apk
```

## Grant overlay permission

The bubble is drawn over other apps, so overlay access is required.

1. Open WhisperType.
2. On first launch, tap `Grant overlay permission` and allow it in the system dialog.

## Grant microphone permission

1. Open WhisperType.
2. On the Home tab, tap the `Microphone permission` tile (or `Grant microphone & notifications`).
3. Choose `While using the app` if Android presents that option.
4. The tile now reads On.

WhisperType does not record continuously. The microphone is used only during an active dictation session.

## Grant notification permission

WhisperType shows a recording notification while the microphone is active.

1. On the Home tab, tap the `Notifications` tile and allow it.
2. If you previously denied it, open:

   `Settings → Apps → WhisperType → Notifications`

3. Enable notifications.

The notification includes Stop and Cancel actions while recording.

## Enable Accessibility access

Accessibility access is required so WhisperType can:

- detect when a text field is active,
- detect the active keyboard’s position,
- place the mic bubble,
- insert the final text at the cursor.

WhisperType does not use this permission to read or store unrelated screen content.

### Pixel / stock Android

1. Open `Settings`.
2. Tap `Accessibility`.
3. Tap `Downloaded apps` or `Installed apps`.
4. Tap `WhisperType`.
5. Turn on `WhisperType`.
6. Read the disclosure.
7. Tap `Allow`.
8. Return to WhisperType.

### Samsung Galaxy

1. Open `Settings`.
2. Tap `Accessibility`.
3. Tap `Installed apps`.
4. Tap `WhisperType`.
5. Turn on the service.
6. Confirm the disclosure.
7. Return to WhisperType.

The exact labels may vary slightly by Android or One UI version.

### Sideloaded APK: “Restricted setting” (Android 13+)

If you installed WhisperType from an APK (not the Play Store), some phones grey
out the accessibility toggle until you allow restricted settings:

1. Open `Settings → Apps → WhisperType` (App info).
2. Tap the **⋮** menu → **Allow restricted settings**.
3. Confirm with your PIN or fingerprint.
4. Return to `Settings → Accessibility`, enable WhisperType, and come back to the app.

The in-app onboarding wizard walks through this path if accessibility still
will not stay on after your first attempt.

## Keep your normal keyboard selected

WhisperType does not need to become your default keyboard.

To verify your keyboard:

### Pixel / stock Android

`Settings → System → Keyboard → On-screen keyboard`

### Samsung

`Settings → General management → Keyboard list and default`

Keep SwiftKey, Gboard, or Samsung Keyboard selected as your default.

## Configure the Gemini API key

1. Open WhisperType.
2. Tap the `Settings` tab (bottom navigation).
3. Open the `Gemini account` section.
4. Paste or type your key.
5. Tap `Save key`.
6. The card confirms the key is configured; the Home tab's `Gemini API key` tile reads On.

The key is encrypted with Android Keystore-backed storage. It is not saved in normal app preferences, history, logs, backups, or the clipboard.

## Choose speech mode

Open:

`WhisperType → Settings tab → Recording`

Choose:

- `English`
- `Hinglish`

Hinglish supports natural English/Hindi code-switching; the transcribe model's
automatic code-mixing (with a `hi-IN` language hint) produces romanized Latin
output natively, and whatever it returns is inserted verbatim.

## Customize the bubble

Open:

`WhisperType → Settings tab → Bubble`

Available controls:

- Bubble size (24-72 dp).
- Bubble opacity (10-100%).
- `Mini dot` — shrink the bubble to a small dot when idle (the dot still starts dictation).
- `Turn to dot after` — idle seconds before auto-minimizing (1-15 s, default 3 s).
- `Reset bubble position` — return the bubble to its default spot.

The mic bubble is freely draggable: drag it with your finger to place it anywhere on screen. Its position persists across sessions and device restarts. The bubble is the round app logo.

## Set the auto-stop timeout

Open:

`WhisperType → Settings tab → Recording → Auto-stop timeout`

Choose 15, 30, 60, 120, or 300 seconds. The default is 60.

Recording stops automatically after you have been silent for the chosen interval. A hard cap also stops any session that reaches the maximum duration, even if you are still speaking.

## How the transcript is shaped

WhisperType runs on Google's dedicated streaming transcription model
(`gemini-3.5-transcribe-live`) in **smart** mode: the transcription arrives with
filler words and disfluencies removed, self-corrections resolved, and grammar,
casing, and formatting polished — all server-side. There is no separate polish
setting; every dictation gets the smart treatment automatically. (See
`docs/GEMINI_LIVE.md` for the engine.)

## Choose the recording source

Open:

`WhisperType → Settings tab → Recording → Recording source`

Choose:

- `Phone microphone` (default) — always records from the phone's built-in mic.
- `Bluetooth headset` — records from a connected bluetooth headset's mic.

The default is `Phone microphone`. Bluetooth is strict opt-in: even with a headset
paired, dictation keeps using the phone mic until you explicitly select
`Bluetooth headset`. With `Bluetooth headset` selected, the Settings screen shows
the connected device's name; if no headset is connected (or one cannot be used at
the required 16 kHz format), dictation silently falls back to the phone mic
instead of failing.

## Dictate with a physical keyboard hotkey

A single hardware key toggles dictation: press once to **start**, press again to
**complete** (finalize and insert) the current turn.

Open:

`WhisperType → Settings tab → Recording → Keyboard shortcut`

The default hotkey is the **grave / backtick key (`` ` ``)**. Available choices:
`Off`, `Grave key (`` ` ``)`, `F9`, `F10`, `F11`, `Scroll Lock`. The chosen key is
read by the accessibility service from disk, so a change in Settings applies
within ~2 seconds (the poll interval).

Notes:

- The hotkey works **without** the soft keyboard being visible — a physical-keyboard
  setup typically has no IME window, which is exactly the case this is built for.
- Security is unchanged: the hotkey never starts dictation into a password/secure
  or uncertain field, and those checks fail closed just as they do for the bubble.
- Only the configured key is intercepted; normal typing keys pass through untouched.
- The keyboard shortcut requires the Accessibility Service to be enabled (it is the
  only component that can observe hardware keys).

## Enable / disable dictation (App enabled)

`Settings → General → App enabled` is a genuine kill switch. Turning it **off**
fully stops the runtime: the bubble and overlay are removed, the "WhisperType is
listening" foreground notification and the system "displaying over other apps"
notification disappear, any in-progress dictation is aborted, and the runtime
service is terminated. The Accessibility Service stays registered (Android does
not let apps disable a system accessibility service) but becomes fully inert
until re-enabled. Turning it back **on** restarts the runtime and restores the
bubble. Leave it on for normal use; turn it off to completely stop WhisperType
without touching any permissions.

### Quick Settings tile

Android's Quick Settings panel can control the same kill switch (the tile icon
is the app logo):

1. Pull down the notification shade and tap the pencil/Edit button.
2. Find **WhisperType bubble** in the available tiles.
3. Drag it into the active Quick Settings area.
4. Tap the tile to turn the bubble on or off. The tile state and
   `Settings → General → App enabled` stay synchronized.

If overlay permission has not been granted, enabling the tile opens the system
overlay-permission screen instead of starting a bubble that cannot appear.

## Set up the custom dictionary

Open the `Dictionary` tab (bottom navigation).

- Enter the word exactly as you say it (Word as spoken).
- Optionally enter `Always write as` to force a specific spelling.
- Tap `Add` to save it.
- Delete a single entry, or `Clear all` to remove every entry.

Corrections are applied when the transcript is inserted, so the dictionary never rewrites the live transcript mid-session. Matching is word-boundary and case-insensitive.

## Reset the bubble position

If you dragged the mic bubble somewhere awkward, reset it:

`WhisperType → Settings tab → Bubble → Reset bubble position`

The bubble returns to its default position.

## Check the Home status grid

The Home tab shows a 2-column status grid of everything dictation needs. Any tile that reads Off can be tapped to `Fix` it:

- Overlay permission
- Runtime service
- Accessibility service
- Gemini API key
- Microphone permission
- Notifications

The Home tab also shows your dictation stats (Sessions, Words, Words today, Words this week, Words/min, Words/session), derived from local history.

## Daily use

1. Open an app such as Messages, Gmail, Chrome, Notes, or another supported application.
2. Tap inside a normal text field.
3. Wait for your normal keyboard to appear.
4. Tap the WhisperType bubble (or the mini dot it may have shrunk to).
5. A compact recording pill appears: Cancel (red X), a live waveform, and Done (green check).
6. Speak naturally.
7. Tap `Done` to commit the text, or the red X to cancel and discard.
8. Transient status pills appear while the result is prepared — `Finalizing`, then `Inserting` — before the text lands at the cursor.
9. WhisperType inserts the result at the current cursor or replaces the selected text; the keyboard stays visible throughout.
10. The bubble returns to its idle state.

## Cancel a dictation

You can cancel by:

- tapping the red X (`Cancel`) in the recording pill,
- tapping `Cancel` in the recording notification,
- locking the phone,
- switching to another app or field.

Cancellation discards the pending result and does not insert text.

## If direct insertion is unavailable

Some fields do not expose a safe editable connection.

Examples include:

- password fields,
- payment fields,
- secure PIN fields,
- private browsing fields,
- unusual WebViews,
- canvas-based editors,
- remote desktop applications,
- unsupported floating or split keyboards.

In these cases:

1. WhisperType finishes the transcription.
2. It does not insert into the field.
3. Tap `Copy`.
4. Return to the text field.
5. Paste using SwiftKey, Gboard, or Samsung Keyboard.

WhisperType never silently pastes into an uncertain field.

## Secure fields

WhisperType intentionally does not appear in:

- password inputs,
- PIN inputs,
- payment fields,
- banking authentication fields,
- secure login fields.

This protects sensitive information and avoids accidental recording.

## Optional history

History is on by default with a 30-day retention; transcript text is recorded only while it is enabled, and it can be turned off in the History tab.

To enable it:

1. Open the `History` tab (bottom navigation).
2. Turn on `Save dictation history`.
3. Choose a retention period with the slider (7-90 days).

When enabled, history is encrypted and stored locally. Audio is never stored.

The History tab shows the transcript list: copy an entry, delete a single entry, or delete all with the delete icon (a confirmation dialog appears first). Entries are automatically pruned once they are older than the retention period. Enabling history also powers the stats grid on the Home tab.

## Troubleshooting

### The bubble does not appear

Check:

1. WhisperType Accessibility Service is enabled.
2. Overlay permission is granted.
3. `Settings → General → App enabled` is on.
4. Microphone permission is enabled.
5. Gemini API key is saved.
6. The text field is not secure.
7. The keyboard is fully visible.
8. The keyboard is a supported docked keyboard.
9. Android has not stopped the Accessibility Service.

Since 0.5.2, the Home tab shows a **"Why is the bubble not showing?"** card that names
the exact blocking condition (for example, "Accessibility service is off") and, where
a fix exists, jumps you straight to it.

Reopen:

`Settings → Accessibility → Installed/Downloaded apps → WhisperType`

For more detail, see `docs/TROUBLESHOOTING.md`.

### The bubble disappears

The bubble hides when:

- the keyboard closes,
- the text field loses focus,
- the screen locks,
- an unsupported keyboard layout is detected,
- the current field is secure,
- the Accessibility Service disconnects,
- `Settings → General → App enabled` is turned off.

When idle, the bubble shrinking into a mini dot after a few seconds is expected behavior, not a fault.

### The recording pill does not start

Check:

1. Microphone permission.
2. Notification permission.
3. Gemini API key.
4. Network connection.
5. Whether another app is using the microphone.
6. Whether the recording notification appears.

### Text was copied instead of inserted

The application likely did not expose a reliable input connection. Tap `Copy`, return to the field, and paste manually.

### Samsung stops showing the bubble

Open:

`Settings → Accessibility → Installed apps → WhisperType`

Confirm the service remains enabled.

If the device has aggressive battery management, open:

`Settings → Apps → WhisperType → Battery`

Use the least restrictive setting that Samsung provides for the installed version. Do not disable battery protections unless WhisperType documentation specifically identifies the device as requiring it.

Android clears an app's accessibility service whenever the app is force-stopped
(swiped from the recents list, "Force stop" in `Settings → Apps`, or a battery
manager putting the app "to sleep"). To avoid the bubble disappearing:

- Do not force-stop WhisperType; lock it in the recents list so "Close all" skips it.
- Add WhisperType to `Settings → Battery → Background usage limits → Never sleeping apps`.
- If it already disappeared, turn the service back on under Accessibility, and confirm
  the disclosure dialog. WhisperType's Home card ("Why is the bubble not showing?") and
  its low-importance watchdog notification both point you here in one tap.

### Gemini connection fails

Check:

- API key is valid.
- The key has Gemini API access.
- The phone has internet access.
- Device date and time are correct.
- No VPN, firewall, or private DNS is blocking the connection.

WhisperType never displays or logs the full authenticated Gemini connection URL.

## Disable Accessibility access

### Pixel / stock Android

`Settings → Accessibility → Downloaded/Installed apps → WhisperType → Off`

### Samsung

`Settings → Accessibility → Installed apps → WhisperType → Off`

Disabling the service immediately hides the bubble and cancels any active recording.

## Delete the Gemini API key

Open:

`WhisperType → Settings tab → Gemini account → Clear key`

Then confirm deletion.

## Uninstall WhisperType

1. Open Android Settings.
2. Tap `Apps`.
3. Tap `WhisperType`.
4. Tap `Uninstall`.
5. Confirm.

Before uninstalling, optionally:

1. Disable Accessibility access.
2. Delete the Gemini API key.
3. Clear local history.
4. Revoke microphone and notification permissions.

## Privacy summary

WhisperType:

- records only after you tap the bubble,
- streams audio directly to Gemini Live,
- does not use a WhisperType cloud account,
- does not use a backend server,
- does not store audio,
- does not store history unless you enable it,
- does not read the clipboard,
- does not log complete transcripts,
- does not log API keys,
- does not operate in secure fields,
- requires Accessibility access only for field detection, keyboard positioning, and final text insertion.
