# GTA VI Live Translator · مترجم GTA VI

Android companion app that translates and explains Grand Theft Auto VI dialogue **live**, for players on PS5 / Xbox:

- **Camera mode** – point the phone at the TV. On-device OCR (ML Kit) reads the subtitle region you choose, detects each new line, and Gemini translates + explains it (slang, sarcasm, cultural references).
- **Snap & explain** – one tap sends the current frame to Gemini Vision when the OCR struggles (glare, small text).
- **Listen mode** – the phone mic picks up game audio; an adaptive voice detector cuts speech segments and Gemini transcribes, translates and explains them (music and song lyrics are ignored).
- **Type mode** – paste or type any line or word.
- **Explain more** on any card for a deeper breakdown.

Output in Libyan Arabic (default) or Modern Standard Arabic, short or detailed.

## Free to use
The app uses your own free Gemini API key from [Google AI Studio](https://aistudio.google.com/apikey) (no credit card). The key is stored encrypted with the Android Keystore and only sent to Google.

Free-tier friendly by design: a single request queue with a configurable requests-per-minute limit, consecutive subtitles merged into one request, stale live input dropped, automatic back-off on HTTP 429, and on-device OCR so only *new* lines are sent.

## Install
Download the latest APK from **Releases** and install it (allow "Install unknown apps" for your browser/files app).

## Tips
- Turn on subtitles in the game and make them large.
- Drag the pink box onto the subtitle area; use the corner dots to resize; use zoom if the phone is far away.
- In listen mode, lower the in-game music volume and raise dialogue volume.
- If you hit the daily free quota, switch the model to a `flash-lite` variant in Settings.

## Build
Every push to `main` builds a signed release APK with GitHub Actions and publishes it as a GitHub Release.

Local build: `./gradlew assembleRelease` (JDK 17, Android SDK 35). For release signing, set `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` or create `keystore.properties` (git-ignored).

## Stack
Kotlin · Jetpack Compose (Material 3) · CameraX + ML Kit Text Recognition · AudioRecord with adaptive VAD · OkHttp · Gemini REST API (structured JSON output).

---
Unofficial fan tool, not affiliated with Rockstar Games or Take-Two Interactive.
