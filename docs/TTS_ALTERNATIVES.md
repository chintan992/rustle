# TTS alternatives — research report (Nov 2026)

Research into expressive TTS engines that could replace or complement
**Supertonic 3** in the Rustle on-device Android audiobook app. All file sizes
were verified against the public Hugging Face / GitHub file listings in
Nov 2026; latency numbers that are *not* verified are marked as claims.

The Supertonic 3 baseline itself is documented in `docs/SUPERTONIC.md`.

---

## 1. Scope and constraints

Decisions fixed with the user before the comparison:

| constraint | value |
|---|---|
| Deployment | **On-device Android, performance first** |
| Target markets | **English + Europe** (EN, FR, DE, ES, IT, PT, NL primary; PL/CS/DA/SV/EL/HU secondary) |
| Model pack budget | Up to **~1.5 GB**, but the size must buy real performance |
| Voice model | **Presets only**; voice cloning omitted (impersonation / licensing risk) |
| Delivery | Chapter pre-generation acceptable; **48 kHz stereo allowed** |
| License posture | Commercial OK; open weights preferred; **OpenRAIL-M and CC-BY-4.0 acceptable** (no impersonation) |
| Quality vs speed | **Balanced** — measurable quality improvement plus mid-range Android performance |

---

## 2. How to read the cost numbers

Three different "heaviness" axes, and they must not be conflated:

1. **Pack size** — bytes the user downloads / unpacks to disk.
2. **Runtime RAM** — memory held while generating (never published for most
   models; for small ONNX models expect roughly 1.5–2× the weight size for
   activations + ONNX Runtime arena).
3. **CPU/GPU load** — whether a mid-range phone SoC can render faster than,
   or at least near, real time.

A model advertised as "110M parameters" or "100M" can still carry a
~1 GB audio-decoder network next to it, so *pack size is the only number that
is verified below*; everything else is derived from it or from a vendor claim.

---

## 3. Verified pack sizes

Measured from the HF repo file trees / release assets on Nov 2026
(`tree/main?recursive=true` API; release-asset byte sizes).

| Model / configuration | Runtime files that must ship | Pack size | Source |
|---|---|---:|---|
| **Supertonic 3 int8** (sherpa bundle, in-app) | int8 ONNX + vocoder + metadata | **~123 MB** bundle / **~138 MB** unpacked | verified, in use |
| **Pocket TTS English int8** (sherpa, 2026-01-26) | english model + vocab | **~94 MB** (tar.bz2) | sherpa-onnx release asset |
| **Pocket TTS full** (sherpa, unquantized) | english model | ~160 MB (tar.bz2) | sherpa-onnx release asset |
| **Pocket TTS, 7 official languages, int8 est.** | EN/FR/DE/PT/IT/ES/NL bundles | **~0.66 GB** (7 × ~94 MB) | estimate from EN bundle |
| **Pocket TTS, 7 languages unquantized** | per-language `model.safetensors` (~209 MB each) | **~1.46 GB** | kyutai/pocket-tts tree |
| **Pocket TTS 24-layer "high quality"** | model + ~25 voice-embedding files per language | **~641 MB per language** | kyutai/pocket-tts tree |
| **MOSS-TTS-Nano ONNX** | TTS graphs + mint/Moss audio tokenizer codec | **~765 MB** (672 MB TTS + 93 MB codec) | OpenMOSS-Team HF tree |
| **Kokoro-82M int8** (sherpa) | int8 model + voice bins | **~98–140 MB** | sherpa release assets |
| **Chatterbox Nano** | `t3_nano_v1` (830 MB) + one `s3gen` variant (~1 008–1 016 MB) | **~1.85 GB** minimum | ResembleAI/chatterbox-nano tree |
| **Chatterbox Turbo** | `t3_turbo_v1` (1 827 MB) + s3gen variant | **~2.85 GB** | ResembleAI/chatterbox-turbo tree |
| **Chatterbox Multilingual V3** | `t3_mtl23ls_v3` (2 045 MB) + `s3gen_v3` (1 008 MB) | **~3.05 GB** | ResembleAI/chatterbox tree |
| **Qwen3-TTS-12Hz-0.6B-Base** | `model.safetensors` (1 745 MB) + speech tokenizer (651 MB) | **~2.40 GB** | Qwen HF tree |
| **Qwen3-TTS-12Hz-0.6B-CustomVoice** | model (1 728 MB) + tokenizer (651 MB) | **~2.38 GB** | Qwen HF tree |
| **Qwen3-TTS-12Hz-1.7B-CustomVoice / VoiceDesign** | model (3 656 MB) + tokenizer (651 MB) | **~4.31 GB** | Qwen HF tree |
| **Qwen3-TTS-12Hz-1.7B-Base** | model (3 679 MB) + tokenizer (651 MB) | **~4.33 GB** | Qwen HF tree |

Notes:

- The sherpa Pocket/MOSS bundles are **compressed** (tar.bz2); unpacked sizes
  are somewhat larger. The 1.5 GB budget check below uses verified numbers.
- `Qwen3-TTS-12Hz-0.6B-VoiceDesign` repo was empty at measurement time; only
  Base and CustomVoice have weights published.
- Chatterbox Nano advertises "110M parameters" but its **decoder is a full
  ~1 GB** `s3gen` network — the real runtime set is ~1.85 GB, not 110M-
  parameter-scale storage. Same trap for the rest of the family.

---

## 4. CPU/GPU load on mobile

| Model | Compute path | Verified/claimed speed | Mobile verdict |
|---|---|---|---|
| **Supertonic 3** | pure ONNX Runtime, CPU only | RTF **0.30** measured on desktop (docs/SUPERTONIC.md); proven in-app on Android | **Excellent** — the only engine already shipped |
| **Pocket TTS** | CPU, ~2 cores, no GPU | claim: ~6× realtime on MacBook Air M4 (2 cores) | **Excellent on paper** for EN; untested on Android |
| **MOSS-TTS-Nano** | CPU/ONNX streaming; 48 kHz stereo codec is the expensive part | claim: realtime on 4-core CPU | **Uncertain** — ONNX path exists but Android integration is DIY |
| **Kokoro-82M** | CPU only | RTF 0.57 measured on same desktop as Supertonic | **Slower than Supertonic** and weaker language coverage |
| **Chatterbox Nano** | CPU claimed 3× realtime on 8-core x86 | unverified on ARM | **No Android path**; pack already over budget |
| **Chatterbox ML V3 / Turbo** | CUDA/GPU class in practice; PyTorch runtime | n/a | **Not mobile** — server class |
| **Qwen3-TTS (0.6B / 1.7B)** | CUDA recommended; no supported mobile runtime | n/a | **Not mobile** — server class |

Key distinction for the "does size buy performance?" question: **no.** The
2.4–4.3 GB models (Qwen, Chatterbox ML) are bigger *because they are stronger
language models with richer voice control*, not because they are faster. On
phones they would be both slower and RAM-infeasible. Size on-device buys
quality/coverage, never speed.

---

## 5. Language coverage vs. markets

| Model | Coverage | Fit to EN + Europe |
|---|---|---|
| Supertonic 3 | **31 languages**, incl. full Europe + South Asia | current full fallback; non-major-language quality varies |
| Pocket TTS | **7 official**: EN, FR, DE, PT, IT, ES, NL | covers the **primary** markets only |
| MOSS-TTS-Nano | **20**, incl. PL, CS, DA, SV, EL, HU, TR, RU, AR | broadest Europe coverage under 1 GB |
| Kokoro | practical EN/ZH in sherpa | no |
| Chatterbox ML V3 | 23 | good coverage, wrong deployment class |
| Qwen3-TTS | 10 (EN, DE, FR, ES, IT, PT, ZH, JA, KO, RU) | good, wrong deployment class |

Important caveat carried over from the sherpa issue tracker (#3755, Jul 2026):
the **official sherpa Pocket bundles are English-only today**. French/Spanish/
German/Italian/Portuguese packs are requested but **not yet published** —
using Pocket for European markets would require custom export/packaging
until sherpa ships them.

---

## 6. Output-storage accounting

The model is only half the storage story; rendered audio is the other half
(16-bit PCM, per hour of audiobook):

| Engine output | Storage per hour |
|---|---:|
| 24 kHz mono (Pocket, Kokoro) | ~173 MB |
| 44.1 kHz mono (Supertonic) | ~318 MB |
| 48 kHz stereo (MOSS) | ~691 MB |

MOSS's stereo/48 kHz output roughly **doubles the encode+download cost** of
rendered chapters vs Supertonic even when re-encoded to M4B/AAC. With chapter
pre-generation this is acceptable for premium books, not for "pack size
minimized" defaults.

---

## 7. Licensing summary

| Model | Code | Weights | Commercial | Notes |
|---|---|---|---|---|
| Supertonic 3 | MIT | **OpenRAIL-M** | yes, with use restrictions | already displayed in `LicensesScreen.kt` |
| Pocket TTS (Kyutai) | MIT | **CC-BY-4.0** | yes, attribution required | weights carry prohibited-use clauses; **no impersonation** — matches our preset-only stance |
| MOSS-TTS-Nano | — | **Apache-2.0** | yes | cleanest license; add NOTICE |
| Kokoro | — | Apache-2.0 | yes | |
| Chatterbox family | MIT | MIT (weights claimed MIT on card) | yes | cloning-first product; we would use presets only |
| Qwen3-TTS | — | Apache-2.0 | yes | |

Both OpenRAIL-M (Supertonic) and CC-BY (Pocket) are acceptable per the agreed
legal posture, provided the app offers no impersonation/voice-upload feature.

---

## 8. Decision matrix

| Option | EN + Europe | Preset voices | On-device perf | Pack size | Expression control | Verdict |
|---|---|---|---|---|---|---|
| Supertonic 3 | Excellent | 10 presets | **Proven in app** | ~138 MB | **21 inline tags** | **keep as baseline** |
| Pocket TTS | primary 7 markets only | ~26 pre-made voices | likely good; must test | ~94 MB (EN) | none | **pilot first** |
| MOSS-TTS-Nano | broad 20 langs | unverified presets / needs prototype | unverified; DIY ONNX path | ~765 MB | none | **second prototype** |
| Kokoro-82M | EN/ZH | yes | slower than Supertonic | ~98–140 MB | none | rejected |
| Chatterbox Nano | EN only | no practical presets | CPU-friendly claim, no Android path | ~1.85 GB | tags | rejected (over budget, EN-only) |
| Chatterbox Turbo / ML V3 | 23 langs | reference-audio based | GPU class | 2.85–3.05 GB | strong | server pre-render only |
| Qwen3-TTS 0.6B / 1.7B | 10 langs | presets + VoiceDesign | GPU class | 2.38–4.33 GB | very good (NL delivery directions) | server pre-render only |

---

## 9. Recommendation

### Phase A — Supertonic stays the default
No switch until a candidate beats it in *both* quality and acceptable speed.
It is the only option proven across all 31 catalog languages with working
expression tags, already wired into `TtsEngineManager`, `BookReprepper`,
`ImportPipeline`, and the license screen.

### Phase B — Pocket TTS pilot (EN/FR/DE/PT/IT/ES/NL)
- Use the ~94 MB int8 English bundle first; expose as an **optional
  experimental engine/download** — not a default switch.
- Pre-made voices only; no reference-upload/cloning UI.
- Add CC-BY-4.0 attribution to `LicensesScreen.kt`.
- Keep Supertonic as fallback for every other language.
- Expectation: **voice-variety/stability upgrade for major Western European
  markets**, not a dramatic-acting upgrade (no emotion tags).

### Phase C — MOSS-TTS-Nano prototype (only if broad-Europe matters)
Prototype behind a flag, in this order:
1. Confirm usable preset voices exist in the ONNX path without a reference
   clip (the in-repo Android example only runs pre-tokenized demo prompts).
2. Port text pipeline: SentencePiece tokenization + JNI or pre-tokenization.
3. Size check: 765 MB pack passes the budget; verify the 48 kHz stereo→mono
   downmix, or accept ~691 MB/h raw audio.
4. Measure RTF / peak RAM on low-, mid-, and high-end devices vs Supertonic.
5. Do **not** commit unless it beats Supertonic on balanced quality+speed.

### Phase D — Premium expressive books via server pre-render
For maximum character drama, pre-render on server/desktop and ship audio:
- **Qwen3-TTS VoiceDesign** — synthetic character voices directed by natural-
  language delivery instructions (no impersonation), or
- **Chatterbox Multilingual V3** — 23 languages, strong exaggeration control.

Both are GPU/CUDA-class with 2.4–4.3 GB files; they can never be app-embedded
under the 1.5 GB budget.

---

## 10. Evaluation plan (when the pilot starts)

1. **Corpus**: neutral narration chapter; one EN dialogue scene (2 voices);
   one FR/DE/ES chapter; one ≥10 min long-input render.
2. **Devices**: low-end, mid-range, flagship.
3. **Metrics**: RTF, peak RAM, chapter wall-clock, Whisper WER (resampled to
   16 kHz), MOS/AB preference vs Supertonic, voice consistency across a
   chapter, long-input drift, per-hour audio storage.
4. **Decision gate**: Pocket passes → optional engine for its 7 languages;
   MOSS passes → evaluate replacing Pocket or serving as the 20-language
   premium engine; neither passes → Supertonic stays, premium content moves
   to server pre-rendering.

---

## 11. Verification notes

- All pack sizes: HF `api/models/<id>/tree/main?recursive=true` and the
  sherpa-onnx `tts-models` release assets, queried Nov 2026.
- Pocket speed claim: kyutai.org Pocket TTS technical report (2-core Apple
  M4, ~6× realtime) — **not** verified on Android.
- MOSS 4-core realtime claim: project README / r/LocalLLaMA — **not**
  verified on Android; the in-repo `examples/android_onnx_runtime` does not
  yet demonstrate production text→speech on device.
- sherpa Pocket language-pack limitation: k2-fsa/sherpa-onnx issue #3755
  (Jul 2026) — non-English official bundles pending.
- Runtime RAM numbers are not published by any of these projects; the
  ~1.5–2× weights heuristic is an estimate, not measured.

---

## 12. Practical device check — Samsung Galaxy S25 Ultra

The 1.5 GB budget is a *download-UX* limit, not a hardware limit. To sanity
check against real silicon, the reference device is the Galaxy S25 Ultra
(launch Jan 2025, ~1.5+ years old at the time of writing — an "old flagship",
still a useful floor for "what will run"):

| property | value |
|---|---|
| SoC | Snapdragon 8 Elite for Galaxy |
| CPU | 2× Oryon @ 4.47 GHz prime + 6× Oryon @ 3.53 GHz — **8 big cores, no little cores** |
| Geekbench 6 | ~3 100 single / ~9 800 multi |
| RAM | 12 GB (16 GB variant exists) LPDDR5X |
| GPU | Adreno 830 |
| NPU | Hexagon (ONNX Runtime QNN EP available) |
| Thermal | vapor-chamber class; sustained all-core loads throttle after ~10–20 min — schedule render batches |

### 12.1 On-device speed anchors (verified, external)

| Anchor | Result |
|---|---|
| Kokoro-82M, **CPU**, audiobook reader app on Snapdragon 8 Elite (Z Fold 7, same chip) | **~2.8× realtime** (r/LocalLLaMA, Oct 2026) |
| Kokoro-82M, **GPU** (adreno-llms OpenCL), Adreno 620 — a 2020 mid-range GPU | RTF ≈ 0.93, i.e. ~1× realtime even on far weaker silicon than the 830 |
| Supertonic 3 vs Kokoro on the same desktop CPU | Supertonic ~2× faster (RTF 0.30 vs 0.57), so the 8-Elite CPU anchor is a *conservative* bound for Supertonic-class ONNX models |

Reading of the anchors: any **~100M-parameter ONNX TTS** runs comfortably on
this chip on CPU alone, and the GPU/NPU paths offer headroom. Also, per the
Speech Central write-up, a device slower than realtime is still perfectly
usable **for chapter pre-generation** — only live sentence playback needs
>1× realtime. Our app pre-generates chapters, which lowers the bar for heavy
candidates; what matters then is RAM fit and the existence of a runtime.

### 12.2 Per-model verdict on the S25 Ultra

| Model | Pack | Est. native RAM | Android runtime today | Expected speed on 8 Elite | Verdict |
|---|---|---|---|---|---|
| Supertonic 3 int8 | 138 MB | ~0.3 GB | **shipped in app** | > 2.8× RT (anchor) | **Capable now** — stays |
| Pocket TTS int8 (EN) | 94 MB | ~0.3–0.5 GB | sherpa-onnx | est. 2–5× RT | **Capable now** — pilot; language bundles pending |
| Kokoro-82M int8 | ~171 MB | ~0.4 GB | CPU (sherpa), GPU (OpenCL impl) | 2.8× RT **verified on this chip** | Capable — caution: eSpeak-NG/GPL in some Android impls |
| MOSS-TTS-Nano ONNX | 765 MB | ~1.5–2 GB | ONNX Runtime yes; tokenization DIY | est. 1–2.5× RT CPU; GPU path possible | **Capable with integration work** — flagship makes this prototype cheap to test |
| Chatterbox Nano | 1.85 GB | ~2.5–3 GB | **none** (PyTorch/meanflow) | unknown; desktop 8-core claim ⇒ est. ~1–2× RT *if ported* | Hardware-feasible, runtime port is the blocker; breaches 1.5 GB |
| Chatterbox Turbo | 2.85 GB | ~3.5–4 GB | none | n/a | Not sensible on device |
| Chatterbox ML V3 | 3.05 GB | ~4–5 GB | none (CUDA-first) | est. RTF 10–30 on CPU | RAM/fp32 peak borderline on 12 GB; render times useless — **no** |
| Qwen3-TTS 0.6B | 2.40 GB | ~2.5–3.5 GB | none; AR-LM + codec decode | 12 tokens per second-of-audio; est. ≤1× RT even quantized — unproven | **Not today**; only quantized-0.6B research path |
| Qwen3-TTS 1.7B | 4.31 GB | ~5–6 GB | none | worse | **No** — server pre-render |

### 12.3 What this changes (and what it does not)

- **Confirms**: Supertonic, Pocket, Kokoro-class engines run easily on this
  chip — the old-flagship floor is far above what they need.
- **Upgrades MOSS** from "risky on mid-range" to "reasonable on flagships":
  12–16 GB RAM covers its ~2 GB native footprint, 8 fast cores make CPU
  realtime plausible, and QNN/Adreno paths may accelerate the ONNX graphs.
  Still gated by preset-voice availability and the tokenization port.
- **Does not rescue** Chatterbox ML/Turbo or Qwen3-TTS: their problem is not
  the chip — it is the absence of any supported mobile runtime and
  (for the GPU-class ones) prohibitively slow CPU inference even for batch
  pre-rendering.
- **Budget choice is unchanged**: 12 GB of RAM does not mean 4 GB packs —
  pack size remains a download/UX/product decision. Flagship users *could*
  get a larger optional premium pack, while the default stays within budget.

---

## 13. Expressive-TTS matrix — human-likeness for runnable models

Two independent kinds of quality evidence, deliberately kept on separate
scales (UTMOS is a 1–5 predicted-MOS; TTS Arena Elo is blind human
preference votes — the two numbering systems cannot be mixed):

### 13.1 Naturalness scores (verified)

**UTMOS objective MOS** — 6-configuration CPU benchmark (4-core Xeon
8272CL, 180 timed runs, `utmos22_strong`; heyneo/Neo benchmark, Jul 2026,
code+samples public in `gauravvij/kokoro-tts-vs-supertonic-3-tts`):

| Config | UTMOS MOS | By-ear verdict |
|---|---:|---|
| Supertonic-3, 2-step (speed mode) | 1.53 | robotic — **do not ship this config** |
| Supertonic-3, 5-step (quality) | **4.32** | intelligible+usable, "lacks some warmth" vs Kokoro |
| Kokoro-82M (ONNX) | **4.44** | "human-like", natural prosody |
| Kokoro-82M (PyTorch) | 4.46 | identical to ONNX |
| Pocket TTS (preset `alba`) | **4.10** | clean, well-modulated, no vocoder buzz |
| Inflect-Nano-v1 (4.6M) | 3.48† | buzzy/robotic by ear — UTMOS over-rates |

† documented UTMOS failure mode on small HiFi-GAN vocoders: rewards clean
output even when robotic. Corroborates relying on ear + AB tests.

**TTS Arena blind human preference** (Hugging Face, loaded Oct 10 2026;
39 models, 16.7k votes):

| Rank | Model | Elo | Notes |
|---|---|---:|---|
| #27 | ElevenLabs Eleven v3 *(cloud reference)* | 1501 ± 34 | quality ceiling reference |
| #28 | **Chatterbox** | **1481 ± 19** | **highest open-weight model** |
| #29 | **Kokoro v1.0** | 1478 ± 22 | within error margin of Chatterbox |
| #1 | CastleFlow v1.0 *(cloud, retired)* | 1561 | not applicable |

Supertonic 3, Pocket TTS, and MOSS-TTS-Nano are **not on the TTS Arena
board** — their naturalness evidence is UTMOS-based only.

### 13.2 Expressiveness matrix (models runnable on an S25 Ultra)

| Model | Naturalness evidence | Expression / acting control | Voice variety | Audio spec | On-device status |
|---|---|---|---|---|---|
| **Supertonic 3** | UTMOS 4.32 (5-step) | **21 inline expression tags — the only runnable model with inline acting control** | 10 presets (F1–F5/M1–M5) | 44.1 kHz mono | **shipped in app** |
| **Pocket TTS** | UTMOS 4.10 | none — no emotion/pause control | ~26 pre-made voices | 24 kHz mono | runs via sherpa; EN bundle today |
| **Kokoro-82M** | UTMOS 4.44 + Arena 1478±22 (best blind-vote evidence of the runnable set) | none — style comes only from voice choice | 54 voices (28 EN in SC-Kokoro) | 24 kHz | runs; EN/ZH focus; GPL caveat in some Android impls |
| **MOSS-TTS-Nano** | no third-party score; 48 kHz stereo + 16-codebook RVQ tokenizer claims high fidelity | no tags; expression only via reference-audio styling / VoiceGenerator prompts; preset availability unverified | cloning-first; built-ins unconfirmed | 48 kHz stereo | prototype only |
| (ref) Chatterbox family | Arena 1481±19 — best open-weight | exaggeration/emotion controls, strongest acting | many | 44.1 kHz | not runnable (no mobile runtime) |
| (ref) Qwen3-TTS 1.7B VoiceDesign | vendor-class quality; no arena rank found for open ckpt | natural-language delivery directions — strongest "director" model | VoiceDesign unlimited | 24 kHz class | not runnable (server pre-render) |

### 13.3 What the matrix means for the audiobook switch

1. **Naturalness is not the differentiator anymore.** Supertonic (4.32),
   Pocket (4.10), and Kokoro (4.44) sit in the same natural band; the gaps
   are ~0.1–0.3 UTMOS. On blind Elo, the open-weight leaders cluster within
   ±25 of each other. Nothing here is "robotic" except speed-mode configs.
2. **What decides expressive audiobooks is acting control, and only
   Supertonic has it on device** (21 inline tags). Pocket/Kokoro/MOSS sound
   natural *but flat* for dialogue scenes — the same voice for a fight and
   for a love scene.
3. **Where others genuinely win over Supertonic:**
   - Pocket: 2.6× the preset voices; narrator variety for anthologies.
   - Kokoro: slightly better neutral-narration naturalness (UK/US voices).
   - MOSS: 48 kHz stereo fidelity + potential voice design — but unproven.
4. **Expression ceiling stays at Eleven v3 / Chatterbox / Qwen3 level**,
   which are pre-render-only on mobile; the realistic premium path is
   server-rendered packs, not an engine swap.
5. **Never run Supertonic at 2-step**: UTMOS collapses to 1.53 (robotic).
   Current app config is the default 5-step path — keep it.

Practical reading: a Pocket/Kokoro pilot is a *voice-variety* upgrade; a
switch motivated by "more human-sounding" alone is **not** supported by the
data — naturalness differences among the runnable set are marginal, and the
candidates with a big authenticity jump are exactly the ones that cannot run
on device.

---

## 14. Kokoro-82M — summary (full deep dive moved to `docs/KOKORO.md`)

Kokoro was identified as the most natural **runnable** engine (§13), so it got
its own standalone deep dive: **`docs/KOKORO.md` — with the full evidence
tables, license sheet, pilot checklist, and source appendix.** Key findings:

- **Naturalness**: UTMOS **4.44** vs Supertonic 4.32; TTS Arena blind Elo
  **1478 ± 22** (#29) — statistically tied with Chatterbox (1481), ~20 Elo
  below ElevenLabs v3.
- **Edge realtime verified externally**: 2.8× realtime on Snapdragon 8 Elite
  CPU; fp32 RTF **0.48–0.51** on Tensor G5 (4 threads); OpenCL GPU path hits
  ~1× realtime even on a 2020 mid-range Adreno 620.
- **Ship fp32 (~334 MB), never int8 on ARM**: int8 is slower (0.62), adds
  `fs/5` iSTFT tone artifacts, and had an int8-only sentence-garbage bug
  (fixed in sherpa-onnx v2.0.1, Oct 2026).
- **English specialist**: graded EN voices (af_heart A / af_bella A- /
  bf_emma B-); exactly one thin French voice; thin ES/IT/PT-BR; German,
  Dutch, Nordic, Slavic **absent**. Verdict: ADD for English, keep
  Supertonic for the rest of Europe (per-language engine selection).
- **No expression control** (speed + voice choice only) — an EN switch
  trades our 21 inline tags for ~0.1 UTMOS of naturalness.
- **License**: Apache-2.0 weights + CC-BY attributions (Koniwa/SIWIS); the
  espeak-ng GPL problem was removed upstream in sherpa-onnx v2.0
  (Oct 2026). Remaining pilot risk: Kokoro G2P via lexicon (weak OOV on
  fiction names) or external misaki tokens.
- **Long-form**: 510-phoneme hard cap, truncation corrupts audio, chunk to
  the 100–200-token goldilocks window.

Pilot de-risking order and open verification items: `docs/KOKORO.md` §8–§9.