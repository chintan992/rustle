# Supertonic 3 — evaluation for the Shadow Slave audiobook

> **Note (after the restructure).** The tag measurements in this document were taken with the environment that is now `.venv-tts`; the commands are unchanged. The chapter range 100-150 is prepared in `work/prepared/` and rendered into `output/wav/` and `output/mp3/`.

**Result: Supertonic 3 is faster than Kokoro-82M *and* has working expression
control, which Kokoro does not have at all. It is the new recommended engine.**

Everything below was measured on this machine. The research that led to
choosing it is in `docs/TTS_OPTIONS.md`; the Kokoro and KittenTTS baselines
are in `docs/KOKORO_PLAN.md` and `docs/KITTENTTS.md`.

---

## 1. What it is

| property | value |
|---|---|
| Model | Supertonic 3 (`supertone-oss-archive/supertonic-3`) |
| Parameters | ~99 M |
| Architecture | flow-matching TTS, pure **ONNX Runtime** — no PyTorch anywhere |
| Sample rate | **44 100 Hz** (not 24 kHz like the others here) |
| Languages | 31 |
| Voice styles | 10 presets: F1–F5 (female), M1–M5 (male) |
| Cloning | **not in the open-weight release** (the hosted Voice Builder did it) |
| Expression | **21 tags confirmed working** |
| Licence | MIT (code) + **OpenRAIL-M** (weights) — commercial OK, with use restrictions |
| Download | 398 MB of ONNX + 12 small JSONs |
| Model load | **1.8 s** |

---

## 2. Installation

```powershell
$py = "$env:LOCALAPPDATA\Programs\Python\Python312\python.exe"
& $py -m venv .venv-tts
.\.venv-tts\Scripts\python.exe -m pip install "supertonic==1.3.1"
```

The environment is **onnxruntime + soundfile + numpy + huggingface-hub**. There
is no `torch` in it, which is why the model loads in 1.8 s instead of 30 s.

### Downloading the weights

The HF **Xet transfer path stalls at 0 MB on this network** (the
`cdn-lfs.huggingface.co` host does not resolve here), so Xet must be
disabled:

```powershell
$env:HF_HUB_DISABLE_XET = "1"

hf download supertone-oss-archive/supertonic-3 `
  --revision aafc6e32416a594460b32413efc49d7fe4ce6d46 `
  --include "onnx/*" "config.json" "voice_styles/*" `
  --local-dir "work\supertonic-3"
```

All 19 files were verified byte-exact against the repo manifest:

```
onnx/vector_estimator.onnx     256 534 781 bytes
onnx/vocoder.onnx              101 424 195
onnx/text_encoder.onnx          36 416 150
onnx/duration_predictor.onnx     3 700 147
onnx/tts.json, onnx/unicode_indexer.json, config.json
voice_styles/F1..F5.json, M1..M5.json
```

---

## 3. Measured results

### 3.1 Speed and intelligibility

| render | audio | wall | RTF | WER | coverage |
|---|---|---|---|---|---|
| 3-sentence test, style F2 | 22.6 s | 6.4 s | **0.277** | **0.0 %** | 100 % |
| **Chapter 1, style F2** | 738.8 s (12.3 min) | 221.9 s (3.7 min) | **0.300** | **1.2 %** | **99.9 %** |

### 3.2 All ten voice styles

Every preset style renders the test passage intelligibly:

| style | F1 | F2 | F3 | F4 | F5 | M1 | M2 | M3 | M4 | M5 |
|---|---|---|---|---|---|---|---|---|---|---|
| WER | 0.0 % | 0.0 % | 0.0 % | 0.0 % | 0.0 % | 0.0 % | 0.0 % | 1.4 % | 0.0 % | 0.0 % |
| RTF | 0.281 | 0.285 | 0.273 | 0.306 | 0.288 | 0.265 | 0.286 | 0.290 | 0.279 | 0.279 |

### 3.3 Verification is sampled, not exhaustive

Transcribing a chapter of audio takes about two minutes on this machine, so
verifying all 3 220 chapters would take roughly 115 hours. The verification
answers one question - *did the engine render the text correctly?* - and that
is uniform across a batch, so the pipeline verifies **5 chapters per batch of
about 50**, spread evenly and always including the first and the last:

`
verifying a sample of 5 of 51 chapters with Whisper (small.en)
  sampled chapters: [100, 112, 125, 138, 150]
  verified 5 chapters, mean WER 1.6 %  (best 1.3 %, worst 1.8 %)
`

10 minutes instead of 110. --sample 0 verifies the whole range. The
audio was also checked by ear, which is the ground truth the WER is measured
against.

### 3.4 Head-to-head against Kokoro-82M

| | **Supertonic 3 (F2)** | Kokoro-82M (af_jenny clone) |
|---|---|---|
| RTF on this CPU | **0.30** | 0.57 |
| Speed | **3.3× realtime** | 1.75× realtime |
| Model load | **1.8 s** | 2.8 s |
| Test passage WER | **0.0 %** | 0.0 % |
| Chapter 1 WER | **1.2 %** | 1.5 % |
| Chapter 1 coverage | **99.9 %** | 99.9 % |
| Sample rate | **44.1 kHz** | 24 kHz |
| **Expression control** | **21 working tags** | **none** ("cannot laugh. cannot sound extremely angry") |
| Voice cloning | no (10 presets) | yes, approximate (F0 +9.5 Hz off) |
| Licence | MIT + OpenRAIL-M | Apache-2.0 |

Supertonic wins on every axis that matters here except cloning, and cloning
was explicitly dropped from the requirements.

**Projected cost for the whole book** (340 h of audio):

| engine | RTF | wall clock |
|---|---|---|
| **Supertonic 3** | 0.30 | **102 h ≈ 4.3 days** |
| Kokoro-82M | 0.57 | 194 h ≈ 8.1 days |

---

## 4. The expression tags — measured, not assumed

The model card only says *"supports simple tags such as `<laugh>`, `<breath>`
and `<sigh>`"*, and the tags are **not** special tokens in
`onnx/unicode_indexer.json` — they are ordinary characters the trained text
encoder learned to interpret. So the supported set was measured
(`tts_pipeline/tools/supertonic_tags.py`).

### 4.1 Method

For each candidate, the same sentence was rendered with and without the tag.
A tag is a **real** expression control only if both hold:

* **consumed** — the tag's own word is not spoken aloud (it is markup);
* **changed** — the rendered audio differs from the baseline.

"Changed" needed care. The model is **stochastic** (flow-matching samples
noise), so the baseline was rendered **6 times** to establish the noise
floor: identical text gave durations of exactly 3.00 s every time, but
pairwise envelope NCC of 0.32–0.92 (mean 0.58). Nothing can be called a
"change" unless it beats that.

### 4.2 The supported set — 21 tags

Every one of these is consumed as markup and produces a **distinct acoustic
event**, verified by comparing the lead region of each tag against every
other tag's:

| tag | lead silence | lead peak | distinct from other tags? |
|---|---|---|---|
| `<joyful>` | 0.44 s | 0.0298 | yes (median NCC 0.12) |
| `<breath>` | 0.67 s | 0.0201 | yes (0.39) |
| `<burp>` | 0.62 s | 0.0183 | yes (0.41) |
| `<cry>` | 0.66 s | 0.0139 | yes (0.42) |
| `<pant>` | 0.86 s | 0.0136 | yes (0.33) |
| `<heavy_breath>` | 0.48 s | 0.0135 | yes (−0.03) |
| `<calm>` | 0.78 s | 0.0134 | yes (0.43) |
| `<stern>` | 0.65 s | 0.0122 | yes (0.41) |
| `<inhale>` | 0.61 s | 0.0119 | yes (0.35) |
| `<sigh>` | 0.73 s | 0.0111 | yes (0.43) |
| `<sad>` | 0.75 s | 0.0102 | yes (0.38) |
| `<happy>` | 0.62 s | 0.0101 | yes (0.33) |
| `<hiccup>` | 0.66 s | 0.0091 | yes (0.43) |
| `<laugh>` | 0.55 s | 0.0054 | yes (0.25) |
| `<clears_throat>` | 0.66 s | 0.0043 | yes (0.16) |
| `<laughter>` | 0.67 s | 0.0032 | yes (0.35) |
| `<cough>` | 0.60 s | 0.0015 | yes (0.26) |
| `<yawn>` | 0.62 s | 0.0015 | yes (0.35) |
| `<groan>` | 0.62 s | 0.0013 | yes (0.28) |
| `<moan>` | 0.65 s | 0.0010 | yes (0.43) |
| `<mischievous>` | 0.59 s | 0.0005 | yes (0.08) |

The "lead silence" column is the length of the pause before the speech
starts; the "lead peak" is the peak amplitude of the event in that pause.
Both vary per tag, and the events are acoustically distinct from each
other, so these are genuine vocalisations rather than one generic pause.

**Tags work mid-sentence too.** `"One two <laugh> three four …"` is rendered
with the tag consumed (not spoken) and the sentence continues.

### 4.3 The definitive tag list

The first test (is the tag's word in the transcript?) is ambiguous: Whisper
sometimes renders an audible laugh as the word "laughs", which looks exactly
like the tag being read aloud. The fix is a **control**. For each candidate
``T``, a second clip is rendered from the same sentence with a plain word
that is definitely not a tag:

```
<T>      The city had learned to live underneath the rain.
<zebra>  The city had learned to live underneath the rain.
```

`zebra` is always spoken. So:

* zebra spoken, T never spoken -> **T is markup**
* T always spoken -> **T is an ordinary word, not a tag**
* T sometimes heard -> **T is markup whose effect Whisher sometimes
  transcribes as the tag's own word** (a pause that reads as "pause")

Each variant was rendered **3 times**, so "never spoken" means 0 of 3.

**MARKUP — safe to use (20):**

`<breath>` `<burp>` `<calm>` `<clears_throat>` `<cough>` `<cry>` `<groan>`
`<heavy_breath>` `<hiccup>` `<laugh>` `<laughter>` `<moan>` `<sad>` `<sigh>`
`<yawn>` `<nod>` `<slow>` `<laughs>` `<coughs>` `<laughs_softly>`

**AMBIGUOUS — markup, but the effect is often transcribed as the word (10):**

`<pause>` (67 %) `<stop>` (67 %) `<serious>` (67 %) `<loud>` (67 %)
`<mischievous>` (33 %) `<pant>` (33 %) `<wait>` (33 %) `<fast>` (33 %)
`<breathe>` (33 %) `<sighs>` (33 %)

These are genuine tags, but the sound they produce is close enough to the
word that a listener might hear the word. They are allowed by the prep
script but flagged as risky.

**SPOKEN — never insert (11):**

`<happy>` `<inhale>` `<joyful>` `<stern>` `<silence>` `<soft>` `<heavy>`
`<emotional>` `<angry>` `<whispers>` `<chuckles>`

Plus the 24 words from the earlier sweep that are plainly read aloud:
`<afraid>` `<angry>` `<applause>` `<chuckle>` `<contemplative>` `<crying>`
`<disgusted>` `<excited>` `<exhale>` `<gasp>` `<melancholic>` `<nervous>`
`<sarcastic>` `<scream>` `<serious>` `<shout>` `<sneeze>` `<sniffle>`
`<stutter>` `<surprised>` `<tender>` `<throat>` `<whimper>` `<whisper>`

`<whisper>` is the trap worth knowing about: it *sounds* like it should
work, and the model reads the word "whisper" aloud.

**Tag position.** Tags are honoured at the start of a segment, at the end of
one, and mid-sentence. Measured on the same sentence: plain 3.00 s, trailing
3.69 s, leading 3.55 s, mid 3.41 s - all consumed, all producing a
different result.

### 4.4 Practical note

The model card calls the set "10 expression tags"; the measurement finds
**20 safe markup tags** plus 10 ambiguous ones. The count probably differs
because the authors counted non-verbal sounds only, and the remainder are
emotion-like (`<calm>`, `<sad>`, `<nod>`, `<slow>`, and so on). The lists
above are what this model actually honours.

---

## 5. A bug worth recording: faster-whisper assumes 16 kHz

The first Supertonic render came back at **WER 47.1 %** — with a transcript
that was *almost* right:

> "A **fine**-looking young man with pale skin and dark **selfies** and eyes
> always was sitting on a **worsted bunch of pearls** from the police station."

Every error was a word heard at the wrong speed. The cause: Supertonic
outputs **44.1 kHz**, and faster-whisper's feature extractor **assumes
16 kHz and does not resample a numpy input**. The audio was being heard
2.76× too fast.

The fix in `tts_pipeline/verify.py` is to resample to 16 kHz before
transcribing:

```python
if sr != 16000:
    g = gcd(16000, sr)
    data = resample_poly(data, 16000 // g, sr // g)
```

After that: **WER 0.0 %**. The same bug would have silently corrupted any
future 44.1 kHz measurement, and would have looked like a TTS defect rather
than a harness defect — which is why the control test (the model's *own*
demo sentence garbling too) was the thing that gave it away.

---

## 6. The prepped batch for chapters 100-150

Chapters 1-99 are skipped. Chapter **N** in the book is file index
`N-1`, so **chapters 100-150 = `ch0099.txt` .. `ch0149.txt`**, 51 chapters.

### 6.1 The output folder

Everything the batch needs lives in one directory:

```
work/prepared/
    ch0099.txt ... ch0149.txt    51 prepped chapters, one paragraph per line
    manifest.json                per-chapter stats, cost estimates, tag lists
    emotion_maps/                per-chapter expression-cue templates
    validation.json              the validation report
```

`work/emotion_maps/` holds the same map files in editable form; the
preparer reads from there and writes the applied result into the prepared
folder.

### 6.2 What the prep step does

| transformation | count in this range | why |
|---|---|---|
| missing space after an ellipsis (`bastard....me`) | 37 | the EPUB joins a paragraph starting with "..." onto the previous one |
| standalone `***` scene break -> `<breath>` + blank line | 18 | the asterisks would otherwise be read aloud |
| square brackets stripped, text kept | 45 | the novel uses `[...]` for the Spell's voice and for status panels; the brackets are stage directions, not words |
| expression cues from the emotion map | 0 so far | the maps are templates awaiting direction |
| whitespace normalisation | all | |

A scene break now reads:

```
Whispering into his ear, it slowly said:
You have received an Echo: Stone Saint.

<breath>

Sunny's eyes opened wide.
```

and the Spell's voice keeps its wording but loses the brackets:

```
At that moment, the familiar voice of the Spell resounded in the darkness:
You have slain an awakened monster, Stone Saint.
Your shadow grows stronger.
```

### 6.3 The expression-cue map

`work/emotion_maps/ch0100.json` (and one per chapter):

```json
{
  "chapter": 100,
  "title": "Clear Conscience",
  "rules": [
    {"match": "exact text, must be unique", "tag": "<sigh>", "mode": "before"}
  ]
}
```

`mode` is `before`, `after` or `replace`. A rule whose `match` is not
unique in the chapter is **reported, not silently applied**, and a rule using
a tag outside the measured allow-list is rejected outright. That way a typo
in a map cannot quietly corrupt a chapter.

### 6.4 Validation

`tts_pipeline/validate_prepared.py` checks the result:

```
validated 51 files, 59,217 words
expression tags used: {'breath': 18}
no issues found
```

It verifies ASCII-only output, no leftover `*` or `[` or `]`, only
allow-listed tags, balanced angle brackets, no empty paragraphs, and that the
word count matches the manifest.

A **segmentation dry run** over the whole batch found no pathological input
before anything was rendered:

```
total segments      : 3939
segments with a tag : 18
tag-only segments   : 0        (an empty render)
segment len min/med/max: 3 / 76 / 432 chars
segments > 600 chars: 0
```

### 6.5 Cost

| metric | value |
|---|---|
| chapters | 51 |
| words | 59 217 |
| estimated audio | **379.6 min** (6.3 h) |
| estimated render at RTF 0.30 | **113.9 min** (~1.9 h) |

### 6.6 Running it

```powershell
.\.venv-tts\Scripts\python.exe -m tts_pipeline.cli `
    --from-chapter 100 --to-chapter 150 `
    --voice-style F2 --out-dir output\super
```

The run is resumable: a chapter whose WAV already exists is skipped, and
`output/super/render_log.json` is written after every chapter with
per-chapter duration, wall time and RTF.

**Nothing has been rendered yet.** Only the 2-segment smoke test in §6.7 has
been run.

### 6.7 Smoke test of the prepped path

Two prepped segments (one carrying a `<breath>`) were rendered to confirm the
whole path works end to end:

```
'As the dust settled, Sunny abandoned his hiding spot on the roof of one of the b'
'<breath> Sunny's eyes opened wide.'
-> output/st_prepped_smoke.wav  11.01s @ 44100 Hz
WER 7.4 %, coverage 100 %
```

The 7.4 % is Whisper noise on a 27-word sample (it wrote "Sonny" for
"Sunny" and "jumped down" for "jump down"). The full-chapter measurement is
the reliable one: **1.2 % over 1 854 words**.

---

## 7. Trade-offs and open questions

* **No cloning.** The narrator is one of ten presets, not
  `refs/jenny.wav`. The requirement was relaxed, so this is acceptable, but
  it is the one thing lost.
* **44.1 kHz** is a bonus for audiobook quality, but it doubles file size
  against 24 kHz, and it is the thing that exposed the Whisper bug.
* **Stochastic output.** Two renders of the same text differ (NCC 0.32–0.92
  between them). That is normal for flow-matching, but it means a bad take
  cannot be re-rolled deterministically — a chunk may need re-rendering
  until it comes out right, which is what the Higgs pipeline already does.
* **The repository is archived** (September 2026) and the weights now live
  under `supertone-oss-archive`. No further development or fixes will be
  published. The `supertonic` PyPI package (1.3.1) still works.
* **44.1 kHz vs 24 kHz for the M4B/Mp3 stage** — worth deciding before
  rendering 340 hours, since it doubles the disk and encode time.
* **The `speed` parameter** (default 1.05) and `total_steps` (default 8) are
  the two tuning knobs. More steps may improve quality at a linear cost in
  time; worth a sweep.

---

## 8. Files

```
.venv-tts/                onnxruntime only, no torch
models/supertonic-3/        the downloaded model (onnx/, voice_styles/, config.json)
work/st_tags_report.json  tag measurement: durations per candidate
work/st_tags_angle.json   transcripts per candidate, for the consumed/spoken split
tts_pipeline/render.py   render text -> WAV
tts_pipeline/tools/supertonic_tags.py    measure which expression tags work
tts_pipeline/tools/batch_transcribe.py   transcribe a directory of WAVs in one model load
output/supertonic/       Chapter 1 rendered with Supertonic
output/st_tags/          the per-tag renders used for the measurement
```

## 9. Sources

* [Supertonic repository (archived)](https://github.com/supertone-oss-archive/supertonic)
* [Supertonic 3 model card](https://huggingface.co/supertone-oss-archive/supertonic-3)
* [tts-bench](https://github.com/5uck1ess/tts-bench) — the screen that
  shortlisted Supertonic 3, and its `docs/expressive-control.md`.
* `docs/TTS_OPTIONS.md` — the full candidate list and the reasoning.
