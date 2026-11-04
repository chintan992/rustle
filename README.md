# Rustle

Rustle turns your ebooks and text documents into audiobooks with on-device
text-to-speech. Listen while following along with the reader, and keep your
library and playback progress on your device.

## Features

- Import EPUB, plain-text (`.txt`), and Markdown (`.md`, `.markdown`) files.
- Listen with on-device neural voices. Narration works offline after the
  selected voice model has been downloaded.
- Follow along in the reader with sentence highlighting and playback controls.
- Choose between the Supertonic voice pack and the optional Kokoro English
  voice pack.
- Set per-book playback preferences, bookmark your place, and use the sleep
  timer.
- Track listening time, weekly streaks, and an optional yearly listening goal.
- Import books from your device or share a supported document to Rustle.

## Install

Download the APK for your device's CPU architecture from
[GitHub Releases](https://github.com/chintan992/rustle/releases/latest):

- `arm64-v8a` — most modern Android phones and tablets
- `armeabi-v7a` — 32-bit ARM devices
- `x86_64` and `x86` — compatible emulators and devices

Rustle requires Android 7.0 (API 24) or later. On first launch, choose a voice
and download its model pack while connected to the internet. Model files are
large; the optional Kokoro pack requires additional storage. After setup,
speech generation runs on-device and does not require a network connection.

## Build from source

Requirements:

- Android Studio with Android SDK Platform 36 and Build Tools 36
- JDK 17

On Windows, build and run the unit tests from the project root:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

The debug APKs are written under `app/build/outputs/apk/debug/`. To build
release APKs, configure the local signing properties described in
[docs/UPDATES.md](docs/UPDATES.md), then run:

```powershell
.\gradlew.bat :app:assembleRelease
```

Do not publish unsigned release APKs or change the release signing key: Android
updates depend on a compatible signing certificate.

## Privacy and network use

Books, extracted text, generated audio, and playback progress are stored in
app-private storage. Book content is not uploaded. An internet connection is
used to download model packs and check for app updates. The app also uses
Firebase Analytics and Crashlytics for app interaction analytics and crash
diagnostics; see the in-app **Licenses & AI Disclosure** screen for details.

## Licensing

Rustle's original source code and project assets are licensed under
CC-BY-NC-ND-4.0; see [LICENSE](LICENSE). Third-party software and voice models
have their own licenses and notices, documented in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and in the app's
**Licenses & AI Disclosure** screen.

## Links

- [Releases](https://github.com/chintan992/rustle/releases)
- [Report an issue](https://github.com/chintan992/rustle/issues)
- [Update publishing guide](docs/UPDATES.md)
