# Alternative TTS models for this machine

Research notes: which models are worth using on **Quadro P2000 (5 GB VRAM,
`sm_61`) + i7-7700 (4 cores, AVX2, no AVX512) + 24 GB RAM**, and how they
compare to the Higgs Audio v3 pipeline in this repo.

## The constraint that decides everything

Higgs Audio v3 is a **4.65 B-parameter autoregressive** model. Per second of
audio it performs 25 sequential decoder steps, each reading the full
(int4) weight matrix — so it is **memory-bandwidth bound**, not compute
bound. Two consequences:

1. On this CPU it runs at **RTF ≈ 10** (measured: 10.5 min of audio in ~3 h).
2. **Any model with fewer sequential steps per second of audio is faster**,
   regardless of parameter count.

So the fastest local option is not "the model with the fewest parameters" —
it is "the model that generates many frames per forward pass".

## Candidate table

| model | params | architecture | cloning | expression control | licence | local size |
|---|---|---|---|---|---|---|
| **Higgs Audio v3** (current) | 4.65 B | AR, 25 Hz, 8 codebooks | yes | 43 tags | research + **Creator Use Grant** (audiobooks, even monetised) | 2.3 GB int4 |
| **OmniVoice** (k2-fsa) | 0.6 B | diffusion LM, **32-step non-AR unmasking** | yes | voice design, `[laughter]` | **CC-BY-NC** (weights) | 300 MB int4 |
| **KittenTTS 2** (KittenML) | 1.7 B | AR, **1.58-bit ternary** weights | yes (in-context) | 20 tags (`[joyful]`, `<sigh>`, `(((emph)))`) | Stellon Community (free < $1 M revenue) | 954 MB |
| **KittenTTS 0.8 nano** | 15 M | **single forward pass** | 8 preset speakers only | none | **Apache-2.0** | **24 MB** int8 |
| **Kokoro-82M** | 82 M | **single forward pass** | no | none | **Apache-2.0** | 86 MB q8 |
| **Qwen3-TTS-12Hz-0.6B** | 0.6 B | AR, **12 Hz** (half of Higgs) | yes (3 s) | natural-language `instruct` | **Apache-2.0** | 1.2 GB |
| **Ming-3.0-tts-0.5B** | 0.5 B | 12 Hz AR | yes | — | **MIT** | 3.0 GB (ONNX partial) |

## Speed reasoning (per second of generated audio)

| model | sequential steps / s-audio | weight traffic per step | rough RTF on this CPU |
|---|---|---|---|
| Higgs int4 | 25 | 2.3 GB | **~10 (measured)** |
| Qwen3-TTS-0.6B int8 | 12 | 0.6 GB | ~1.5–3 (needs measuring) |
| KittenTTS 2 (ternary, llama.cpp) | codec-rate | ~1 GB | ~2–5 (needs measuring) |
| OmniVoice int4 | 32 forwards over the **whole** sequence | 0.3 GB each | compute-bound — the published RTF 0.48 was measured on a server CPU |
| Kokoro-82M | 1 forward | 0.09 GB | ~0.2–0.5 (needs measuring) |
| KittenTTS 0.8 nano | 1 forward | 0.02 GB | ~0.1 (needs measuring) |

**Important caveat on OmniVoice's published RTF.** The model card for
`onnx-community/OmniVoice-Onnx` reports *eval RTF 0.483 (fp16) / 0.592
(int4) on CPU*. That number is real but it was measured on a many-core
server CPU. OmniVoice replaces 25 sequential AR steps with 32 *full-sequence*
forward passes, so its cost is dominated by
`32 x 2 x params x sequence_length` FLOPs — on 4 cores that is compute-bound
and may well be *slower* than Higgs here. It needs measuring, not assuming.

## Licensing note (matters for an audiobook)

* **Higgs Audio v3** — research/non-commercial *plus* a Creator Use Grant that
  explicitly permits audiobooks, including monetised creator work, provided
  Boson AI is credited. This is the most permissive option for the use case.
* **OmniVoice** — the *weights* are CC-BY-NC (non-commercial) because of the
  Emilia training data. The ONNX re-upload is tagged Apache-2.0, but that tag
  covers the conversion, not the weights. Monetised use needs a licence.
* **KittenTTS 0.8 / Kokoro / Qwen3-TTS / Ming** — Apache-2.0 / MIT, no
  restrictions.

## Recommendation

For the *full book* (3,220 chapters, ~340 h of audio) on this CPU:

1. **Kokoro-82M or KittenTTS 0.8 nano** are the throughput winners
   (Apache-2.0, single forward pass, 24–86 MB). They have no voice cloning
   and no emotion control, so they are a *quality* compromise, not a
   like-for-like swap.
2. **Qwen3-TTS-12Hz-0.6B** is the best like-for-like replacement: Apache-2.0,
   3-second voice cloning, natural-language emotion/prosody instructions, and
   half the token rate of Higgs.
3. **KittenTTS 2** is interesting for expression control (20 tags, close to
   Higgs' 43) but the licence caps commercial use at $1 M revenue.
4. **Keep Higgs** if per-chapter quality matters more than throughput, and
   use the Colab T4 notebook (`colab/higgs_tts_audiobook.ipynb`) for it.

## Measured on this machine

Same 3-sentence passage (~60 words, ~22 s of speech) through each backend,
scored identically with Whisper `small.en`. Reproduce with:

```powershell
.\.venv-alt\Scripts\python.exe scripts\bench_alternatives.py `
    --backends kitten kokoro omnivoice higgs
```

| backend | RTF | vs. Higgs | WER | coverage | notes |
|---|---|---|---|---|---|
| **KittenTTS nano 0.1** | **0.14** | **80× faster** | **0.0 %** | 100 % | 15 M params, 24 MB, Apache-2.0, 8 preset speakers, no cloning, no expression tags |
| **Kokoro-82M** | **0.50** | **22× faster** | **0.0 %** | 100 % | 82 M params, 86 MB, Apache-2.0, 15 female + 3 male speakers, no cloning, no expression tags |
| OmniVoice int4 | 10.88 | 1.0× (no gain) | 100 % | 4 % | 0.6 B, 300 MB, **CC-BY-NC**; output was garbage on this build |
| Higgs Audio v3 int4 | 11.25 | 1× (baseline) | 2.7 %* | 100 %* | 4.65 B, 2.3 GB, Creator Use Grant; 43 expression tags, cloning |

\* Higgs's fair numbers come from the Chapter 1 run (200-char chunks, retries
enabled): **mean WER 2.7 %, 121/125 segments under 10 % WER**. The benchmark
row renders the whole passage as a single chunk with one attempt, which is
exactly the configuration that degenerates — it is included to show the
*un-chunked* baseline, not the pipeline's real quality.

### The headline

**Kokoro and KittenTTS are 22–80× faster than Higgs on this hardware and
produce perfectly intelligible speech.** The reason is architectural: both
generate the whole utterance in a **single forward pass** (StyleTTS2-style,
non-autoregressive), whereas Higgs performs 25 sequential decoder steps per
second of audio and is therefore memory-bandwidth bound.

At RTF 0.14–0.50, the full 3,220-chapter book (~340 h of audio) becomes:

| model | wall-clock for the full book |
|---|---|
| KittenTTS nano | **~1.5 days** |
| Kokoro-82M | **~4 days** |
| Higgs int4 (current) | ~10–12 weeks |

### The trade-off

Kokoro and KittenTTS 0.8 give up the two things Higgs is best at:

* **no voice cloning** — so the "one consistent bespoke narrator for 3,220
  chapters" property has to come from a preset voice instead;
* **no expression control** — no `[joyful]` / `<sigh>` / `(((emphasis)))`
  tags, so the emotional arc in `work/emotion_maps/ch0000.json` is not
  available.

Neither has voice cloning *and* the speed. If you need cloning, expression
control and a permissive licence in one model, the remaining candidate is
**Qwen3-TTS-12Hz-0.6B** (Apache-2.0, 3-second cloning, natural-language
`instruct` for emotion, 12 Hz instead of 25 Hz) — it was not benchmarked
here because it needs a PyTorch runtime and Python ≤ 3.13 for its package.
`KittenTTS 2` also has cloning + 20 expression tags, but the Stellon
Community licence caps commercial use at USD 1 M in annual revenue, and its
package needs Python ≤ 3.13 as well.

**A pragmatic hybrid** is possible: Kokoro for the body of the book (fast,
cheap, consistent) and Higgs only for the passages that carry real emotional
weight. Or simply accept a preset voice throughout and use Higgs's
instruct-free prosody.

## How to reproduce

```powershell
# weights (Kokoro 86 MB + KittenTTS 24 MB + OmniVoice 1.3 GB)
.\.venv-alt\Scripts\python.exe scripts\fetch_alternatives.py all

# benchmark (3-sentence passage, ~22 s of speech, per backend)
.\.venv-alt\Scripts\python.exe scripts\bench_alternatives.py --backends kitten kokoro omnivoice higgs
```

`scripts/bench_alternatives.py` renders the same passage through each backend
and reports RTF plus a Whisper-measured WER, so the comparison is objective
rather than a claim.

### Environment gotchas found while benchmarking

* **espeak-ng**: the KittenTTS backend needs it for phonemisation.
  `pip install espeakng-loader` provides the DLL, and the script wires up
  `PHONEMIZER_ESPEAK_LIBRARY` / `PHONEMIZER_ESPEAK_DATA_PATH` automatically
  (`setup_espeak()`).
* **KittenTTS 0.8 weights** need the `kittentts` **0.8** wheel, which requires
  Python <= 3.12; this machine only has **Python 3.14**, so the benchmark uses
  the **0.1** weights (which work with the published package on 3.14). The 0.8
  model also changed the speaker-embedding layout, so the 0.1 package feeds
  it the wrong `style` shape.
* **Kokoro voices**: the ONNX repo ships one raw `.bin` per voice but
  `kokoro_onnx` wants a single `.npz` —
  `scripts/build_kokoro_voices.py` converts them.
* **Kokoro v1.0 ONNX** expects a rank-2 `style` input and a float `speed`;
  `kokoro_onnx` passes rank-1 and int32. The benchmark patches the session
  (`KokoroBackend.__init__`).
* **Kokoro returns a `(1, N)` array**; soundfile reads 2-D as (frames,
  channels), so it must be flattened before writing.

## Side finding: the Higgs int4 render is *deterministic*

On a fixed thread count, ONNX Runtime reduces floating point in a fixed
order, so **the same text + seed + sampling parameters always produce
byte-identical audio**. Two consequences for this pipeline:

1. Re-rendering a bad chunk with the same parameters changes nothing — the
   pipeline therefore varies **both the seed and the sampling parameters** on
   each retry (`fallback_configs` in `scripts/synthesize.py`).
2. A handful of chunks are bad under *every* parameter combination. Those can
   only be fixed at the text level, which is why `scripts/prep_text.py`
   supports pronunciation overrides:

```json
{"fixes": [{"find": "\"Tsk. What a rip-off.\"", "replace": "\"Tssk. What a rip-off.\""}]}
```

`scripts/repair_chapter.py --fixes FILE` drops the now-stale segments so they
are re-rendered. This is standard audiobook practice (respelling proper
nouns, archaic words and sound effects).

Measured on Chapter 1: 125 segments, **mean WER 2.7 %**, 121/125 under 10 %
WER, and 4 segments that no parameter combination could fix (all short
utterances containing a hard word — "Tsk", "grunted", "sighed").
