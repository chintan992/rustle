# Meta MMS VITS — Gujarati engine — research deep dive + implementation plan

> **Research snapshot: Nov 2026.** Companion documents: `docs/SUPERTONIC.md`
> (native engine baseline), `docs/KOKORO.md` (second-engine pattern we follow),
> `docs/TTS_ALTERNATIVES.md` (candidate matrix). This file is the detailed
> record of the Gujarati (MMS) research and the agreed implementation plan so
> nothing gets lost; the decisions table in §9 is binding.

**TL;DR**: `facebook/mms-tts-guj` is the only credible Gujarati TTS that runs on
our existing sherpa-onnx stack (VITS slot, native Gujarati-script frontend —
no uroman, no espeak). The catch is licensing: Meta ships all MMS weights under
**CC-BY-NC 4.0 (non-commercial)**. That is compatible with Rustle's own
CC-BY-NC-ND-4.0 posture **as long as Rustle stays non-monetized**, but the pack
must carry full attribution in the license screen, and the engine must be
swappable if a commercial route is ever chosen. No pre-built sherpa-onnx
Gujarati pack exists anywhere, so we convert the checkpoint ourselves, pin
sha256s, and self-host on a HF repo. Verdict: **add as the Gujarati engine —
third engine alongside Supertonic/Kokoro, manually selectable, with a
"Gujarati detected" suggestion pop-up at import.**

---

## 1. Identity and provenance (verified 2026-11-06)

| property | value |
|---|---|
| HF repo | [`facebook/mms-tts-guj`](https://huggingface.co/facebook/mms-tts-guj) (Meta AI org; ISO-639-3 code `guj`, catalog code we adopt: `gu`) |
| Architecture | **VITS** — separate checkpoint trained per language inside the MMS project (1107 TTS languages) |
| Parameters | **36.3 M** (F32 safetensors, HF file listing) |
| Sampling rate | **16 000 Hz** (HF transformers `config.json` *and* fairseq-space `models/guj/config.json` — verified identical) |
| Speakers | **1** (`num_speakers: 1`); fairseq config uses the legacy `n_speakers: 0` single-speaker marker |
| Frontend | **characters** — vocab of **60 tokens**, native Gujarati script; **no uroman** (verified: fairseq config `training_files: train.ltr`, not a `*.uroman` suffix, which is the case sherpa's exporter explicitly rejects) |
| `add_blank` | `true` — blank-token insertion between characters; the ONNX metadata carries it and sherpa-onnx applies it internally |
| Duration predictor | stochastic (`use_stochastic_duration_prediction: true`) → **non-deterministic synthesis**; segment-cache stays keyed on hash+voice, two renders of the same segment may differ subtly |
| Paper | *Scaling Speech Technology to 1,000+ Languages*, arXiv **2305.13516** (Pratap et al., 2023) — cite this in the license screen |
| Weights home also at | `dl.fbaipublicfiles.com/mms/tts/guj.tar.gz` and `https://huggingface.co/facebook/mms-tts/resolve/main/models/guj/{G_100000.pth,config.json,vocab.txt}` (the latter three are the exporter's inputs) |
| Checkpoint frozen | MMS TTS released with the MMS project (May 2023); no newer Gujarati MMS checkpoint exists |

## 2. Licensing deep dive (the issue the user asked about)

### 2.1 What the license actually is

Verified from two Meta-controlled sources:

- HF model card `facebook/mms-tts-guj`: **"License: cc-by-nc-4.0"**
- fairseq repo, `examples/mms/README.md` (verbatim): *"The MMS code and model
  weights are released under the CC-BY-NC 4.0 license."*

So **model weights (including the Gujarati checkpoint) = CC-BY-NC 4.0**, and
the MMS inference/convert code in fairseq is CC-BY-NC too. Contributors we
otherwise lean on are clean:

| component | license | shipped by us? |
|---|---|---|
| sherpa-onnx (AAR `app/libs/sherpa-onnx-2.0.1.aar`) | Apache-2.0 | yes — already attributed |
| ONNX Runtime (inside the AAR) | MIT | yes — already attributed |
| original VITS implementation (`jaywalnut310/vits`) | MIT | **no** (desktop conversion only) |
| 🤗 transformers `VITSModel` port (the HF card usage path) | Apache-2.0 | **no** — we use the fairseq/sherpa path instead |
| **MMS Gujarati weights** | **CC-BY-NC 4.0** | **yes — as a user-downloaded pack with attribution** |
| fairseq MMS conversion code (`vits-mms.py` recipe path) | CC-BY-NC 4.0 (per README) | **no** — one-time desktop conversion; only the CC-BY-NC **output weights** ship |

### 2.2 What CC-BY-NC 4.0 permits for this exact use case

- **Adaptation allowed**: ONNX conversion is a "Producer-made adaptation" and
  NC has **no NoDerivatives clause** → we may convert and re-host, with
  attribution, under the same NC license. ShareAlike is *not* required, so our
  HF repo's CC-BY-NC-4.0 label is enough (there is no licensing contagion to
  Rustle's code, which is explicitly carve-out-based).
- **Redistribution allowed** as a user-downloaded voice pack — same flow the
  Supertonic (OpenRAIL-M, fully passed to users in `LicensesScreen`) and Kokoro
  packs use, with attribution mandatory.
- **Non-commercial use**: CC's NonCommercial clause ("not primarily intended
  for or directed towards commercial advantage or monetary compensation").
  Rustle is **CC-BY-NC-ND-4.0** and has no ads/paid tiers, so shipping is
  license-compatible **today**.
- **The hard limitation**: if Rustle is ever monetized (ads, subscriptions,
  paid listing), the MMS engine must not ship alongside monetization. The
  third-engine design below deliberately keeps MMS behind its own swappable
  pack/manager so it can be dropped cleanly.
- **Attribution duty**: Meta AI ("Value from AI at Meta"), model + paper
  citation (Pratap et al. 2023), and the CC-BY-NC-4.0 restriction note must be
  passed to end users — add to `THIRD_PARTY_NOTICES.md`, bundle the license
  text in `res/raw`, and surface in `LicensesScreen` (see §6 Phase D).

### 2.3 Why is MMS non-commercial? (context, not a blocker)

Meta has not published a clause-by-clause justification on the card; the MMS
README release note is the authoritative statement (weights = CC-BY-NC 4.0).
Community understanding is that MMS trained on transcribed religious readings
with non-commercial-only terms, which propagates to the weights — so treat any
"maybe it's really commercial-OK" hope as **not supported by the license text**
and plan around NC strictly.

### 2.4 Commercial-friendly Gujarati alternatives (keep for the escape hatch)

If monetization ever happens, or if MMS quality/RTF disappoints, these were
checked on HF (2026-11-06):

| candidate | license | notes |
|---|---|---|
| `SYSPIN/vits_Gujarati_Male` + `SYSPIN/vits_Gujarati_Female` | **MIT** (card-tagged) | native Gujarati VITS checkpoints (fairseq/VITS format) — same conversion choreography as MMS would apply; provenance/training-data + quality review still required |
| `multilingual-tts/VITS-OpenBible-Gujarati` | **CC-BY-SA-4.0** (card-tagged) | ShareAlike rather than NC; commercially usable with attribution+SA; OpenBible-derived corpus |
| community F5-TTS Gujarati models (e.g. `HarshBhanushali7705/…`, `Arjun4707/F5-TTS-Gujarati`) | untagged/unclear | **F5 is not runnable in sherpa's VITS slot**; also no licensing clarity → disqualified for now |

Note: search surfaced third-party MMS ONNX conversion projects
(`willwade/mms-tts-multilingual-models-onnx` and forks) aimed at
transformers.js, but **none provide a verified sherpa-onnx-format pack with
pinned checksums** — self-building keeps provenance clean either way.

## 3. Runtime feasibility on our exact stack (verified against the shipped AAR)

- `app/libs/sherpa-onnx-2.0.1.aar` was inspected (`javap` over `classes.jar`):
  - `OfflineTtsVitsModelConfig(model, lexicon, tokens, dataDir, dictDir,
    noiseScale = 0.667, noiseScaleW = 0.8, lengthScale = 1.0)` **exists**
    (data-classes + getters/setters confirmed).
  - `OfflineTtsModelConfig(vits, matcha, kokoro, zipvoice, kitten, pocket,
    supertonic, numThreads, debug, provider)` exposes the `vits` slot — the
    same `OfflineTts` JNI we already drive for Supertonic/Kokoro.
- sherpa-onnx's MMS doc path (`k2-fsa.github.io/sherpa/onnx/tts/mms.html`)
  converts **non-uroman** MMS checkpoints to exactly two runtime files:
  **`model.onnx` + `tokens.txt`** — no lexicon, no espeak `dataDir`, no
  `dictDir` for native-script languages like Gujarati. The official usage line
  is only `--vits-model=./model.onnx --vits-tokens=./tokens.txt`.
- Upstream ships **8 pre-converted MMS packs** in the `k2-fsa/sherpa-onnx`
  `tts-models` GitHub release (646 assets, verified count): `vits-mms-{eng,
  spa,fra,tha,ukr,rus,deu,nan}` at ~102.7 MB bz2 each (fp32 145 MB `model.onnx`
  inside). **No `guj` pack** — and a HF search for a `vits-mms-guj` repo came
  up empty → confirmed: **we must build and host the pack ourselves**.

## 4. Conversion procedure (verified recipe, desktop one-time)

From sherpa-onnx docs (`onnx/tts/mms.html`), condensed for `guj`:

1. `pip install onnx scipy Cython` + `torch==1.13.0+cpu` (version pinned in the
   docs — do not "upg the torch" casually, the exporter relies on it).
2. Download the three exporter inputs:
   `https://huggingface.co/facebook/mms-tts/resolve/main/models/guj/G_100000.pth`,
   `config.json`, `vocab.txt`.
3. `git clone https://huggingface.co/spaces/mms-meta/MMS` for the VITS source;
   export `PYTHONPATH=MMS:MMS/vits`, build `monotonic_align` (its `setup.py`
   builds the `.so`).
4. Run the docs' `vits-mms.py` exporter with `lang=guj` → produces
   **`model.onnx`** (opset 13; input `x/x_length/noise_scale/length_scale/
   noise_scale_w`) and **`tokens.txt`** (character ids incl. the exporter's
   uppercase-mapping rule for Latin tokens).
5. Runtime knobs to pin in the app: `noiseScale 0.667 / noiseScaleW 0.8 /
   lengthScale 1.0` (the AAR data-class defaults match the checkpoint's
   `config.json` exactly).
6. Verify on desktop CLI (`sherpa-onnx-offline-tts --vits-model --vits-tokens
   --debug`) **listening-test a Gujarati paragraph** before declaring sha256s
   — do not ship an un-listened pack.

## 5. Pack decisions (agreed with user, 2026-11-06)

| decision | value |
|---|---|
| Hosting | **Rustle-controlled HF repo** (e.g. `chintan992/rustle-mms-guj-tts`), repo-labeled **CC-BY-NC-4.0**, per-file `resolve/main/` URLs — mirrors how the Supertonic pack comes from `csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11` and Kokoro from a pinned `csukuangfj/kokoro-multi-lang-v1_0` commit |
| Files | `model.onnx` + `tokens.txt` (2-file pack, both sha256-pinned in `MmsModelManager`; no "existence-only" `tts.json` exception needed) |
| Precision | **fp32** (~150 MB class; int8 rejected — VITS vocoders degrade audibly; fp16 rejected — Mobile CPU fp16 kernels may be slower) |
| Size estimate | ~145 MB raw ONNX → **~150 MB download surface**; measure exact bytes at conversion time and pin them |

## 6. Implementation plan — app integration map (all paths verified in this session's source reads)

The integration copies the Kokoro second-engine pattern end-to-end. Phases:

**Phase A — pack build (desktop, `work/` script pattern):**
1. Conversion script per §4 + recorded `EXPECTED_SIZES`/`EXPECTED_SHA256`.
2. Upload to the HF repo; freeze the commit hash we pin in code.

**Phase B — third engine (mirrors `KokoroTtsEngineManager` exactly):**
1. `data/model/AppSettings.kt`: `TtsEngine` adds `MMS_GUJ`
   (storage-key back-compat unchanged — `fromStorageKey` falls through).
2. **`engine/MmsVitsTtsEngineManager.kt`** (new):
   `OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(model = …,
   tokens = …))`; `EXPECTED_SAMPLE_RATE = 16_000`; **1 speaker** → fixed
   voice id (e.g. `"guj-mms-single"`, sid 0); reject wrong sample rate /
   `numSpeakers != 1` at load like Kokoro does; reuse chunked synthesis
   (`KokoroTextChunker`-style cap) since long inputs degrade VITS; no
   `extra lang` config needed (language is baked into the checkpoint).
3. **`engine/MmsModelManager.kt`** (new, mirror `KokoroModelManager`):
   staged dir `models/mms-guj-v1-staging` → final `models/mms-guj-v1`;
   2-file `REQUIRED_FILES`; size **and** sha256 verify; Wi-Fi gating
   (`isWifiConnected()`), retry/backoff, `clearModelData()`,
   `isPackInstalled()`, `evaluatePackChecksums()`.
4. `di/RepositoryModule.kt`: provide both managers (next to
   `provideKokoroTtsEngineManager`, line ~76).
5. `engine/TtsEngineSelection.kt`:
   ```kotlin
   fun forLanguage(preferred: TtsEngine, language: String): TtsEngine {
     val normalized = LanguageCatalog.normalizeOrNull(language) ?: return?…
     // gu has exactly one capable engine:
     if (normalized == "gu") return TtsEngine.MMS_GUJ
     return if (preferred == TtsEngine.KOKORO && normalized == "en") TtsEngine.KOKORO
            else TtsEngine.SUPERTONIC
   }
   ```
   (manually picking MMS for a non-`gu` book falls back like Kokoro-on-Hindi
   does today.)
6. `service/AudiobookService.kt`: third `when(engine)` arm in
   `renderSegment` (dispatch at lines ~253-256) + sample-rate branch for the
   RTF math (lines ~266-270 → `MmsVitsTtsEngineManager.EXPECTED_SAMPLE_RATE`).
7. `engine/SegmentVoices.kt`: engine-scoped dirs for the third engine —
   `audio-mms` / `cache/audio-mms` (`engineAudioDir`, `chapterAudioDir`,
   `globalCacheAudioDir`, `wavFileName` currently branch only on
   SUPERTONIC-vs-Kokoro).
8. `playback/PlaybackSession.kt`: language/engine re-target paths already call
   `TtsEngineSelection.forLanguage` at ~9 sites (335, 707, 716, 757, 782, 791,
   837, 889) + `resolveVoiceForEngine` (~844) — extend for the MMS voice slot;
   engine-change unload/`releaseAndRun` policy applies (never 2 engines warm).

**Phase C — language + UI:**
1. `core/LanguageCatalog.kt`: add `"gu" to "Gujarati"` (the map is Supertonic's
   31 today; `normalizeOrNull` handles `gu-IN`-style values automatically).
2. `core/LanguageDetector.kt`: add the Gujarati Unicode block
   **`0x0A80..0x0AFF`** to the script histogram with a decisive
   `SCRIPT_FRACTION` vote → `"gu"` (same shape as Devanagari → hi; both blocks
   are disjoint so detection is unambiguous).
3. Settings/book-prefs: `BookPrefs.voiceFor(engine)` gains the MMS slot;
   `AppSettings` default single-voice `mms` entries as needed.
4. **Manual selection** (user-required): `ui/onboarding/VoiceCatalog.kt`
   (`presetsFor(engine)` / `defaultId(engine)` / `contains(engine, id)`) and
   `ui/onboarding/VoiceSetupScreen.kt` (engine preview wiring — new
   `mmsEngineManager` preview like the Kokoro one at line ~76) surface a
   "Gujarati (MMS — single voice)" choice.
5. **Model card**: `ui/model/ModelManagerScreen.kt` + `ModelViewModel.kt`
   third card & state (`mmsDownloadState`, `startMmsDownload`, clear-actions —
   copy the `kokoro` pair verbatim; "Download Gujarati (~150 MB)").
6. **Import-time suggestion pop-up** (user-required): `pipeline/ImportPipeline.kt`
   already runs `LanguageDetector.detect(sampleText)` at line ~46 (and
   `BookReprepper` re-detects on re-prep) — when result is `gu` and the MMS
   pack is absent, surface "Gujarati detected — download the Gujarati voice
   pack (~150 MB)?" with CTA to the Model card / inline kick-off (a gu book
   without the pack cannot narrate with Supertonic; gate any play attempt on
   `isPackInstalled()` with the same prompt).
7. Non-English books already skip expressive `<…>` cue insertion; per-book
   language override flow (`changeBookLanguage`) picks MMS up automatically.

**Phase D — licensing compliance:**
1. `THIRD_PARTY_NOTICES.md`: MMS Gujarati weights entry (CC-BY-NC-4.0, Meta AI,
   HF src, paper cite).
2. `app/src/main/res/raw/license_cc_by_nc_4.txt` — verbatim text (house pattern
   beside `license_apache_2.txt` / `license_mit_onnxruntime.txt` /
   `license_openrail_m.txt`).
3. `ui/settings/LicensesScreen.kt`: attribution block (Meta AI + Pratap et al.
   2023 arXiv:2305.13516 + "non-commercial use only" restriction note).
4. README "Choose an on-device voice" section: add the Gujarati bullet + size.

**Phase E — tests & validation:**
1. `app/src/test/.../LanguageDetectorTest.kt`: Gujarati-block decisive cases
   (gu beats Latin/Devanagari noise; sample-cap edge).
2. Engine-selection tests: `gu`→MMS regardless of preference; `en`+Kokoro
   pref→Kokoro; defaults unchanged.
3. `MmsModelManager` checksum/set-evaluation tests (mirror `KokoroModelManager`
   JVM-suite cases).
4. Device RTF benchmark via the existing `RtfBenchmark`/`RtfStats` harness —
   **the one real unknown**: 36M-param VITS at 16 kHz on mid-range mobile CPU;
   if RTF ~1+, tune `lengthScale`/accept slower prepare with pre-gen cache
   (chapter pre-generation already caps live-listening RTF exposure).
5. Listen-test on-device: Gujarati book chapter, verify sentence-highlight
   sync (16k WAV math in `renderSegment` must use the 16 000 constant).

## 7. Known risks / open follow-ups

- **Non-deterministic synthesis** (stochastic duration predictor): two renders
  of one segment differ subtly. Cache is keyed on segment hash + voice, so
  nothing churns; silent-continuity seams across re-renders are the thing to
  listen for.
- **Text normalization**: MMS `guj` is character-based — Gujarati numerals
  (૦–૯), digits, and abbreviations get read character-wise. Optional stretch:
  a small Gujarati number-to-words pre-synthesis pass in the text pipeline
  (out of Phase A–D scope).
- **Dialect/accent versus news-read style**: single-speaker checkpoint per
  language; the exact speaker is whatever the MMS data contained — accept
  after listen-test.
- **NC contagion canary**: keep MMS fully behind `MmsModelManager` +
  `MMS_GUJ` enum entry so a future monetization switch removes one engine +
  one license row, not a refactor.
- The `gu`↔`guj` mapping (catalog code vs ISO-639-3) is ours to keep straight
  in one place: `LanguageCatalog` code `gu`, HF/weights side `guj`.

## 8. Sources

- HF model card + file listing: <https://huggingface.co/facebook/mms-tts-guj> (public)
- fairseq MMS README (license statement + checkpoint download layout):
  <https://github.com/facebookresearch/fairseq/blob/main/examples/mms/README.md> (public)
- sherpa-onnx MMS conversion docs:
  <https://k2-fsa.github.io/sherpa/onnx/tts/mms.html> (public)
- sherpa-onnx `tts-models` release asset list (646 assets, 8 MMS packs,
  verified via GitHub API): <https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models> (public)
- Bundled AAR API surface: `javap` over `sherpa-onnx-2.0.1.aar` → `classes.jar`
  (`com.k2fsa.sherpa.onnx.{OfflineTtsVitsModelConfig, OfflineTtsModelConfig}`), 2026-11-06
- HF alternative-sweep: `SYSPIN/vits_Gujarati_*` (MIT),
  `multilingual-tts/VITS-OpenBible-Gujarati` (CC-BY-SA-4.0), HF API model
  search "gujarati tts", 2026-11-06
- Rustle code call-sites: file/line references inline above (this session)

## 9. Binding decisions (agreed with user)

| # | decision | value |
|---|---|---|
| 1 | Pack hosting | Rustle-controlled HF repo, CC-BY-NC-4.0 label, resolved per-file |
| 2 | Pack precision | fp32 (~150 MB) |
| 3 | Engine slotting | Third engine like Supertonic/Kokoro; manually selectable in voice setup/model manager; suggested pop-up when Gujarati is detected in imported text |
