# Kokoro-82M — evaluation deep dive

> **Research snapshot: Nov 2026.** Companion documents: `docs/TTS_ALTERNATIVES.md`
> (candidate comparison, device feasibility, human-likeness matrix) and
> `docs/SUPERTONIC.md` (current engine baseline). This file is the detailed
> record of the Kokoro research so nothing gets lost; a short summary lives in
> §14 of `TTS_ALTERNATIVES.md`.

**TL;DR**: Kokoro is the most natural sounding of all models that can actually
run on our target device (UTMOS 4.44; blind-human Elo 1478), with verified
flagship-class realtime CPU speed — but it is an **English specialist** with
**zero acting control** and one significant integration risk (G2P under the
GPL-clean sherpa-onnx 2.0). Verdict: **add as the English engine; keep
Supertonic for the rest of Europe.**

---

## 1. Identity and provenance

| property | value |
|---|---|
| Model | `hexgrad/Kokoro-82M` **v1.0** (published 2025-01-27) |
| Architecture | StyleTTS 2 + ISTFTNet, decoder-only — **no diffusion, no encoder** |
| Parameters | 82 M |
| Output | 24 kHz, all voices in one `voices.bin` |
| Languages / voices | **8 languages, 54 voices** (v1.0) |
| License | **Apache-2.0** weights — clearly commercial |
| Training data | few hundred hours of *permissive-only* audio: public domain, Apache/MIT, and **synthetic audio from big providers only** — no open-model synthetic, no voice clones → no impersonation surface at all |
| CC attribution | Koniwa (JA, <1 h), SIWIS (FR, <11 h) — attribution duty in `LicensesScreen` if we ship it |
| Training cost | ~$1 000 of A100-hours; trained by a solo maintainer |
| Roadmap | **effectively frozen** — v1.1-zh is Mandarin-focused and drops most v1.0 voices ("not a strict upgrade"); EN unchanged since Jan 2025; "Retired" badge on TTS Arena. Same risk profile as the archived Supertonic repo |
| Frontend | `misaki` (Apache-2.0, Python) with **espeak-ng fallback** for OOV / non-English |

### 1.1 Release history (verified 2026-11-04 via HF author API)

`https://huggingface.co/api/models?author=hexgrad` returns **exactly these
four repos** — there is nothing newer:

| HF repo | Published | Voices / langs | Role for Rustle |
|---|---|---|---|
| `hexgrad/Kokoro-82M` **(v1.0)** | **2025-01-27** | 54 / 8 | **our target release** — every benchmark, the Arena entry, and all bundle sizes in this doc refer to v1.0; ~11.5 M downloads |
| `hexgrad/Kokoro-82M-v1.1-zh` | late-2025 preview | ~100 CMS voices, **drops most v1.0 EN voices** | zh-first experiment, "not a strict upgrade" per card; ~74 k downloads — **not for us** |
| `hexgrad/kLegacy` **(v0.19)** | 2024-12-25 | 10 / 1 (EN) | legacy only |
| `hexgrad/styletts2` | — | base model | not a TTS release |

Conclusion from the listing: **no English or European-language release
exists after v1.0**; the roadmap is frozen. (A "v1.1-gla"/German official
release does *not* exist — German Kokoro work is exclusively community
third-party, e.g. Hangry Labs KokoroTTS and AKinvox `kokoro-multispeaker-de`,
not evaluated here.) The ecosystem standard is v1.0: thewh1teagle's
`kokoro-onnx` exports both v1.0 and v1.1-zh in three precisions, and sherpa
bundles exist for both — v1.0 is what the UTMOS 4.44 and Arena 1478 scores
were measured on.

---

## 2. Naturalness evidence (verified)

- **UTMOS 4.44–4.46** — best of the runnable set (Supertonic 4.32, Pocket
  4.10). Objective neural-MOS, `utmos22_strong`, 180 timed runs, public code
  + all 36 audio samples.
- **TTS Arena 1478 ± 22 (#29)** from 1.0 k blind votes — statistically tied
  with Chatterbox (1481 ± 19) and only ~20 Elo below ElevenLabs Eleven v3
  (1501 ± 34). Badge: "Retired" (no longer accepting votes).
- Independent review consensus (2026): "ranks level with Google's/Amazon's
  premium voices in blind listening tests" (texttolab), "human-like" (Neo).

---

## 3. Edge realtime — verified anchors

| Device | Path | Speed | Source |
|---|---|---:|---|
| Snapdragon 8 Elite (Z Fold 7), **CPU** | PyTorch native | **2.8× realtime** | r/LocalLLaMA audiobook-reader builder (Oct 2026) |
| Pixel 10 Pro / Tensor G5, **CPU, 4 threads** | sherpa fp32 bundle | **RTF 0.48–0.51 (~2× realtime)** | sherpa-onnx issue #3754 measurements |
| Motorola Razr 2020 / **Adreno 620 GPU**, OpenCL | adreno-llms | **RTF ≈ 0.93** (~1× realtime on a 2020 mid-range GPU) | adreno-llms benchmark via Speech Central |

S25 Ultra expectation: passes on CPU alone (~3× realtime-class), with an
accelerated GPU path (Adreno 830) on top.

### 3.1 Critical int8 warning — quantization is a trap here

Measured on the same Tensor G5, 4 threads (issue #3754):

| Config | RTF | Notes |
|---|---:|---|
| fp32 | **0.48–0.51** | clean audio |
| int8 | 0.62 | **slower than fp32** on ARM; steady tones at `fs/5` and `2·fs/5` (4.8/9.6 kHz at 24 kHz output — iSTFT frame-rate artifacts); int8-only rail-pinning bug that returned whole sentences as garbage (~+1.0 samples, swallowed sentences via DC pop) |

The rail-pinning bug (sherpa-onnx 1.13.4, Android int8 path) was
**engine-state dependent**, never reproduced on x86_64, never on fp32, and
was **fixed 2026-10-26 by PR #4087 / sherpa-onnx v2.0.1**.

**Conclusion: use the fp32 bundle, not int8.** For Kokoro on ARM, quantization
buys smaller storage but *loses* speed. (Opposite of Supertonic, where int8 is
the deployment standard.) Rustle pins the v1.0 sherpa-onnx pack and downloads
`model.onnx`, `voices.bin`, `tokens.txt`, and `lexicon-us-en.txt` (359,719,088
bytes total, about 343 MiB); this is larger than the earlier ~334 MB estimate
because the runtime pack includes the English lexicon.

---

## 4. Language and voice reality (author's own grades, `VOICES.md`)

| Language | Voices | Author grades / notes |
|---|---:|---|
| English US | 11F 9M | **af_heart A, af_bella A-**, af_nicole B-; several C/D voices |
| English UK | 4F 4M | bf_emma B-; rest C/D |
| French | **1F only** | ff_siwis B-, trained on <11 h CC-BY audio |
| Italian | 1F 1M | ~H hours of data, otherwise ungraded |
| Spanish | 1F 2M | ungraded |
| Portuguese BR | 1F 2M | ungraded |
| Hindi | 2F 2M | C grade |
| Japanese / Mandarin | 9 total | mostly C; zh thin |
| **German, Dutch, Nordic, Slavic** | **0** | **not supported at all** |

Card warning, quoted: *"Support for non-English languages may be absent or
thin due to weak G2P and/or lack of training data."* Non-English text in an
English voice silently pronounces in English.

Practical casting notes:

- Narration shortlist: af_heart, af_bella, bf_emma, af_nicole.
- Male side: several male EN voices grade C–F (am_adam literally "F+"),
  am_fenrir / am_michael / am_puck / bf? bm_george are the usable C/B-ish
  set — a male dialog cast must be curated around grades.
- 54 voices ≠ 54 usable narrator voices; roughly ~10 are book-quality for EN.

**Verdict for Rustle: an English specialist.** EN narration world-class;
FR/ES/IT/PT exist but thin and mostly single-voice; the rest of Europe is
absent. It is an **ADDITION** (per-language engine selection), not a
Supertonic replacement across the 31-language catalog.

---

## 5. Long-form audiobook behavior

- Hard cap: **510 phonemes per pass**; longer input must be split. The pinned
  sherpa-onnx Android `OfflineTts` API does not expose a phoneme-count or
  phonemization preflight method, so Rustle uses sentence/clause-first chunks
  with a conservative character bound. This reduces risk but is not a formal
  guarantee for unusually phoneme-dense or out-of-vocabulary text.
- Truncation is not benign: *"when truncation happens, the audio also gets
  corrupted. So my strategy … has been to avoid truncation at all costs"*
  (hexgrad/kokoro issue #200).
- Author guidance (`VOICES.md`): voices peak at **100–200 tokens**
  (goldilocks), weaken below 10–20 tokens, and *rush* above 400 tokens.
  Mitigations: chunk on sentence boundaries, **merge short segments**,
  `speed` parameter tuning.
- Community precedent: readers rendering **600-page novels** (r/TextToSpeech
  iOS reader dev) and the 2.8×-RT Android audiobook reader above.
- Maps well onto `BookReprepper`'s existing segment chunking: merge tiny
  segments, split long passages; per-segment voice already supported.

---

## 6. Packaging and licensing on Android

### 6.1 The GPL knot — resolved upstream, still integration work for us

sherpa-onnx historically phonemized Kokoro via `piper-phonemize` +
`espeak-ng` (GPL-3) — incompatible with sherpa's Apache-2.0 and the reason
projects like SC Kokoro GPL-licensed their Android engine.

**sherpa-onnx v2.0.0 (2026-10-23) / v2.0.1 (2026-10-26) removed
`espeak-ng` and `piper-phonemize` entirely** (issue #3731, closed) to stay
Apache-compatible. This is a breaking change (v2.0):

1. **`lexicon.txt`** — word → phonemes file distributed with the model
   (sherpa ships generation scripts). Risk for us: a lexicon cannot cover
   the invented proper names that dominate fiction (Shadow Slave!); OOV
   pronunciation must be audit-tested.
2. **External phonemization** — pre-compute token strings and pass them via
   the new `GenerationConfig.tokens` field. The natural tool is `misaki`
   (Apache-2.0) but it is Python (kokoro.js uses a WASM port) → needs a
   Kotlin/C++ port, an embedded WASM path, or server-side pre-phonemization
   baked into our prepared-book format.

### 6.2 Verified bundle sizes (sherpa `tts-models` release assets, Nov 2026)

| Bundle | Size |
|---|---:|
| `kokoro-multi-lang-v1_0` (fp32, 8 langs) | **333.7 MB** |
| `kokoro-int8-multi-lang-v1_0` | 126.2 MB — slower on ARM, avoid |
| `kokoro-multi-lang-v1_1` (= v1.1-zh, zh-focused — not for us) | 348.0 MB |
| legacy EN `v0_19` | 98.5 MB int8 / 304.8 MB fp32 |

The source bundle table above lists 333.7 MB, but Rustle's pinned runtime pack
also includes the English lexicon required by sherpa-onnx v2.0.1. The four
downloaded files total 359,719,088 bytes (about 343 MiB); alongside Supertonic
the on-device model files occupy roughly half a GB.

### 6.3 License sheet if we ship Kokoro

| Component | License | Action |
|---|---|---|
| Kokoro weights | Apache-2.0 | NOTICE |
| Koniwa tones (JA data) | CC BY 3.0 | attribution (already-IN cards) |
| SIWIS (FR data) | CC BY 4.0 | attribution |
| misaki frontend | Apache-2.0 | NOTICE |
| espeak-ng | GPL-3 | **do not embed** — sherpa ≥2.0 requires lexicon/external-tokens route |
| sherpa-onnx | Apache-2.0 | already used |

---

## 7. Expression

- **No emotion tags, no pause control** — only `speed` + voice choice.
- The 21-tag inline acting that Supertonic has does **not** exist in Kokoro.
  Our expression-tag pipeline (`BookReprepper` prep) becomes inert on EN
  chapters unless tags are stripped (or mapped to `speed`, lossy at best).
- Net trade for EN books: +0.12 UTMOS naturalness, −voice acting.

---

## 8. Conclusion and implementation status

Kokoro is implemented as an optional English engine; Supertonic remains the
default and continues to handle every non-English language. The engine is a
global preference, with per-language fallback rather than a replacement for
the existing language catalog.

Rustle pins `csukuangfj/kokoro-multi-lang-v1_0` at
`f7b96bb6bef5c5da4d3aa4f4e0498fbbf62dc78b` and downloads only
`model.onnx`, `voices.bin`, `tokens.txt`, and `lexicon-us-en.txt`. It uses the
sherpa-onnx 2.0.1 lexicon route without downloading espeak-ng data. Output is
kept in an engine-specific cache and compressed at Kokoro's 24 kHz sample
rate. Kokoro uses sentence/clause-first chunks capped at 160 characters, with
words preserved at chunk boundaries. This is a conservative proxy for the
phoneme cap, not an exact phoneme-count guarantee; oversized single tokens
surface an error instead of being silently split or dropped. A Kokoro cache
version is included in WAV names and compressed indexes so output made with
the previous chunker is regenerated. Supertonic text preparation and cache
identity remain unchanged.

Before release, validate Kokoro synthesis and lexicon/OOV pronunciation on
physical Android devices, and measure latency and memory use alongside the
existing Supertonic path. The unit suite covers engine selection, cache
isolation, chunking boundaries, and sample-rate forwarding; it cannot replace
those device and pronunciation checks.
   e. Voice audit: pick 1 female + 1 male narrator + dialog set from the
      graded EN shortlist; check consistency across a 60-min render.
   f. ExecuTorch/ONNX GPU path (NNAPI/QNN or OpenCL) as a stretch speedup.
4. Do not expect acting control — expressive EN books remain the domain of
   Qwen VoiceDesign / Chatterbox **server pre-rendering**.

---

## 9. What we did NOT verify

- Our own on-device RTF/RAM with the fp32 sherpa bundle (all speed numbers
  are external measurements from other people's devices).
- OOV pronunciation quality through `lexicon.txt` on fiction names.
- Whether sherpa's v2.0 model page actually ships a Kokoro lexicon file (the
  release notes describe the mechanism; per-model packaging must be checked
  before building).
- Android GPU-accelerated path audio quality/stability (QNN/OpenCL).
- Voice-consistency drift over 30–60 minute chapter runs.
- Voice quality of the graded-omitted non-EN voices (ef_dora/em_alex/
  em_santa, pf_dora/pm_alex/pm_santa): `VOICES.md` gives them no grades.
- Whether sherpa's per-language chunking heuristics differ from English.

## 10. Sources (accessed Nov 2026)

- Model card: <https://huggingface.co/hexgrad/Kokoro-82M> (README, raw) —
  releases table, training-data disclosure, CC-BY attribution table
- Voice grades & chunking guidance: `VOICES.md` on the same card
- UTMOS benchmark incl. Pocket TTS:
  <https://heyneo.com/blog/kokoro-supertonic-inflect-nano-pocket-tts-cpu-benchmark>
  — code + all audio samples at
  <https://github.com/gauravvij/kokoro-tts-vs-supertonic-3-tts>
- TTS Arena standings (Oct 10 2026 snapshot): yaps "Best Local TTS Models"
  leaderboard section — <https://www.yaps.ai/blog/best-local-tts-models>
- ARM int8 bug + fp32 RTF measurements:
  <https://github.com/k2-fsa/sherpa-onnx/issues/3754> (closed; fixed by
  PR #4087 in v2.0.1)
- espeak-ng removal / Apache-only posture:
  <https://github.com/k2-fsa/sherpa-onnx/issues/3731>; releases
  v2.0.0 (2026-10-23), v2.0.1 (2026-10-26)
- Bundle sizes: sherpa-onnx `tts-models` release asset listing
- Chunking/truncation: <https://github.com/hexgrad/kokoro/issues/200>
- Community realtime anchors: r/LocalLLaMA "Android audiobook reader" thread
  (2.8× RT on Snapdragon 8 Elite); Speech Central "Kokoro for Android"
  writeup (adreno-llms RTF 0.93 on Adreno 620; GPL-3 engine choice):
  <https://speechcentral.net/2026/10/17/kokoro-for-android-from-proof-of-concept-to-practical-offline-tts/>
- v1.1-zh: <https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh> (zh-focused,
  drops most v1.0 voices)
- Independent review: texttolab Kokoro TTS review (May 2026)
