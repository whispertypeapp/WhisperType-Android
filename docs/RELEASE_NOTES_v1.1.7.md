# WhisperType v1.1.7 — GitHub Release body (paste-ready)

**Title:** `WhisperType v1.1.7 — OSS Android dictation (Gemini Live BYOK)`

**Tag:** `v1.1.7`  
**Assets to attach:** `WhisperType.apk`, `SHA256SUMS.txt`  
**Model:** `gemini-3.5-transcribe-live`  
**Package:** `com.whispertype.android`  
**minSdk:** 33 (Android 13+)  
**versionName:** 1.1.7 · **versionCode:** 61  
**Source commit (Status UI):** `d7404ee` ← prefer this in the public Release body  
**APK metadata commit (alternate):** `0e6c1508a99dbc8ae47e881c609feca70d5156a0` — footnote if it still differs from Status UI  

**Gates:** LICENSE/SPDX on repo · versionCode confirmed · GitHub URL from Kunal · no marketing until PL launch week  

---

## WhisperType v1.1.7

Free Wispr Flow alternative for Android — keep your keyboard, Gemini Live via your AI Studio key, OSS, $0. Tradeoff: audio goes to Google.

### What you get
- Floating mic / bubble — **keep your normal keyboard**; text inserts at the cursor  
- **Bring your own** Google AI Studio key (`gemini-3.5-transcribe-live`) — no WhisperType servers or subscription  
- **Hinglish** speech mode, **Dictionary** (spoken → written), **History** (30-day default in UI), Grave (`` ` ``) shortcut  
- Dark UI v2 (Home / History / Dictionary / Settings) · About **1.1.7**

### Install
1. Download `WhisperType.apk` below (or build from source).  
2. Allow install from unknown sources if needed.  
3. Grant mic, overlay, accessibility, and notifications as prompted.  
4. Paste your AI Studio API key in Settings, then dictate into any field.

### Honest limits (Gemini)
- AI Studio free tier is **rate-limited** (RPM/TPM/RPD) — not unlimited  
- Long sessions may hit Google’s continuous-session caps (~10 minutes class)  
- Review Google’s AI Studio terms (free-tier data use). Enable billing on Google’s side for higher quotas  

### Requirements
- Android 13+ (API 33)  
- Microphone + network  
- Google AI Studio API key  

### Artifacts
- `WhisperType.apk`  
- SHA256: `f7a55069bddcc6a24baad469d5df5b0a76a757bdfa34af375dcbe73b3f9f99df`  
- Source commit (Status UI): `d7404ee`  
- APK metadata commit (if different): `0e6c1508a99dbc8ae47e881c609feca70d5156a0`  
- versionCode: **61**  

### Demo
- Hero clip: `demos-v2/demo-02.mp4` (attach optionally)

### Changelog
- Shipping dark **UI v2** (Home, History, Dictionary, Settings)  
- BYOK Gemini Live only (no on-device / dual-mode path)  
- Hinglish · Dictionary · Grave shortcut · bubble controls · on-device key storage · 30-day history  

---

*Do not publish marketing posts until Product Launch calls launch week — Release cut is OK once GitHub URL exists, LICENSE is set, versionCode is confirmed, and Kunal/PL confirm.*
