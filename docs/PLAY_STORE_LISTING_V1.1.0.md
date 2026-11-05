# Rustle 1.1.0 — Play Store listing

**Title:** Rustle: EPUB Audiobooks

**Short description:** Turn your EPUBs into private, offline audiobooks with read-along narration.

**Full description:**

Turn the books you already have into audiobooks with Rustle. Import an EPUB,
plain-text, or Markdown document and listen to on-device narration while the
reader follows along with sentence highlighting.

Rustle is designed for personal libraries. Your imported books, extracted
text, generated audio, and playback progress stay in app-private storage on
your device. Download a voice model over the internet; after setup, narration
runs on your device and works offline.

FEATURES

- Import EPUB, plain-text, and Markdown files.
- Listen to on-device neural narration and follow sentence highlighting.
- Choose multilingual Supertonic voices or optionally download the
  English-focused Kokoro voice pack.
- Use bookmarks, a sleep timer, per-book playback settings, and listening
  goals.
- Keep listening while using other apps with background playback controls.
- Opt in to occasional Rustle announcements; manage this anytime in Settings.

Rustle requires Android 7.0 or later. Voice model downloads require an internet
connection and additional device storage. Rustle also uses Firebase Analytics
and Crashlytics for app interaction analytics and crash diagnostics. See the
in-app Licenses & AI Disclosure screen for details.

**What's new:**

- Optional Rustle announcement notifications, with a first-launch opt-in and
  Android notification permission prompt.
- Manage announcement preferences in Settings.

## Store assets

The local asset package contains the 512 × 512 app icon, 1024 × 500 feature
graphic, and phone screenshots. Screenshots are normalized to 1080 × 2160 PNG
with no alpha channel to satisfy Play listing image constraints.

## Play Console checklist

- Upload `app/build/outputs/bundle/release/app-release.aab` to an internal or
  closed testing track.
- Add `https://chintan992.github.io/rustle/privacy-policy/` as the app's public
  privacy-policy URL and complete the Data safety,
  content-rating, target-audience, and app-access declarations in Play Console.
- The policy-link app update is version 1.1.1 (versionCode 3); upload its
  signed AAB rather than the earlier 1.1.0 bundle to satisfy the in-app link
  requirement.
- Verify the actual Firebase SDK data practices and disclosures against the
  current Data safety questionnaire before submitting.
- Confirm the Play upload key and Play App Signing configuration in the
  account; never upload or commit keystore/password files.
