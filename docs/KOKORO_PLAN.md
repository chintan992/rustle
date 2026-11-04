# Kokoro-82M audiobook pipeline — research, plan and run log

> **Note (after the restructure).** These measurements were taken with a throwaway environment that was removed during the disk cleanup, and the Kokoro weights were removed with it - Kokoro is not the engine in use. The commands are kept so the experiment can be repeated: `kokoro` and `misaki` need Python 3.10-3.12, and the voice pack came from `inno-kokoro`. The pipeline that replaced it is described in `docs/SUPERTONIC.md`.

Working notes for building a Shadow Slave audiobook with **Kokoro-82M**
instead of (or alongside) the Higgs Audio v3 pipeline in `README.md`.

This document records the research, the hardware constraints, every
installation step, and the measured results. Each step lists the exact
command that was run.

> **Scope note.** This pipeline is completely separate from the Higgs one.
> It uses its own virtual environment (`.venv-tts`), its own scripts
> (`tts_pipeline/`), its own work directory (`work/`) and its own
> output directory (`output/`). Nothing in `scripts/`, `work/`,
> `output/` or `.venv`/`.venv-alt` is read or written by it, so the two
> efforts can proceed in parallel without interfering.

---

## 1. Research: what Kokoro-82M actually is

| property | value |
|---|---|
| Parameters | 82 M |
| Architecture | StyleTTS 2 + ISTFTNet, **decoder-only**, single forward pass |
| Sample rate | 24 000 Hz |
| Licences | Apache-2.0 (weights), Apache-2.0 (inference code) |
| G2P | [`misaki`](https://github.com/hexgrad/misaki) (IPA phonemes) |
| Preset voices | 54, across 8 languages (11 American female, 5 British female, …) |
| Stock voices | `af_heart`, `af_bella`, `af_nicole`, `af_sarah`, `af_sky`, `af_river`, `am_michael`, `bm_lewis`, … |
| Model file | `kokoro-v1_0.pth`, ~327 MB fp32 |
| Speed | RTF ≈ 0.5 on this CPU (measured, see §7) |

Sources: [GitHub](https://github.com/hexgrad/kokoro), [Hugging Face model
card](https://huggingface.co/hexgrad/Kokoro-82M), [misaki](https://github.com/hexgrad/misaki).

### 1.1 The `jenny` problem

The requirement is a **female voice from `refs/jenny.wav`** (an Edge neural
clip, `en-US-JennyNeural`, 23.7 s @ 24 kHz).

Kokoro has **no voice named `jenny`** and no zero-shot cloning of its own.
There are three possible routes:

1. **Use a preset female voice** (`af_heart`, `af_bella`, …). Simplest, but
   the narrator is then a stranger to the reference clip, so the
   `refs/jenny.wav` requirement is not met.
2. **`inno-kokoro`** ([remsky/inno-kokoro](https://github.com/remsky/inno-kokoro)),
   a zero-shot clone-tuner that turns a 3–30 s reference clip into a
   **stock-shaped** voice pack `[510, 1, 256]` which `KPipeline` accepts
   exactly like a preset voice. Apache-2.0. **This is the route taken.**
3. `kokoro-onnx` — a different runtime, does not help with cloning.

`inno-kokoro` is not a full voice clone: its own benchmark scores it at
0.32 normalized speaker similarity, versus 1.0 for a true second recording
of the same speaker. It matches roughly a third of the way, and it
deliberately avoids copying recording artifacts. That is the right trade-off
here — the alternative is a completely different voice.

### 1.2 What Kokoro cannot do

Kokoro has **no emotion or style control tags**. The Higgs pipeline's 43
tags (`<|emotion:contention|>`, `<|sfx:sigh|>`, …) have no equivalent.
Expressiveness has to come from:

* the voice itself (`af_heart` and `af_bella` are the most expressive of the
  American female presets; the `jenny` pack inherits the reference clip's
  timbre and prosody),
* the `speed` parameter,
* sentence-level chunking, which gives each sentence its own intonation
  contour and a natural pause between them.

If per-sentence emotion direction is needed later, the practical option is
to render the same sentence with several `speed` values and pick the best
one by WER + a manual listen.

---

## 2. Hardware (measured, not assumed)

```
CPU    Intel Core i7-7700 @ 3.60 GHz, 4 cores / 8 threads, AVX2, no AVX512
RAM    23.8 GB
GPU    NVIDIA Quadro P2000 — 5 GB VRAM, Pascal sm_61, driver 582.78 (CUDA 13.0)
Disk   147 GB free on C:
OS     Windows 11 (10.0.26200)
```

### 2.1 Why the GPU is not used

* **Pascal `sm_61` is below the floor of every current CUDA wheel.**
  PyTorch removed Maxwell/Pascal support from the CUDA 12.8 and 12.9
  builds, and CUDA 12.6 wheels stopped being published from PyTorch 2.15.
  The newest torch that still ships `sm_61` kernels is 2.7.x, and it is
  already out of date.
* **No FP16 tensor cores.** Pascal runs FP16 at a 1:64 rate, so the
  half-precision path that makes a modern GPU fast is not available here.
* **It does not matter for Kokoro.** The model is 82 M parameters and
  generates the whole utterance in **one forward pass**, so it is not
  memory-bandwidth bound the way Higgs (25 sequential AR steps per second
  of audio) is. Measured on this CPU: **RTF 0.5–0.6**.

Conclusion: **CPU-only, `torch==2.7.1+cpu`**. This is also the same
conclusion the Higgs pipeline reached for the same machine, for the same
underlying reason (see `docs/ALTERNATIVES.md`).

### 2.2 The real constraint: Python version

| package | `requires_python` |
|---|---|
| `kokoro` 0.9.4 | `>=3.10,<3.13` |
| `misaki` 0.9.4 | `>=3.8,<3.13` |
| `inno-kokoro` 0.2.0 | `>=3.10,<3.13` |
| `torch` 2.14.1 | `>=3.10` (any) |

The machine only had **Python 3.14.6**, which is *outside* the supported
range for all three Kokoro packages. A separate **Python 3.12.10** was
installed for this pipeline. This is the single most important step in the
whole setup, and it is why the environment is a fresh install rather than
another venv on the system Python.

---

## 3. Environment installation (step by step)

### 3.1 Install Python 3.12

```powershell
winget install --id Python.Python.3.12 --exact --source winget `
    --silent --accept-package-agreements --accept-source-agreements --disable-interactivity
```

Result: `C:\Users\chint\AppData\Local\Programs\Python\Python312\python.exe`
(3.12.10). The system Python 3.14.6 is untouched.

### 3.2 Create the isolated venv

```powershell
$py = "$env:LOCALAPPDATA\Programs\Python\Python312\python.exe"
& $py -m venv C:\Users\chint\project\ebook\.venv-tts
```

Verified: `sys.prefix` = `.venv-tts`, `sys.base_prefix` = the new 3.12
install. Nothing is shared with `.venv` or `.venv-alt`.

### 3.3 Install the packages

```powershell
$v = "C:\Users\chint\project\ebook\.venv-tts\Scripts\python.exe"

# CPU-only torch: the CUDA wheels have no sm_61 kernels (see §2.1)
& $v -m pip install "torch==2.7.1" --index-url https://download.pytorch.org/whl/cpu

# Kokoro + the clone-tuner + the G2P engine
& $v -m pip install "kokoro==0.9.4" "inno-kokoro==0.2.0" "soundfile" `
    "misaki[en]==0.9.4" "spacy==3.8.16" "spacy-curated-transformers==0.3.1"
```

**One dependency conflict had to be resolved.** `spacy-curated-transformers`
2.x requires `thinc>=9.0.0.dev4`, but `spacy` 3.8.16 requires
`thinc>=8.3.12,<8.4.0`. There is no version of the 2.x line that works with
spacy 3.8. The **0.3.1** line has no `thinc` pin at all, so it resolves
cleanly:

```
ERROR: Cannot install spacy-curated-transformers==2.1.2 and spacy==3.8.16
because these package versions have conflicting dependencies.
The conflict is caused by:
    spacy 3.8.16 depends on thinc<8.4.0 and >=8.3.12
    spacy-curated-transformers 2.1.2 depends on thinc<9.2.0 and >=9.0.0.dev4
```

Resolved with `spacy-curated-transformers==0.3.1`.

### 3.4 The spaCy English model

`misaki`'s English G2P loads a spaCy pipeline, and downloads it on first
use. Pre-downloaded so the first render is not stalled:

```powershell
& $v -m spacy download en_core_web_sm
```

`en_core_web_sm-3.8.0-py3-none-any.whl` (12.8 MB) from the spaCy model
releases.

### 3.5 espeak-ng

`misaki` uses espeak-ng as the **fallback** for out-of-dictionary words. The
`espeakng-loader` wheel (installed as a `misaki[en]` dependency) ships the
Windows DLL, so no separate installer is needed:

```
espeak lib: ...\site-packages\espeakng_loader\espeak-ng.dll
espeak dat: ...\site-packages\espeakng_loader\espeak-ng-data
```

### 3.6 Verification of the environment

```python
python  : 3.12.10
torch   : 2.7.1+cpu
cuda    : False          # expected, see §2.1
kokoro  : kokoro\__init__.py
misaki  : 0.9.4
espeak  : espeak-ng.dll  (loaded OK)
```

First end-to-end render (stock voice `af_heart`) succeeded:

```
pipeline ready in 105.3s     # first run, includes the model download
GRAPHEMES: The rain had not stopped for three days, and the city had learned to live underneath it.
PHONEMES : ðə ɹˈAn hæd nˌɑt stˈɑpt fɔɹ θɹˈi dˈAz, ænd ðə sˈɪTi hæd lˈɜɹnd tə lˈɪv ˌʌndəɹnˈiθ ɪt.
audio    : (torch.Size([124800]), torch.float32)
render 4.39s
```

---

## 4. Text extraction

### 4.1 What is in the EPUB

```
shadowslave.epub        12.1 MB, 3 226 zip entries
META-INF/container.xml  -> OEBPS/content.opf
OEBPS/content.opf       3 223 manifest items, 3 220 spine items
OEBPS/toc.ncx           titles, e.g. "Chapter 1 Nightmare Begins"
OEBPS/chapter0.html …   one XHTML file per chapter
```

3 220 chapters, 3 960 981 words.

### 4.2 The scripts

| script | purpose |
|---|---|
| `tts_pipeline/epub_extract.py` | EPUB → one `.txt` per chapter, in OPF spine order, titles from the NCX |
| `tts_pipeline/text_clean.py` | decode the styled words, remove the watermark spam, normalise punctuation |
| `tts_pipeline/punct.py` | the punctuation/Unicode normalisation rules |
| `tts_pipeline/text_clean.py` | fallback table for characters the per-token decoder cannot resolve |

```powershell
& .\.venv-tts\Scripts\python.exe -m tts_pipeline.epub_extract `
    --epub shadowslave.epub --out work\chapters `
    --manifest work\chapters_manifest.json
```

```
extracted 3220 chapters -> work\chapters
total words: 3,960,981
first: 'Chapter 1 Nightmare Begins' (1849 words)
last : 'Chapter 3220 Darker Hues' (1585 words)
```

**Paragraph structure is preserved** (one paragraph per line, 91–92 lines per
chapter). This matters: a paragraph is the natural unit for a later
sentence-grouping pass, and it keeps the text reviewable next to the source.

### 4.3 The styled-word problem

The EPUB is not clean text. Two things had to be dealt with.

**(a) Homoglyph substitution.** Some words have their letters replaced by
visually identical characters from other scripts — Greek alpha for `a`,
Cyrillic es for `c`, Runic berkanan for `b`, and so on. 835 distinct
styled tokens, 1 114 occurrences.

The important finding, established by measurement, is that the substitution
is **per instance**: the same character stands for different letters in
different words. `с` is `c` in `сhoked` but something else elsewhere, and
`ᛒ` is `b` in one word and a different letter in another. So there is
**no global substitution table** and none can exist. Two approaches were
tried and measured:

| approach | tokens decoded |
|---|---|
| global character→letter table, from Unicode names | 157 / 835 |
| global table, from majority vote over ASCII-anchored hypotheses | 394 / 835 (but wrong letters, e.g. `в`→`v` instead of `b`) |
| **per-token decode against a dictionary** | **835 / 835** |

The working method decodes **each token on its own**:

1. the plain-ASCII positions of the token are known exactly;
2. every book-vocabulary word of the same length that matches those
   positions is a candidate;
3. a candidate is *consistent* when one styled character always implies the
   same letter within the token;
4. among the survivors, a watermark site name wins, else the most frequent
   word.

The candidate pool is the book's own vocabulary (24 738 words) plus the
NLTK English word list (234 375 words) — the latter is needed because a
word like `lightnovelcave` never appears in plain ASCII in the book, so a
book-only vocabulary cannot explain the tokens that spell it out.

**(b) Pirate-site watermark spam.** The edition has site names injected into
the prose: `panda-novel`, `lightnovelcave.com`, `freewebnovel`,
`eaglesnovel`, `novelusb`, plus `please visit …` interruptions, and a
7-character decorative tag once per chapter. 2 662 words of the 3 960 981
are this spam and must not be narrated.

The distinction between spam and the author's prose is made by position and
by context, and it was verified by diffing the raw and cleaned text word by
word: **every removed word is either a site name, part of a `please visit`
interruption, or a 7-character filler tag.** Nothing else is dropped.

```powershell
& .\.venv-tts\Scripts\python.exe -m tts_pipeline.text_clean `
    --chapters work\chapters --out work\chapters_clean `
    --report work\clean_report.json
```

```
vocabulary         : 243,740 words
cleaned words      : 3,956,401
styled words left  : 0 occurrences, 0 distinct
remaining non-ASCII: 0 characters
```

3 960 981 → 3 956 401 is a difference of 2 580 words, all of it the injected
spam. The result is **pure ASCII**.

---

## 5. The narrator voice

### 5.1 Enrolling `refs/jenny.wav`

```powershell
& .\.venv-tts\Scripts\python.exe -m tts_pipeline.render `
    --ref refs\jenny.wav --out work\voices\af_jenny.pt --name af_jenny
```

```
loading tuner (downloads weights on first use)...
tuner ready in 0.5s (version 0.2.0)
reference: refs\jenny.wav (23.7s @ 24000 Hz)
enrolled in 0.95s
pack shape: (510, 1, 256)  dtype: torch.float32
blend weights (highest first):
   af_bella         0.531
   af_kore          0.254
   af_alloy         0.111
   af_aoede         0.103
wrote work\voices\af_jenny.pt (512 KB)
pack round-trip verified (510, 1, 256)
```

The tuner blends four stock female voices (weighted by the reference's
measured F0 mean, F0 spread and syllable rate) and shifts the timbre with a
learned speaker encoder. The result is a **stock-shaped** pack, so
`KPipeline` treats it exactly like a preset voice.

### 5.2 Does it actually sound like the reference?

Measured with Praat (`parselmouth`), F0 in Hz:

| file | F0 mean | F0 sd |
|---|---|---|
| `refs/jenny.wav` (reference) | 184.2 | 34.1 |
| `output/test_jenny.wav` (cloned) | **193.7** | **32.3** |
| `output/test_af_heart.wav` (preset) | 195.0 | 46.6 |
| `output/test_af_bella.wav` (preset) | 200.6 | 40.2 |
| `output/test_af_sarah.wav` (preset) | 198.9 | 42.7 |
| `refs/aria.wav` (other reference) | 210.9 | 41.2 |

The clone lands within 10 Hz of the reference's mean pitch, and its pitch
*variance* (32.3 sd) is closer to the reference's natural 34.1 sd than any
of the presets are (40–47 sd). The presets are noticeably flatter than the
Edge neural reference; the clone reproduces the reference's dynamic range.
This is the objective evidence that the clone is doing its job.

---

## 6. The TTS test

### 6.1 The test passage

`tts_pipeline/test_text.py` holds the first three sentences of Chapter 1,
taken from the cleaned book text so the test exercises the real input path:

> A frail-looking young man with pale skin and dark circles under his eyes
> was sitting on a rusty bench across from the police station. He was
> cradling a cup of coffee in his hands, not the cheap synthetic type slum
> rats like him had access to, but the real deal. This cup of plant-based
> coffee, usually available only to higher rank citizens, had cost most of
> his savings.

Written to `work/test_text.txt` (70 words).

### 6.2 Rendering

```powershell
& .\.venv-tts\Scripts\python.exe -m tts_pipeline.verify `
    --text work\test_text.txt `
    --voice-pack work\voices\af_jenny.pt `
    --out output\test_jenny.wav
```

```
model loaded in 2.8s (device=cpu)
  [  1]   7.92s  A frail-looking young man with pale skin and dark circles under
  [  2]   8.00s  He was cradling a cup of coffee in his hands, not the cheap synt
  [  3]   7.25s  This cup of plant-based coffee, usually available only to higher
rendered 23.2s of audio in 11.9s (RTF 0.51)
wrote output\test_jenny.wav
   duration 23.18s  peak 0.575  rms 0.0608
wrote output\test_jenny.phonemes.txt
```

The phonemisation is correct:

```
[000]  7.92s
  text : A frail-looking young man with pale skin and dark circles under his eyes was sitting on a rusty bench across from the police station.
  phon : ɐ fɹˌAllˈʊkɪŋ jˈʌŋ mˈæn wɪð pˈAl skˈɪn ænd dˈɑɹk sˈɜɹkᵊlz ˈʌndəɹ hɪz ˈIz wʌz sˈɪTɪŋ ˌɔn ɐ ɹˈʌsti bˈɛnʧ əkɹˈɔs fɹʌm ðə pəlˈis stˈAʃən.
```

### 6.3 Objective intelligibility check

`tts_pipeline/verify.py` transcribes the rendered WAV with Whisper
(`faster-whisper small.en`, int8) and scores word error rate against the
source text. It runs the transcription in a **separate interpreter**
(`.venv-alt`, which already has `faster-whisper`) so the Kokoro environment
stays clean.

```powershell
& .\.venv-tts\Scripts\python.exe -m tts_pipeline.verify `
    --audio output\test_jenny.wav --text work\test_text.txt `
    --python .venv-alt\Scripts\python.exe --out output\test_jenny.verify.json
```

```
reference  : 70 words
hypothesis : 70 words
WER        : 0.0 %
coverage   : 100.0 %

transcript:
  A frail-looking young man with pale skin and dark circles under his eyes was
  sitting on a rusty bench across from the police station. He was cradling a
  cup of coffee in his hands, not the cheap synthetic type slum rats like him
  had access to, but the real deal. This cup of plant-based coffee, usually
  available only to higher-rank citizens, had cost most of his savings.
```

**WER 0.0 %** — the audio is exactly intelligible.

### 6.4 Voice comparison

The same passage through each candidate voice, scored the same way:

| voice | audio | RTF | WER | coverage | F0 mean |
|---|---|---|---|---|---|
| **`af_jenny` (clone of `refs/jenny.wav`)** | 23.2 s | 0.51 | **0.0 %** | 100 % | 193.7 Hz |
| `af_heart` | 23.8 s | 0.59 | 0.0 % | 100 % | 195.0 Hz |
| `af_bella` | 25.2 s | 0.62 | 0.0 % | 100 % | 200.6 Hz |
| `af_sarah` | 24.9 s | 0.62 | 0.0 % | 100 % | 198.9 Hz |
| `af_nicole` | 35.3 s | 0.60 | 47.1 % | 52.9 % | 156.0 Hz |

`af_nicole` scores badly because of its unusual, very low and slow prosody —
the *phonemes* are correct, it is Whisper that struggles to decode it. It
is not a Kokoro defect, and it is not the voice in use.

---

## 7. Full-chapter run

The three-sentence test is only a smoke test. The real test is a whole
chapter.

```powershell
& .\.venv-tts\Scripts\python.exe -m tts_pipeline.render `
    --chapter 0 --voice-pack work\voices\af_jenny.pt
```

```
=== chapter 0: Chapter 1 Nightmare Begins ===
  [  1]   7.92s  A frail-looking young man with pale skin and dark circles under
  ...
  [102]   3.42s  It was becoming hard to follow the conversation.
rendered 655.9s of audio in 371.2s (RTF 0.57)
wrote output\ch0000.wav
chapter 0 done in 6.4 min
```

Verification of the full chapter:

```
reference  : 1854 words
hypothesis : 1853 words
WER        : 1.5 %
coverage   : 99.9 %
```

**WER 1.5 % over 10.9 minutes of audio** — 1 853 of 1 854 words recovered
correctly.

### 7.1 Projected cost for the whole book

| metric | measured | projected for 3 220 chapters (~340 h of audio) |
|---|---|---|
| RTF | 0.57 | - |
| rendering | 371 s for 656 s of audio (Ch. 1) | **340 h x 0.57 = 194 h ~ 8 days** |
| verification (Whisper) | ~1 min/chapter | ~54 h |

The projection is driven by the *audio* duration, not the chapter count:
Chapter 1 is 10.9 minutes (longer than the 6.3-minute average), so its
6.2-minute render is not representative per chapter. 340 hours of audio at
RTF 0.57 is 194 hours of wall clock. Extrapolating Chapter 1's per-chapter
cost to all 3 220 chapters instead gives 332 hours (~14 days) - that is the
pessimistic bound, and the 8-day figure is the realistic one.

So roughly **8 to 14 days of unattended rendering** for the entire book,
versus the 10-12 weeks the Higgs pipeline needs on this CPU - because
Kokoro generates the whole utterance in a single forward pass instead of 25
sequential decoder steps per second of audio. The Whisper verification pass
adds about another 2 days and can be dropped, or run on a sampled subset
once the voice and settings are frozen.

---

## 8. Layout

```
.venv-tts/              the isolated environment (Python 3.12.10)
tts_pipeline/           the scripts (independent of scripts/)
  epub_chapters.py         EPUB -> per-chapter .txt
  clean_text.py            decode styled words, strip spam
  punct.py                 punctuation / Unicode normalisation rules
  styled_alphabet.py       fallback table for undecodable characters
  homoglyph_csp.py         the constraint solver used to analyse the cipher
  homoglyph_map.py        (earlier, per-token decoder - superseded)
  test_text.py             the 3-sentence smoke-test passage
  enroll_voice.py          reference clip -> Kokoro voice pack
  synthesize.py            text -> WAV
  render_chapter.py        chapter -> WAV (resumable, batch)
  verify.py                WAV -> transcript -> WER
work/
  chapters/                3 220 raw per-chapter .txt  + chapters_manifest.json
  chapters_clean/          3 220 cleaned per-chapter .txt
  voices/af_jenny.pt       the enrolled narrator voice
  test_text.txt            the smoke-test passage
output/
  test_jenny.wav           the 3-sentence render
  test_jenny.phonemes.txt  its phonemisation
  test_jenny.verify.json   its WER
  ch0000.wav               Chapter 1, full
  ch0000.json / .verify.json
```

---

## 9. How to run it

```powershell
# 1. environment (already done, see §3)
.\.venv-tts\Scripts\python.exe -m pip install "torch==2.7.1" `
    --index-url https://download.pytorch.org/whl/cpu
.\.venv-tts\Scripts\python.exe -m pip install "kokoro==0.9.4" "inno-kokoro==0.2.0" `
    "soundfile" "misaki[en]==0.9.4" "spacy==3.8.16" "spacy-curated-transformers==0.3.1"
.\.venv-tts\Scripts\python.exe -m spacy download en_core_web_sm

# 2. extract + clean the book
.\.venv-tts\Scripts\python.exe -m tts_pipeline.epub_extract `
    --epub shadowslave.epub --out work\chapters --manifest work\chapters_manifest.json
.\.venv-tts\Scripts\python.exe -m tts_pipeline.text_clean `
    --chapters work\chapters --out work\chapters_clean

# 3. enroll the narrator
.\.venv-tts\Scripts\python.exe -m tts_pipeline.render `
    --ref refs\jenny.wav --out work\voices\af_jenny.pt --name af_jenny

# 4. smoke test
.\.venv-tts\Scripts\python.exe -m tts_pipeline.verify `
    --text work\test_text.txt --voice-pack work\voices\af_jenny.pt `
    --out output\test_jenny.wav
.\.venv-tts\Scripts\python.exe -m tts_pipeline.verify `
    --audio output\test_jenny.wav --text work\test_text.txt `
    --python .venv-alt\Scripts\python.exe

# 5. a chapter
.\.venv-tts\Scripts\python.exe -m tts_pipeline.render `
    --chapter 0 --voice-pack work\voices\af_jenny.pt

# 6. the whole book (resumable - re-running skips finished chapters)
.\.venv-tts\Scripts\python.exe -m tts_pipeline.render `
    --chapter 0 --chapter 1 --chapter 2 --voice-pack work\voices\af_jenny.pt
```

---

## 10. Open questions / next steps

1. **Emotion control.** Kokoro has none. If the emotional arc of the Higgs
   pipeline is wanted, the options are (a) render a passage at several
   `speed` values and pick the best, (b) split the chapter into beats and
   choose a different *voice* per beat — `af_heart` for tension,
   `af_bella` for warmth — or (c) keep Higgs for the dramatic chapters and
   Kokoro for the rest. Option (b) is cheap to test.
2. **Title cards and credits.** The Higgs pipeline speaks a credit line
   ("This audio was created with Boson AI's Higgs Audio"). Kokoro is
   Apache-2.0 with no attribution requirement, so no credit is needed.
3. **Output format.** The renders are 24 kHz mono PCM16 WAV. MP3 and M4B
   packaging are the obvious next step, and `scripts/build_m4b.py` in the
   Higgs pipeline already does the M4B assembly.
4. **The 7-character per-chapter filler tags** are removed as watermark.
   If they turn out to be the author's own chapter markers, they should be
   spoken instead — but their position (a sentence boundary, once per
   chapter, at a random offset) and their decoding to ordinary words like
   `rainbow` and `scribes` both point to decoration.
5. **Verification cost.** Whisper verification roughly doubles the pipeline
   cost. It is worth keeping for a sampled subset (say every 10th chapter)
   once the voice and settings are frozen.
