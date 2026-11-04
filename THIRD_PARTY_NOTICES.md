# Third-party notices for Rustle

This file lists everything in Rustle that this project did not author
itself, together with the license each component carries.

**Relationship to `LICENSE`:** the CC-BY-NC-ND-4.0 grant in `LICENSE`
applies **only** to Rustle's own source code, configuration and assets
created for this project. It does **not** apply to, and cannot be
construed to restrict, any third-party component listed below, which
remains under the license its upstream author granted it. For
end users, the same information (including the required OpenRAIL-M
full text and the CC BY attributions) is presented inside the app on
the **Licenses & AI Disclosure** screen
(`app/src/main/java/com/chint/audiobook/ui/settings/LicensesScreen.kt`).

Verbatim license texts bundled with the app live in
`app/src/main/res/raw/`:

| Resource | License |
|---|---|
| `license_apache_2.txt` | Apache-2.0 (sherpa-onnx, Kokoro-82M weights, AndroidX stack) |
| `license_openrail_m.txt` | BigScience OpenRAIL-M including Attachment A (Supertonic-3 weights) |
| `license_mit_onnxruntime.txt` | MIT (ONNX Runtime) |

---

## 1. Components bundled into the APK

| Component | Version | License | Origin | Notes |
|---|---|---|---|---|
| sherpa-onnx (AAR) | 2.0.1 | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx | Shipped as `app/libs/sherpa-onnx-2.0.1.aar`. Used for on-device inference (`OfflineTts`). |
| ONNX Runtime | (statically inside the sherpa-onnx AAR) | MIT | https://github.com/microsoft/onnxruntime | Microsoft's neural-network runtime, linked into the sherpa-onnx native code. |

## 2. Model packs downloaded at runtime from Hugging Face

These are **not** stored in this repository; Rustle downloads them once
to app storage with SHA-256 verification, and they remain under their
upstream licenses.

### Supertonic-3 speech model — BigScience OpenRAIL-M

- Export used at runtime: `csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11`
- Original model: `supertone-oss-archive/supertonic-3` (Supertone Inc.)
- License: **BigScience OpenRAIL-M** (code of the original Supertone
  repository is MIT; the open model *weights* distributed here are
  OpenRAIL-M).
- The **full OpenRAIL-M text, including Attachment A use restrictions,
  is passed on to every end user** via the in-app Licenses screen and
  bundled verbatim in `res/raw/license_openrail_m.txt`. The Attachment A
  restrictions travel with the weights: anyone who redistributes or uses
  the model through Rustle inherits them.

### Kokoro-82M v1.0 — Apache-2.0 weights with CC BY data attributions

- Export used at runtime: `csukuangfj/kokoro-multi-lang-v1_0`,
  pinned at revision
  `f7b96bb6bef5c5da4d3aa4f4e0498fbbf62dc78b`; files `model.onnx`,
  `voices.bin`, `tokens.txt`, `lexicon-us-en.txt`.
- Weights license: **Apache-2.0** — hexgrad, https://huggingface.co/hexgrad/Kokoro-82M
- The Kokoro model card discloses third-party CC BY audio in the
  training set, which carries an attribution duty and is credited in the
  app and here:

| Training data | Held-out role | License | Attribution |
|---|---|---|---|
| [Koniwa `tnc`](https://github.com/koniwa/koniwa) (Japanese tone recordings) | < 1 h | CC BY 3.0 | © Koniwa contributors — https://github.com/koniwa/koniwa |
| [SIWIS Audio](https://datashare.ed.ac.uk/handle/10283/2353) (French audio) | < 11 h | CC BY 4.0 | © SIWIS / University of Edinburgh — https://datashare.ed.ac.uk/handle/10283/2353 |

- **misaki G2P frontend** (Apache-2.0, hexgrad) is used upstream of the
  lexicon route and remains Apache-2.0; nothing from it is re-distributed
  here.

## 3. Gradle dependencies (Apache-2.0 unless stated)

Resolved from `gradle/libs.versions.toml` / `app/build.gradle.kts`:

| Group | Components | License |
|---|---|---|
| AndroidX / Google | core-ktx, activity-compose, lifecycle (incl. `runtime`, `viewmodel-compose`, `runtime-compose`), Jetpack Compose (BOM 2026.03.01: ui, material3, foundation, material-icons-core), navigation3, datastore-preferences, media3 (exoplayer, session, ui), test/espresso/espresso-ext | Apache-2.0 (Google LLC) |
| JetBrains | Kotlin 2.3.20 (incl. compose compiler plugin), kotlinx-serialization-json 1.8.0, kotlinx-coroutines(-test) 1.10.2 | Apache-2.0 |
| Square | OkHttp 4.12.0 | Apache-2.0 |
| Firebase | firebase-bom 34.0.0 (analytics, crashlytics), google-services plugin 4.4.2, crashlytics plugin 3.0.2 | Apache-2.0 (Google LLC) |
| Dagger / KSP | Hilt 2.60.1 (+ compiler), KSP 2.3.13 | Apache-2.0 |
| Android | Android Gradle Plugin 9.0.1 | Apache-2.0 (build-time only, not shipped) |
| JUnit | junit 4.13.2 | **EPL-1.0** — test/classpath only, never included in the released APK |

## 4. Explicitly *not* embedded

- **espeak-ng and piper-phonemize (GPL-3.0)** are *not* embedded and may
  not be added while this notice stands: sherpa-onnx ≥ 2.0 switched to
  the lexicon / external-tokens route precisely to stay Apache-compatible
  (see `docs/KOKORO.md` §6.1). Rustle uses that lexicon route
  (`lexicon-us-en.txt`) and downloads no espeak-ng data.
- No code from the Supertone `supertonic` PyPI package or the hexgrad
  `kokoro` Python repository is vendored into this project; only
  runtime-downloaded model files at their upstream licenses.

## 5. Notice / attribution pass-through

For all Apache-2.0 components this file serves as the distributed NOTICE
per Apache-2.0 §4; the MIT notice is bundled verbatim; the CC BY
attribution duty is satisfied by the Koniwa/SIWIS credit above and in
the app's Licenses screen. Erasing these credits or applying the
project's CC-BY-NC-ND-4.0 grant to these components would violate their
upstream licenses.
