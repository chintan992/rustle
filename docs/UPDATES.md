# Publishing Android updates

The in-app updater reads the latest stable release from
`https://github.com/chintan992/rustle/releases/latest`. Publish a GitHub Release
with one signed APK for each supported ABI and a `rustle-update.json` file.
Draft and prerelease releases are not used by the updater.

## Keep the app updateable

- Keep the application ID `dev.chintan992.rustle`.
- Increase `versionCode` for every published build. It must always be greater
  than the versionCode of every build users may have installed.
- Use a numeric `versionName` with two or three dot-separated parts, such as
  `1.0.1`.
- Sign every GitHub APK with the same stable release certificate. Do not
  distribute an unsigned APK or change the signing key.
- Do not instruct users to uninstall before installing an update. Android
  installs a compatible APK in place, retaining app-private books, settings,
  and progress.

The repository's release signing configuration is optional for local/CI
builds. Before publishing, verify that the release APKs are signed with the
intended key and are not debuggable.

## Build and name APKs

Set the new `versionCode` and `versionName` in `app/build.gradle.kts`, then run:

```powershell
.\gradlew.bat :app:assembleRelease
```

ABI splits are configured for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`.
Rename each generated APK to this exact pattern before uploading:

```text
Rustle-<abi>-<versionName>.apk
```

For version `1.0.1`, examples are `Rustle-arm64-v8a-1.0.1.apk` and
`Rustle-x86_64-1.0.1.apk`. Upload only APKs for ABIs for which the release
actually contains compatible native libraries.

## Update manifest

Upload `rustle-update.json` alongside the APKs. It must list the exact APK
filenames and their SHA-256 checksums:

```json
{
  "versionCode": 2,
  "versionName": "1.0.1",
  "assets": {
    "arm64-v8a": {
      "name": "Rustle-arm64-v8a-1.0.1.apk",
      "sha256": "<64-character lowercase SHA-256 hex>"
    },
    "x86_64": {
      "name": "Rustle-x86_64-1.0.1.apk",
      "sha256": "<64-character lowercase SHA-256 hex>"
    }
  }
}
```

Compute each checksum from the final APK after signing and renaming. The
manifest's versionCode/versionName must match the APKs. Upload the manifest and
all referenced APKs to the same GitHub Release.

## Play Store distribution

Build the Play upload artifact as an Android App Bundle (AAB):

```powershell
.\gradlew.bat :app:bundleRelease
```

The bundle is written to
`app/build/outputs/bundle/release/app-release.aab`. Verify that it is signed
with the intended upload key before submitting it in Play Console. Keep the
upload key consistent; Play App Signing manages the distribution signing key.
Use Play's internal or closed testing track before production rollout.

The GitHub updater and Play Store are separate distribution channels. Route
Play-installed builds to the Play Store update flow rather than downloading a
GitHub APK. A build installed from GitHub can only be updated in place by a
Play build if the signing certificates are compatible; otherwise users must
remain on the GitHub channel or follow a separately designed migration path.
Check Google Play's installer/signing policies before enabling GitHub APK
installation in a Play-distributed variant.
