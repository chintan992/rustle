# Other TTS options — research and shortlist

> **Note (after the restructure).** This survey was made before the cleanup. The environments and model weights used for it were removed; the reasoning and the measurements are kept because they are what selected the engine that is now in use (`docs/SUPERTONIC.md`).

**Status: research only. Nothing in this document has been installed, downloaded
or benchmarked on this machine.** The measured numbers for Kokoro and
KittenTTS are in `docs/KOKORO_PLAN.md` and `docs/KITTENTTS.md`. Everything
else here is *reported* data from other sources, marked as such.

---

## 1. The brief, restated

The requirement has been narrowed to:

| criterion | weight |
|---|---|
| **Fast** on this CPU (i7-7700, 4 cores, AVX2, no AVX512) | **primary** |
| **Expression control** — an explicit way to direct delivery | **must have** |
| Voice cloning from `refs/jenny.wav` | *optional* — drop it if a model is fast enough |
| Permissive licence for a (possibly monetised) audiobook | important |

This is a much narrower target than "best open TTS model", and it rules out
most of the well-known candidates.

### 1.1 The gap in the current pipeline

* **Kokoro-82M** is fast (measured RTF 0.57 here) but the tts-bench notes
  record its official position: *"cannot laugh. cannot sound extremely angry"*
  — **no expression control at all**.
* **KittenTTS 2** has excellent cloning and 20 expression tags, but measured
  **RTF 16.4 (PyTorch) / 28.6 (C++)** here, because this CPU has neither
  AVX512 nor AMX for its ternary kernels. 232–405 days for the book.
* **Higgs Audio v3** has 43 expression tags but RTF ~10–11 here.

So there is a real gap: **nothing measured so far is both fast and
expressive.**

---

## 2. How the candidates were evaluated

The main source is **[tts-bench](https://github.com/5uck1ess/tts-bench)**
(5uck1ess), which tracks **83 models** and measures them on three rigs:
Windows desktop (Ryzen 9 9950X3D + RTX 5090), Linux (Ryzen 9 5900XT +
RTX 3090) and Mac M4. It publishes, per model: cold/warm TTFA, **RTFx**
(`audio_seconds / generation_seconds`, so 1.0x = realtime and higher is
faster), memory, plus UTMOS / WER / cloning-SIM scores, and a separate
**expressive-control** document giving the exact syntax per model.

Its expressive-control taxonomy is the useful part:

| code | meaning |
|---|---|
| `tags` | inline cues in the text: `(laughs)`, `[sigh]`, `<laugh>` |
| `desc` | natural-language style instruction in a separate field |
| `emo-ref` | emotion from a separate reference clip or an 8-number vector |
| `knob` | numeric/preset parameter: exaggeration, style enum, pitch/speed |
| `—` | **none** — for cloning models, expression just follows the reference |

**A note on transferring the numbers.** The bench rig is a 16-core
AVX512 desktop; this machine is 4-core AVX2. The two are not comparable
directly. The one calibration available is Kokoro: this machine measured
RTF 0.57 (RTFx 1.75) against the bench's 13x on the 9950X3D — a **7.4x**
gap. But KittenTTS nano measures RTF 0.144 here (RTFx 6.9x) against the
bench's 6.6x — **no gap at all**. So the transfer factor is model-specific
(ONNX int8 scales very differently from PyTorch) and **every candidate must
be measured here**. The bench numbers below are a *screening* filter, not a
prediction.

---

## 3. The shortlist: fast **and** expressive

Filtered from the 83 tracked models to those that are (a) fast on CPU and
(b) have explicit expression control.

### Tier 1 — the serious candidates

| model | params | bench CPU RTFx | expression | licence | notes |
|---|---|---|---|---|---|
| **Supertonic 3** | 99 M | **6.1x** Windows / 4.2x Mac cpu | **tags** — 10 inline tags (`<laugh>`, `<breath>`, `<sigh>`, …) | MIT code + **OpenRAIL-M** weights | **pure ONNX, no torch dependency**; 31 languages; 44.1 kHz output; English WER 2.06 on Minimax-MLS (vs Qwen3-TTS 2.25, VoxCPM2 2.11) |
| **OpenVoice v2** | ~100 M | 2.49x Mac cpu | **knob** — `style=` enum: default, friendly, cheerful, excited, sad, angry, terrified, shouting, whispering | MIT | a *style enum* rather than free-form; very permissive licence |
| **StyleTTS 2** | ~148 M | 3.82x Mac cpu | **knob** — `embedding_scale` (classifier-free guidance), `alpha`/`beta` timbre-vs-prosody blend | MIT | Kokoro's own architectural family, so the existing pipeline knowledge transfers directly |
| **sanoTTS Heart-Nano** | 294 K | **104x** Windows (50 ms warm TTFA) | **knob** | **GPL-3.0** | the speed champion by a wide margin; 1 preset voice, 24 kHz; quality is the open question |
| **Phonon 31m** | 41 M | — | **knob** | Apache-2.0 | 1.00 % WER on Seed-TTS English; 1 preset voice; 24 kHz |

### Tier 2 — fast, with expression, but less proven

| model | params | expression | licence | notes |
|---|---|---|---|---|
| SupraTTS 0.1 Beta | 43.5 M | 3 knobs | MIT | 22.05 kHz; 1 preset voice |
| Scylla's Band | ~103 M | 6 knobs | Apache-2.0 | 24 kHz, 10 preset voices, 4 languages |
| Vaniq-Edge | 8.91 M | knob | MIT | 24 kHz; **fastest on CUDA (RTX 5090) at 298x RTFx** |
| Inflect-Nano v2 / Micro v2 | 3.96 M / 9.36 M | 2 knobs | Apache-2.0 | fixed voice, deterministic seeds, long-text handling |
| Magpie-TTS 357M | 357 M | emotion voices (`.Aria.Happy`, `.Pascal.Calm`) | NVIDIA Open Model License | NeMo is heavy on CPU (0.5x Mac) → likely too slow here |
| SteerTTS | 40.8 M | **12 knobs + tags**, 2 825 preset styles | MIT code / **CC-BY-NC 4.0 weights** | non-commercial weights |

### Tier 3 — expressive but too slow on this CPU

| model | bench CPU | expression | licence | why rejected |
|---|---|---|---|---|
| **ChatterBox** 1.2 B | **0.30x** Windows | **knob** — `exaggeration` 0.25–2.0 + `cfg_weight` | MIT | 1000 diffusion steps; GPU-targeted |
| **ChatterBox Turbo** 744 M | 0.8x Mac cpu | **tags** — `[laugh]`, `[chuckle]`, `[cough]` | MIT | borderline; Turbo *silently ignores* `exaggeration`/`cfg_weight` (confirmed by Resemble) |
| **Qwen3-TTS 1.7 B** | 0.2x Mac cpu | **desc** — plain-language `instruct` | Apache-2.0 | too heavy |
| **Qwen3-TTS 0.6 B CustomVoice** | — | **none** | Apache-2.0 | **the `instruct` control is not supported at 0.6 B** — the `qwen-tts` package ignores it despite the model card. This kills the "small Qwen3-TTS" option |
| **Qwen3-TTS 0.6 B Base** | — | none | Apache-2.0 | Base has no expression control at all; reference-only; bench also hit a "decode runaway" (12.9 s of audio on one call, 655.3 s on the next) |
| Orpheus TTS 3 B | gpu-class | **tags** — `<laugh>`, `<sigh>`, `<groan>`, `<gasp>`, … (8 English voices) | Apache-2.0 (card) + Meta Llama terms | 3 B wants a GPU |
| Dia / Dia2 1.6 B / 1 B–2 B | gpu-class | **tags** — a full documented list of 22 (`(laughs)`, `(sighs)`, `(gasps)`, `(singing)`…) | Apache-2.0 | needs a GPU; ~2 min per output |
| IndexTTS-2.5 0.97 B | 0.1x | **emo-ref** (8-number vector) + **desc** + knob | bilibili Model Licence | most granular control in existence, but non-commercial unless a deal is signed |
| Zonos v0.1 | 0.15x | **emo-ref** (8 weights) + `speaking_rate` + `pitch_std` | Apache-2.0 | too slow; asks for 6 GB+ VRAM |
| Fish Audio S2 Pro 4 B | gpu-class | **free-form tags** — 15 000+ tags like `[whisper in small voice]` | Research — non-commercial | far too heavy |
| VibeVoice 1.5 B | 0.06x | none (spontaneous only) | MIT | 70 s TTFA on CPU; long-form times out |
| Echo-TTS 2.8 B | gpu-class | **tags** | CC-BY-NC-SA | CUDA-only, no CPU path |
| Higgs Audio v3 (current) | ~0.1x | 43 tags | Research + Creator Use Grant | the baseline this is meant to beat |

### Also ruled out, for the record

* **Piper** — 47x on Windows CPU, MIT engine (now GPL-3.0), 40+ languages. But
  **no affect control**: only espeak phoneme injection `[[ . ]]`, which is
  pronunciation, not emotion. Still worth keeping as a *pronunciation-override*
  tool, and it is the fastest thing available.
* **XTTS-v2** — Coqui Public Model License, non-commercial, and Coqui has
  shut down so there is no one to buy a commercial licence from.
* **F5-TTS** — weights CC-BY-NC (non-commercial).
* **CosyVoice 3 0.5 B** — Apache-2.0 with `desc` control, but the bench notes
  it is Linux-only (CUDA, torch 2.3.1) with no Windows path.
* **MARS5-TTS** — AGPL-3.0: non-commercial unless licensed from CAMB.AI.
* **VoxCPM2** — Apache-2.0, `desc` control, but timed out (>600 s/cell) on
  the bench CPU rig.
* **NeuTTS Air / Nano** — Apache-2.0, 1.3–2.8x CPU, GGUF, but **no
  expression control**.
* **Pocket-TTS v4** — MIT, 2.9–8.8x, 100 M, but no emotion or pause control.
* **KittenTTS nano / mini 0.8 ONNX** — already measured here at RTF 0.144
  and 0.313, but **no expression control**.
* **MeloTTS, Soprano, LuxTTS, ZipVoice, Sopro V2 Turbo, MOSS-TTS-Nano,
  Sesame CSM, Miso, Audio8, Inflect, dots.tts, LongCat-AudioDiT, FireRedTTS3,
  OpenAudio S2, Breeze TTS 2, DramaBox, Step-Audio-EditX, Mixture/MetaVoice** —
  no expression control, or the wrong licence, or not benched on Windows.

---

## 4. Licence summary for a monetised audiobook

**Commercial use allowed** (the only ones that also fit the speed+expression
filter are marked ★):

* **★ Supertonic 3** — OpenRAIL-M: commercial, with use restrictions
  (including on impersonation) and attribution.
* **★ StyleTTS 2** — MIT.
* **★ OpenVoice v2** — MIT.
* **★ Phonon 31m, Inflect v2, Scylla's Band** — Apache-2.0.
* **★ SupraTTS, Vaniq-Edge** — MIT.
* Kokoro-82M, Qwen3-TTS, Dia/Dia2, Orpheus (card), Pocket-TTS, CosyVoice 3 —
  Apache-2.0 (but all fail the speed filter here).
* ChatterBox family — MIT.
* Piper — MIT engine, but the active fork is GPL-3.0 and each voice has its
  own data licence; the project describes itself as for personal use and
  research.

**Not commercial without a separate agreement:** XTTS-v2 (CPML), F5-TTS
weights (CC-BY-NC), Fish Audio S2 Pro, IndexTTS-2.5 (bilibili), Spark-TTS
(CC BY-NC-SA), ChatTTS (CC BY-NC 4.0), OmniVoice weights (CC-BY-NC),
SteerTTS weights (CC-BY-NC).

**Copyleft, so treat as non-commercial unless cleared:** Piper (GPL-3.0),
MARS5-TTS (AGPL-3.0), CosyVoice (GPL-3.0), sanoTTS (GPL-3.0).

Note the licensing trap in general: Apache-2.0 and MIT are silent on
impersonation, but OpenRAIL-M and Pocket TTS explicitly forbid cloning or
impersonating a real person without consent, and the law is not silent
either way.

---

## 5. Recommended plan (for approval)

### Step 1 — screen the fast+expressive shortlist on this machine

Build one throwaway venv (or reuse `.venv-tts`'s pattern) and measure
**RTF and WER** for each candidate on the same 3-sentence passage and on
Chapter 1:

1. **Supertonic 3** — first choice. Pure ONNX means a tiny venv with no
   torch, so it is also the cheapest to try. 10 expression tags. 44.1 kHz
   output is a bonus for audiobook quality.
2. **OpenVoice v2** — MIT, 9-style enum. Tests the "preset style" approach
   to expression.
3. **StyleTTS 2** — MIT, numeric expression knobs, and architecturally the
   same family as the Kokoro pipeline already in place.
4. **sanoTTS Heart-Nano** — the speed benchmark. If 104x holds up here, it
   changes the economics of the whole book; if the quality is poor it costs
   little to find out.

**ChatterBox Turbo** gets a *conditional* slot: it is MIT, has native
`[laugh]`-style tags *and* cloning (so the jenny requirement is met), but
the bench puts it at 0.8x on an M4 — which on this CPU could be well under
realtime. Worth one measurement because it is the only candidate that would
satisfy *fast + expressive + cloning* simultaneously, if the speed holds.

### Step 2 — if one of them wins, wire it into the existing pipeline

The pipeline is already in place: `tts_pipeline/epub_extract.py` →
`clean_text.py` → `synthesize.py` → `verify.py`, with 3 220 clean chapters
and an objective WER harness. Swapping the engine means a new
`synthesize` backend and a new voice-selection step, not a rebuild.

### Step 3 — keep Kokoro as the fallback

Kokoro remains the safe default: Apache-2.0, measured RTF 0.57, WER 0.0 %
on the test passage and 1.5 % over a full chapter, and the whole pipeline is
already verified end to end. If none of the candidates beat it on
*speed + measured expression effect*, the right answer is to keep Kokoro and
find expression another way (per-sentence voice selection, or speed/pitch
variation per beat).

### Open questions to settle during Step 1

* Does "expressive" mean *tags in the text*, a *style enum*, a *numeric
  knob*, or *free-form natural-language instructions*? The candidates differ
  here, and the Higgs pipeline's `work/emotion_maps/` was written for
  per-line tags.
* Should the audiobook be monetised? That decides whether OpenRAIL-M
  (Supertonic) and GPL-3.0 (sanoTTS) are acceptable at all.
* Is 44.1 kHz output wanted, or is 24 kHz fine? (44.1 kHz is better for
  an audiobook; 24 kHz halves the file size and is what everything else
  here produces.)
* How much of the 340 hours needs real expression, and how much could be
  rendered with the fastest available model? A split — fast model for the
  body, expressive model for the dramatic chapters — may be the best of both.

---

## 6. Sources

* [tts-bench](https://github.com/5uck1ess/tts-bench) — 83 models, speed +
  quality benches, and `docs/expressive-control.md` for exact syntax.
* [Supertonic repository (archived)](https://github.com/supertone-oss-archive/supertonic) —
  model card, English WER table, licence.
* [Chatterbox Turbo model card](https://huggingface.co/ResembleAI/chatterbox-turbo) —
  MIT, paralinguistic tags, one-step decoder.
* [Qwen3-TTS repository](https://github.com/QwenLM/Qwen3-TTS) — Apache-2.0,
  `instruct` control, 3-second cloning.
* [KittenTTS repository](https://github.com/KittenML/KittenTTS) — cloning,
  20 expression tags, Stellon Labs licence.
* [yaps.ai local TTS survey](https://www.yaps.ai/blog/best-local-tts-models)
  — per-model licence table.
* [BentoML 2026 survey](https://www.bentoml.com/blog/exploring-the-world-of-open-source-text-to-speech-models)
  — feature overview.
* [kitten-tts-2-cpp](https://github.com/KittenML/kitten-tts-2-cpp) — the C++
  runtime and `prepare_reference.py`.
