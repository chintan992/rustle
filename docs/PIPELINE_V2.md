# Pipeline v2 — redesign of extract → prepare → render → output

This document redesigns the synthesis pipeline. It is a redesign of **how work is
unitised, cached, and accelerated**, not a rewrite of what is measured and sound: the
six-stage idempotent structure, the ledger contract, atomic writes, the validation
step, the expression-tag allow/ban lists, the 44.1 kHz mono output, and the F2 default
all stay exactly as they are.

The driver is the same fact that shaped the original pipeline: a render costs real
time. v1 spends that time in ways that were right for "render chapters 100–150 once";
v2 spends it right for "import any book, iterate on it, share it with the phone".

---

## 1. Verdict on v1

| v1 behaviour | cost |
|---|---|
| render unit = chapter (one WAV per chapter, segments transient) | re-rendering a chapter because one sentence's emotion map changed: ~2 min for one edited sentence |
| no content addressing | the same text rendered twice is never recognised; a re-prep that shuffles nothing still invalidates work |
| fp32 models, package-default ORT tuning | RTF 0.28 is left on the table: int8 + session tuning + batching are unexplored |
| verification = faster-whisper small.en, default fp32 | ~2 min per chapter even for a sample |
| cleaning rules tuned to one pirate edition | a new book from the Play Store or any normal EPUB imports with the spam/homoglyph machinery aimed at the wrong target |
| deliverable = MP3 per chapter | no M4B with real chapter markers; no cover |
| segment boundaries live only inside `render.py` | the phone cannot share segments, audio, or resume state with the desktop |

None of these break correctness. All of them burn the user's time.

---

## 2. The segment contract (the core change)

**The segment becomes the unit of work everywhere** — desktop, cache, ledger, and
phone. Prep stops being "chapters with segments inside" and starts emitting a
first-class artifact:

```
work/prepared/ch0099.segments.json
[
  {"i": 1, "hash": "9f2c…", "text": "It was the hour of the bell.", "chars": 33,
   "tags": [], "source": "s0001"},
  {"i": 2, "hash": "b7e1…", "text": "<sigh> He did not look up.", "chars": 27,
   "tags": ["sigh"], "source": "s0001"},
  …
]
```

**Hash spec (must be identical on desktop and phone):**

```
hash = sha256( engine_id + "\n" + model_digest + "\n" + voice + "\n" + text_utf8 )
```

* `engine_id` — e.g. `supertonic-3` (the package's own version string is recorded in
  the manifest, not the hash: a pip upgrade of the wrapper must not silently invalidate
  audio; a **model** change must, hence `model_digest`).
* `model_digest` — sha256 of `tts.json` + the four ONNX files' sizes (cheap fingerprint;
  full-file digests live in the manifest for verification).
* `voice` — `F2`, `M3`, …
* `text_utf8` — the segment text exactly as stored.

Because prepared text is pure ASCII, the hash is trivially portable — the same code
path in Kotlin produces the same digest.

**Segmentation spec (shared):** sentence-aligned chunks of **40–300 chars** (120 for
CJK); adjacent sentences merge within a paragraph while under the ceiling; no merge
across a scene break or an inter-segment tag; over-long sentences split at a clause
boundary (comma/semicolon) as a fallback. This is v1's `split_sentences` with a merge
floor added — the segmenter that already survived 259 464 segments, made deterministic
and shared.

---

## 3. Content-addressed render cache

```
cache/audio/<hash>.wav          16-bit mono 44.1 kHz PCM
cache/index.jsonl               hash → wav, seconds, rtf, rendered_at, engine_id
```

* **Render = hash lookup → miss → synthesise → store.** A chapter render becomes
  "concatenate N cache hits", writing `output/wav/ch0099.wav` as today.
* **Emotion-map iteration becomes ~free.** Editing one sentence's cue invalidates one
  hash; the next render re-synthesises one segment (~2–4 s) instead of a chapter
  (~2 min). With the 606-tag emotion-map workload this is the single biggest workflow
  win in v2.
* **Voice/model changes invalidate correctly by construction** — they are in the hash.
  Switching a book to M3 re-renders everything *once*, then is cached thereafter.
* **Stochasticity is respected, not fought.** The cache removes *re-rendering identical
  inputs*, never claims determinism; a deliberate re-roll is `--reroll <hash>` (delete
  one entry, re-render).
* **Retention:** cache entries are content-immortal but disk is finite; `cache gc`
  keeps only hashes referenced by books on disk (whole-book ≈ 108 GB WAV → gc after
  encode, exactly the v1 WAV-deletion flow, now per-segment and safe).
* **The ledger still gates resume** at chapter level; the cache makes segment level
  free. Both stay: ledger answers "is the chapter's audio on disk", cache answers "is
  this exact audio computed".

---

## 4. Desktop speed track (each change gated, in order of expected value)

| # | change | expected | gate |
|---|---|---|---|
| 1 | **ORT session tuning**: explicit `intra_op_num_threads=4`, `ORT_ENABLE_ALL` graph opt, arena growth off, and `io_binding` on the hot path | small–modest; free | RTF ≤ 0.28 or revert |
| 2 | **int8 quantisation** of `vector_estimator` (256 MB) and `vocoder` (101 MB) via `onnxruntime.quantization` static quant, calibrated on a 500-segment sample of prepared text | RTF 0.28 → **≤ 0.20** ( sherpa-onnx's mobile int8 pack exists; the desktop pack is the same graphs) | mean WER over the 5-chapter sample ≤ current 2.0 % + 1 pp; ear check on the test passage |
| 3 | **Batch inference with duration bucketing**: batch segments whose predicted durations are within ±10 % (padding waste bounded) | modest if real; Supertonic supports batch, but flow-matching steps run on padded latents, so padding may eat the win | measure RTF vs #2; keep only if ≥ 10 % better |
| 4 | **Overlap**: prep chapter N+1 while rendering N (producer/consumer queue) | hides ~2 min prep per chapter | wall-clock for the 51-chapter batch |
| 5 | **Parallel workers**: 2 processes × 2 ORT threads vs 1 × 4 on this 4-core box | uncertain; measure | keep the winner of {1×4, 2×2, 4×1} |

Order matters: #2 subsumes #3/#5 decisions (re-measure after each). Every step lands
behind `--engine profile {fp32,int8,int8-batch}` so the measured-good config is always
one flag away.

**Target:** the whole-book render (115 h) → **≤ 80 h**, and a 51-chapter batch
(109 min) → **≤ 75 min**, with WER within 1 pp of today.

---

## 5. Verification upgrade

* faster-whisper `small.en` with `compute_type="int8"` on CPU — typically 2–4× faster
  than fp32 at equivalent WER for verification. Gate: sampled-WER deltas vs fp32 ≤
  0.3 pp on the same chapters; keep fp32 if it drifts.
* Sampling policy unchanged (5 of ~50 per batch); per-chapter cost target 2 min →
  **≤ 1 min**, full-book verify 115 h → **≤ 50 h** (still sampled by default).
* New: verification also checks the **cache** — a 10-segment spot re-render from cache
  must be byte-identical to the stored WAVs (guards against silent cache corruption).

---

## 6. Deliverables

* Keep MP3 128 kbps mono as the cheap per-chapter deliverable.
* Add **M4B** (AAC 64 kbps mono) with real chapter markers + embedded cover: ffmpeg
  concat → `-f mp4` with an ffmetadata chapter file generated from the book manifest.
  One file per book, proper resume across apps, cover art. (README's size table
  already budgets it: 9.8 GB whole book.)
* `verify` gains a **manifest report**: per-chapter audio seconds vs predicted
  duration-predictor totals (drift > 5 % flags a bad segment batch cheaply, before
  Whisper).

---

## 7. Generic-book profiles (import anything, not just this edition)

Cleaning becomes **profile-driven**; the fixture's profile ships as data:

```
profiles/generic.toml       homoglyph decode on, no watermark lists, standard brackets
profiles/shadowslave.toml   + the 3 607-marker site/watermark tables, decorative tag
profiles/<book>.toml        per-import overrides (user-editable)
```

* The homoglyph decoder is already generic (per-instance consistency against the
  book's own vocabulary + word list); it stays on everywhere — it costs only styled
  words.
* Watermark/spam removal keys off the profile's site list; unknown books default to
  `generic` and **report** (never silently delete) suspicious URL-fragment paragraphs.
* The import report (chapters, words, markers removed, tags applied) is shown before
  any render — the same gate the app uses (APP_PLAN.md S2).

---

## 8. Desktop ↔ phone interop (why the segment contract pays twice)

With segments + hashes shared, one new command closes the loop with APP_PLAN.md:

```
python -m tts_pipeline pack --book shadowslave --out bundle/
  → book.json + chNNNN.segments.json + cache/audio/<hash>.wav
```

* Copy `bundle/` to the phone → the app's library imports it and **plays instantly
  with zero on-device synthesis** — the 340-hour book is rendered once on the desktop
  (≤ 80 h) and never synthesised on the phone at all.
* The phone's own synthesis path (APP_PLAN.md §8) consumes the identical segmenter and
  hash, so a chapter partially rendered on both sides merges without conflicts.
* The phone's phase-2 fixture tests ship the same `segments.json` golden files as the
  desktop unit tests (one contract, two test suites).

---

## 9. Migration from v1

* v1 artifacts stay valid: `work/prepared/chNNNN.txt` is regenerated into
  `.segments.json` on first v2 run (deterministic, fixture-tested byte-compare).
* The ledger gains no new event kinds; `render_done` keeps its meaning (chapter audio
  on disk). The cache index is a separate file — a lost cache is a slow next run, never
  a correctness event.
* `--engine fp32` reproduces today's behaviour byte-for-byte at the chapter level
  (modulo flow-matching stochasticity, which v1 already has).

---

## 10. Roadmap mapping

| PIPELINE_V2 item | lands in |
|---|---|
| segment contract + shared segmenter | first (blocks everything) — desktop `prep` + phone phase 2 |
| content-addressed cache + reroll | desktop, before the next 51-chapter batch |
| ORT tuning → int8 → batch → overlap (§4, gated) | desktop, one gate at a time |
| int8 whisper verify + cache spot-check | desktop |
| M4B deliverable + manifest drift report | desktop |
| profiles (generic/shadowslave) | desktop import + phone phase 2 |
| `pack` command + phone import | desktop + phone phase 6 (interop) |

---

## 11. Change log

| | date | change |
|---|---|---|
| | 2026-10-22 | Initial v2 design: segment contract + shared hash, content-addressed cache, gated speed track (ORT tuning, int8, batching, overlap), int8 Whisper verify, M4B deliverable, cleaning profiles, desktop→phone pack interop; v1 idempotence/ledger/atomicity preserved |
