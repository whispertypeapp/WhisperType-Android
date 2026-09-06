## WhisperType v1.0.0

Free open-source Android dictation — keep your keyboard, Gemini Live via your own Google AI Studio key. Tradeoff: audio goes to Google under your key. No WhisperType servers or app subscription.

### What you get
- Floating mic / bubble — **keep your normal keyboard**; text inserts at the cursor
- **Bring your own** Google AI Studio key — no WhisperType cloud account
- English + **Hinglish** speech modes, **Dictionary** (spoken → written), on-device history
- Dark UI (Home / History / Dictionary / Settings)

### Install
1. Download `WhisperType-Android-1.0.0.apk` below (or build from source).
2. Allow install from unknown sources if needed.
3. Grant mic, overlay, accessibility, and notifications as prompted.
4. Paste your AI Studio API key in Settings, then dictate into any field.

### Honest limits (Gemini)
- AI Studio free tier is **rate-limited** — not unlimited
- Long sessions may hit Google’s continuous-session caps
- Review Google’s AI Studio terms. Enable billing on Google’s side for higher quotas

### Requirements
- Android 13+ (API 33)
- Microphone + network
- Google AI Studio API key

### Artifacts
- `WhisperType-Android-1.0.0.apk`
- See `SHA256SUMS.txt` for the checksum

### Changelog
- Initial public release
- Keep-your-keyboard overlay dictation
- BYOK Gemini Live (no on-device STT path)
- Hinglish · Dictionary · on-device key storage
