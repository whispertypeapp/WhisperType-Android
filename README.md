<p align="center">
  <img src="app-logo.png" width="132" height="132" alt="WhisperType App Logo" style="border-radius: 28px;" />
</p>

<h1 align="center">WhisperType</h1>

<p align="center">
  <b>Fast, Private, System-Wide AI Voice-to-Text for Android</b><br>
  <i>Powered by Google Gemini Live (<code>gemini-3.5-transcribe-live</code>)</i>
</p>

<p align="center">
  <a href="https://github.com/chaosmanage/WhisperType-Android/releases/latest"><img src="https://img.shields.io/github/v/release/chaosmanage/WhisperType-Android?label=Release&color=07695D&style=flat-square" alt="Latest Release"></a>
  <img src="https://img.shields.io/badge/Platform-Android%2013%2B%20(API%2033%2B)-0E8388?style=flat-square" alt="Android 13+">
  <img src="https://img.shields.io/badge/Model-gemini--3.5--transcribe--live-4285F4?style=flat-square" alt="Gemini Live">
  <img src="https://img.shields.io/badge/Privacy-100%25%20Local--First-2EA043?style=flat-square" alt="Local First">
</p>

<p align="center">
  <a href="https://github.com/chaosmanage/WhisperType-Android/releases/latest/download/app-release.apk">
    <img src="https://img.shields.io/badge/📥_Download_Release_APK-v1.2.3-07695D?style=for-the-badge&logo=android&logoColor=white" alt="Download APK">
  </a>
</p>

<p align="center">
  <img src="whispertype-banner.jpg" alt="WhisperType Showcase Banner" width="100%" style="border-radius: 16px;" />
</p>

---

Voice to text for Android that keeps your favorite keyboard. WhisperType floats an elegant, draggable microphone bubble above any text field—in Messages, WhatsApp, Gmail, Slack, Notes, browser, or any Android app—and streams your speech directly to Google's Gemini Live API for instant, structured cursor insertion.

> **Private by design:** WhisperType is 100% local-first. Your Gemini API key is encrypted on-device with an Android Keystore AES-GCM-256 key. Transcripts and audio are never logged or sent to any third-party server. There is no WhisperType account, no analytics, and no tracking.

---

## ⚡ How It Works

```mermaid
flowchart LR
    A["📱 Any App<br/>(Focused Text Field)"] --> B["🟢 Mic Bubble<br/>(Tap to Speak)"]
    B --> C["🎙️ Real-time Audio<br/>(16 kHz PCM stream)"]
    C --> D["⚡ Gemini Live Engine<br/>(gemini-3.5-transcribe-live)"]
    D --> E["✨ Shaping Mode<br/>(Smart or Verbatim)"]
    E --> F["✍️ Cursor Insertion<br/>(Accessibility Service)"]
```

1. **Focus any text field** — the floating WhisperType bubble appears above your keyboard.
2. **Tap the bubble (or mini-dot)** — speak naturally as the real-time waveform displays your voice activity.
3. **Finish speaking** — tap Done `✓` (or pause for auto-stop); your shaped transcription is committed directly at the cursor.

---

## 🌟 Highlights

### 🎯 Floating Mic Bubble & Mini-Dot
- Freely draggable circular bubble floats above your keyboard on any app.
- Auto-minimizes to an unobtrusive mini-dot after inactivity (configurable delay).
- Bubble size (12–72 dp) and opacity are fully customizable in Settings.

### 🌊 Recording Capsule & Live Waveform
- An elegant floating pill displays **[Cancel ✕] [Real-Time Waveform] [Done ✓]**.
- Visual audio feedback is responsive and dynamic, adapting to both soft whispers and normal speaking levels.
- Anchored layout ensures the Done checkmark lands right where you tapped to start.

### 🧠 Smart vs. Verbatim Transcription
Choose between two distinct transcription shaping modes:

| Mode | Shaping & Output | Best For |
| :--- | :--- | :--- |
| **Smart** *(Default)* | Server-side shaping: eliminates filler words (*"um"*, *"uh"*), resolves self-corrections, and formats grammar, punctuation, and casing. | Everyday messaging, emails, notes, drafting |
| **Verbatim** | Word-for-word literal transcription preserving all spoken repetitions, filler words, and pauses. | Exact quotes, transcription verification, dictation tests |

### 🔄 In-App & OTA GitHub Updates
- Automatically queries public GitHub releases on app startup.
- Displays an update banner on the Home tab and posts an Android notification when a new version is published.
- Tapping triggers automatic APK download in your default browser.

### 🎧 Bluetooth Headset & Hardware Hotkey Support
- Switch recording source to a connected Bluetooth headset microphone.
- Map a physical keyboard key (e.g. grave/backtick `` ` ``) to start and complete dictations hands-free on physical keyboards or tablets (Samsung DeX supported).

### 📖 Custom Dictionary
- Define custom pronunciation and replacement rules (e.g., spoken technical terms, slang, or names) that automatically substitute on text insertion.

### 🔒 Encrypted History
- Review past dictation history with word counts and timestamps.
- Encrypted locally with Android Keystore AES-GCM; configurable auto-retention (7, 14, 30, or 90 days).

---

## 🏗️ Architecture

WhisperType runs across two isolated processes for security, stability, and speed:

```mermaid
graph TD
    subgraph MainProcess["Main Process (:app) — Foreground Service"]
        Overlay["Persistent Window Overlay<br/>(Draggable Bubble & Recording Pill)"]
        Audio["AudioCapture Pipeline<br/>(16 kHz PCM16 Stream)"]
        Gemini["OkHttp Gemini Live Session<br/>(BidiGenerateContent WebSocket)"]
        Coord["DictationCoordinator<br/>(State Machine, Settlement & Tail Backstop)"]
    end
    subgraph A11yProcess[":accessibility Process"]
        Tracker["EditorTracker<br/>(Focus, Bounds & Window Detection)"]
        Gate["SecurityClassifier<br/>(Secure / Password / PIN Exclusion)"]
        Insert["AccessibilityTargetGateway<br/>(Validated Cursor commitText)"]
    end
    MainProcess <== "Typed Messenger IPC (No text crosses process boundary)" ==> A11yProcess
```

- **Main Process** (`platform/runtime/FlowRuntimeService.kt`): Owns the foreground service, the overlay composition, mic capture, and the Gemini Live WebSocket session.
- **Accessibility Process** (`platform/accessibility/WhisperTypeAccessibilityService.kt`): Tracks cursor focus, guarantees secure field exclusion (passwords/PINs never show the bubble), and inserts validated text.

---

## 🚀 Quick Start

1. **Install the App**:
   Download the latest signed release APK from [GitHub Releases](https://github.com/chaosmanage/WhisperType-Android/releases/latest) and install it on your device.
2. **Grant Permissions**:
   Allow **Overlay**, **Microphone**, and optional **Notification** permissions when prompted.
3. **Enable Accessibility Service**:
   Navigate to **Android Settings → Accessibility → WhisperType** and toggle it **ON**.
4. **Configure Gemini API Key**:
   Open WhisperType → **Settings → Gemini account** → paste your Google Gemini API key. The key is encrypted in your device's Keystore.
5. **Start Dictating**:
   Open any app (Messages, Notes, Slack, etc.), focus a text field, tap the floating bubble, and speak!

---

## 🛡️ Privacy & Security

- **Encrypted Secrets**: Your API key is encrypted using hardware-backed Android Keystore with AES-GCM-256. It is never logged or exposed.
- **Zero Cloud Infrastructure**: Audio streams strictly from your device directly to Google's official Gemini Live endpoint over TLS.
- **Password Exclusion**: Password fields, PIN fields, and `FLAG_SECURE` screens are explicitly blocked. The bubble never displays over sensitive fields.
- **No Background Recording**: The microphone is strictly activated when you tap the bubble and deactivates immediately when dictation completes.

---

## 📚 Documentation Hub

| Guide | Description |
| :--- | :--- |
| [`docs/USER_SETUP.md`](docs/USER_SETUP.md) | Comprehensive step-by-step setup guide and onboarding walkthrough |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Two-process architecture, IPC contracts, and module breakdown |
| [`docs/GEMINI_LIVE.md`](docs/GEMINI_LIVE.md) | Gemini Live protocol mechanics, settlement, and wire reference |
| [`docs/RELEASE_PROCESS.md`](docs/RELEASE_PROCESS.md) | Versioning, signing, and GitHub release deployment protocol |
| [`docs/SECURITY_AND_PRIVACY.md`](docs/SECURITY_AND_PRIVACY.md) | Threat model, Keystore encryption, and privacy rules |
| [`docs/TESTING.md`](docs/TESTING.md) | Test suite breakdown, device matrix, and manual acceptance testing |
| [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) | Diagnosing common overlay, accessibility, or network issues |
| [`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md) | Developer workflow, code standards, and PR requirements |
| [`docs/PUSH_TO_PHONE_VIA_ADB.md`](docs/PUSH_TO_PHONE_VIA_ADB.md) | Wireless ADB deployment over Tailscale |
| [`CHANGELOG.md`](CHANGELOG.md) | Full version release history |

---

## 🛠️ Build & Development

Requires JDK 17 and Android SDK Platform 36.

```bash
# Run JVM unit tests
./gradlew :app:testDebugUnitTest

# Run code analysis (warnings are errors)
./gradlew :app:lintDebug

# Assemble debug APK
./gradlew :app:assembleDebug

# Assemble signed release APK
./gradlew :app:assembleRelease
```

---

## 📱 Supported Environment & Requirements

- **Operating System**: Android 13+ (`minSdk 33`, `targetSdk 36`).
- **Supported Keyboards**: Gboard, Samsung Keyboard, Microsoft SwiftKey (standard docked mode).
- **Service Requirements**: Android Accessibility Service enabled; overlay permission granted.
- **Current Release**: **v1.2.3** (`versionCode = 69`).
