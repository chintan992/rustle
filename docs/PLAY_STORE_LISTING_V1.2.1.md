# Rustle 1.2.1 — Play Store release

**Version name:** `1.2.1`

**Version code:** `5`

**What's new:**

- Separate Play Store build with Google Play-managed updates.
- The Play build no longer requests permission to install packages.
- Guided first-run setup for notification choice, speech model download and
  initialization, and default narrator selection.

## Upload

Build and upload the signed Play App Bundle:

```powershell
.\gradlew.bat :app:bundlePlayRelease
```

The signed bundle is
`app/build/outputs/bundle/playRelease/app-play-release.aab`. Do not upload the
`githubRelease` APKs to Play Console.

Keep the public privacy policy URL configured:
<https://chintan992.github.io/rustle/privacy-policy/>

Complete or verify the Data safety, content-rating, target-audience, and
app-access declarations in Play Console before submission. Confirm the Play
upload key and Play App Signing configuration in the account; never upload or
commit keystore/password files.
