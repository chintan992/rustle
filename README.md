# Rustle

<p align="center">
  <img src="./logo.svg" alt="Rustle logo" width="180">
</p>

<p align="center">
  <strong>Turn the books you already have into audiobooks.</strong><br>
  Read along, listen offline, and keep your library on your device.
</p>

<p align="center">
  <a href="https://github.com/chintan992/rustle/releases/latest">Download for Android</a>
  &nbsp;·&nbsp;
  <a href="https://github.com/chintan992/rustle/issues">Report a problem</a>
</p>

## About

Rustle is an Android audiobook reader that turns EPUBs and text documents into
spoken books using neural text-to-speech on your device. Import a book, choose
a voice, and listen while the reader follows along with the narrated text.

Rustle is designed for personal libraries: your imported books, extracted
text, generated audio, and playback progress are kept in app-private storage
on your device. There is no account to create, and book content is not
uploaded. Voice-model files are downloaded when you choose to install a
voice pack; narration itself runs locally.

## Highlights

### Bring your own books

- Import EPUB books, plain-text files (`.txt`), and Markdown files (`.md`,
  `.markdown`).
- Paste text or share supported content from another Android app.
- Rustle extracts and organizes content into chapters for reading and
  narration. Markdown formatting is cleaned up so the narrator reads the
  document rather than its markup.

### Listen and read along

- Follow the narration in the reader with sentence-level highlighting.
- Use playback controls and keep your place between listening sessions.
- Adjust voice and playback preferences for an individual book.
- Add bookmarks and use a sleep timer, including stopping at a chapter
  boundary.
- Continue listening while using other apps with Android background playback
  controls.

### Choose an on-device voice

- **Supertonic 3** is the primary multilingual voice pack, with selectable
  voice styles. Its compressed pack is approximately 138 MB.
- **Kokoro-82M** is an optional, English-focused voice pack.
- The Kokoro pack is approximately 360 MB and can be installed separately.
- Voice packs are downloaded separately and verified before use. They require
  additional storage; once installed, synthesis runs on-device and works
  without a network connection.

### Keep track of listening

- Review listening time for the week and your consecutive-day listening
  streak.
- Set an optional yearly listening-time goal.
- Stats start accumulating when listening is recorded by the app; they do not
  reconstruct earlier listening history.

## Getting started

1. Install Rustle from [GitHub Releases](https://github.com/chintan992/rustle/releases/latest).
2. On first launch, choose whether to allow Rustle announcement notifications
   (Android 13+). On older Android versions, announcements start enabled and
   can be disabled in Rustle Settings.
3. Download the Supertonic voice pack (about 138 MB). Connect to Wi-Fi if you
   want to avoid using mobile data.
4. Select your default narrator, then import an EPUB, TXT, or Markdown file.
5. Open the book, choose playback settings, and press Play. Narration works
   offline after the voice pack is installed.

Rustle requires Android 7.0 (API 24) or later. APKs are provided for
`arm64-v8a` (most modern Android devices), `armeabi-v7a` (32-bit ARM),
`x86_64`, and `x86` (primarily emulators). Select the APK matching your
device's CPU architecture.

## Build from source

### Requirements

- Android Studio with Android SDK Platform 36 and Build Tools 36
- JDK 17
- Windows PowerShell for the commands below (the Gradle wrapper is also
  available for other platforms)

Clone the repository, open it in Android Studio, or build from the project
root:

```powershell
git clone https://github.com/chintan992/rustle.git
cd rustle
.\gradlew.bat :app:assembleDebug
```

Run the local unit tests with:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Debug APKs are written to `app/build/outputs/apk/debug/`. To build release
artifacts, configure the local signing properties described in
[docs/UPDATES.md](docs/UPDATES.md). Build GitHub APKs with:

```powershell
.\gradlew.bat :app:assembleGithubRelease
```

Build the Play Store App Bundle with `.\gradlew.bat :app:bundlePlayRelease`.
Only the Play flavor omits the GitHub updater and the
`REQUEST_INSTALL_PACKAGES` permission. Never publish an unsigned release build
or replace the release signing key. Android uses the signing certificate to
determine whether an installed app can be updated in place.

## Project structure

- `app/src/main/java/` — Kotlin application, UI, playback, import pipeline,
  model management, and update flow.
- `app/src/main/res/` — Android resources, icons, and bundled license texts.
- `app/src/test/` — JVM unit tests for the document pipeline, playback, model
  management, repositories, and other app behavior.
- `docs/` — model notes and release/update publishing instructions.

Rustle is a native Android application built with Kotlin and Jetpack Compose.
On-device speech synthesis uses sherpa-onnx and ONNX Runtime.

## Privacy and network use

Books, extracted text, generated audio, and playback progress are stored in
app-private storage. Book content is not uploaded. An internet connection is
used to download model packs and check for app updates. Rustle also uses
Firebase Analytics and Crashlytics for app interaction analytics and crash
diagnostics. Announcement notifications use Firebase Cloud Messaging (FCM);
new installs are prompted after voice setup to enable them.
See the [Privacy Policy](https://chintan992.github.io/rustle/privacy-policy/)
and the in-app **Licenses & AI Disclosure** screen for details about data use.

### Sending announcements

The Android client subscribes opted-in devices to the FCM topic
`general-announcements`. Announcements can be sent from Firebase Console or a
trusted server using the Firebase Admin SDK. Never place service-account
credentials or FCM server credentials in the Android app. Local builds also need a valid
`google-services.json` for the Firebase project associated with the app's
`dev.chintan992.rustle` application ID; the committed placeholder is not a
live FCM configuration.

Users can enable or disable announcements from **Settings**. Android 13 and
later also requires the system notification permission, which users can decline;
the rest of Rustle remains available. FCM auto-initialization is off until the
user enables announcements; opting out unsubscribes the device and disables it
again. Notifications received while Rustle is open are displayed by the app;
background notification messages are handled by Firebase. Tapping an
announcement opens Rustle.

## Licensing

Rustle's original source code and project assets are licensed under
CC-BY-NC-ND-4.0; see [LICENSE](LICENSE). Third-party software and voice models
have their own licenses and notices, documented in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and in the app's
**Licenses & AI Disclosure** screen.

## Help and links

- [Releases](https://github.com/chintan992/rustle/releases)
- [Report an issue](https://github.com/chintan992/rustle/issues)
- [Update publishing guide](docs/UPDATES.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)
