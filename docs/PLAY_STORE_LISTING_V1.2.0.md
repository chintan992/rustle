# Rustle 1.2.0 — Play Store listing

**Title:** Rustle: EPUB Audiobooks

**Short description:** Turn your EPUBs into private, offline audiobooks with read-along narration.

**What's new:**

- A guided first-run setup: notification choice, speech model download and
  initialization, then default narrator selection.
- Model setup can resume after the app closes. Notification permission can be
  declined without blocking setup.

## Play Console checklist

- Version name: `1.2.0`
- Version code: `4`
- Upload the signed App Bundle at
  `app/build/outputs/bundle/release/app-release.aab` to an internal or closed
  testing track.
- Keep the public privacy policy URL configured:
  <https://chintan992.github.io/rustle/privacy-policy/>
- Complete or verify the Data safety, content-rating, target-audience, and
  app-access declarations in Play Console before submission.
- Verify Firebase SDK data practices against the current Data safety
  questionnaire.
- Confirm the Play upload key and Play App Signing configuration in the
  account; never upload or commit keystore/password files.
