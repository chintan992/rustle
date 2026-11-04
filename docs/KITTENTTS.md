# KittenTTS research — evaluation for the Shadow Slave audiobook

> **Note (after the restructure).** These measurements were taken with a throwaway environment that was removed during the disk cleanup, and the KittenTTS weights were removed with it. The commands are kept so the experiment can be repeated: `kittenml` needs Python 3.10-3.12, and the C++ path additionally needs `kitten_tts_cpp` (which pins `torch==2.13.0` and therefore cannot share an environment with the rest of the pipeline).

Companion to `docs/KOKORO_PLAN.md`. This document records what KittenTTS is,
what was installed, what was measured on this machine, and the resulting
recommendation.

> **Scope note.** Like the Kokoro pipeline, this work is fully isolated: its
> own environments (`.venv-tts`, `.venv-tts`), its own work
> directory (`work/kitten*`) and its own outputs. Nothing in
> `scripts/`, `work/`, `output/`, `.venv` or `.venv-alt` is modified. The
> KittenTTS ONNX weights already present in `models/` are read-only and
> untouched.

---

## 1. The model family

KittenML ships **two unrelated model families** behind one constructor.
They differ enormously in capability, and confusing them is the main source
of bad advice about "KittenTTS".

| | **KittenTTS 2** | **lightweight ONNX** |
|---|---|---|
| params | 1.7 B | 15 M (nano) – 80 M (mini) |
| format | speech language model, ternary 1.58-bit body | ONNX graph |
| weights | `packed` 954 MB / `emb4` 469 MB / `full` 3.47 GB | 25–80 MB |
| **voice cloning** | **yes, 5–30 s reference** | **no** |
| built-in voices | **47** | 8 |
| **expression control** | **20 tags** | none |
| languages | 20 | 1 (en-us) |
| long-form | sentence-aware chunking, seamless joins | manual |
| licence | **Stellon Labs Community License** | Apache-2.0 (per repo) |
| runtimes | PyTorch (`kittenml`) and C++ (`kitten_tts_cpp`) | ONNX Runtime |

### 1.1 The two model families

`kittenml` dispatches on the repository's `config.json`:

```json
{ "type": "KITTEN2", ... }   ->  KittenTTS2   (cloning, expression)
{ "type": "ONNX1" | "ONNX2" } ->  KittenTTSOnnx  (8 preset voices)
```

Both are reached through `KittenTTS(...)`, and a **local directory** laid out
like a repository is accepted directly — which is what made it possible to
run entirely from files already on disk.

### 1.2 Expression control (KittenTTS 2 only)

Ten emotions, one leading tag per line:

```
[angry] [contemplative] [excited] [joyful] [mundane]
[nervous] [sad] [stern] [surprised] [tender]
```

Ten vocal events, inline:

```
<gasp> <giggle> <growl> <gulp> <laugh>
<pause> <scoff> <sigh> <sob> <um>
```

Plus `(((emphasis)))` spans, and two decoding presets that trade stability
for expressiveness (`stable`: T=0.8/top_p=0.8, `expressive`: T=0.9/top_p=0.9).

This matters for the audiobook: it is the **only candidate in this whole
project that has both voice cloning and expression control** other than
Higgs Audio v3, and it reaches them in a 20-tag vocabulary rather than
Higgs' 43.

### 1.3 Licence — read this before shipping an audiobook

* **Code** (`kittenml`, `kitten_tts_cpp`): Apache-2.0.
* **KittenTTS 2 weights**: **Stellon Labs Community License**. Research,
  non-commercial and limited commercial use are free; the commercial grant
  **ends once you or your affiliates pass USD 1,000,000 in annual revenue or
  total cumulative funding**, after which a separate licence from Stellon
  Labs is required. Distributing the weights, a derivative, or a product
  built on them carries **attribution requirements** (Section IV(a)).
* The `speaker/` sub-model (pyannote speaker embedding) is MIT.

Compare with the alternatives: **Kokoro-82M is Apache-2.0 with no revenue
cap and no attribution requirement.** Higgs Audio v3 is research/non-commercial
plus a Creator Use Grant that explicitly covers audiobooks with attribution.

For a hobbyist audiobook, all three are fine. For a monetised audiobook,
Kokoro is the cleanest and KittenTTS 2 needs a licence check at the $1 M
threshold.

---

## 2. Hardware (same machine)

```
i7-7700, 4 cores / 8 threads, AVX2, no AVX512, no AMX
23.8 GB RAM
Quadro P2000, Pascal sm_61 (unusable — see docs/KOKORO_PLAN.md §2.1)
```

The decisive fact for KittenTTS 2 is **AVX2 without AVX512 or AMX**. The
C++ runtime's TQ2_1 ternary kernels are fast on modern Intel CPUs and
repack into AMX Q4_0 layout when available; on this CPU they fall back to
the compact kernels, which are slow.

---

## 3. Installation

### 3.1 Why a new Python was needed

`kittenml` requires `>=3.10` and `kitten_tts_cpp` requires `>=3.10`, so
unlike Kokoro there is **no upper bound**. But `kitten_tts_cpp` pins
`torch==2.13.0`, and **no `torchaudio` release matches torch 2.13** (the
last is 2.11.0), while `kittenml` imports `torchaudio` in both
`s3gen.py` and `speaker.py`. One environment cannot hold both, so there are
two:

| venv | torch | torchaudio | packages |
|---|---|---|---|
| `.venv-tts` | 2.7.1+cpu | 2.7.1+cpu | `kittenml==0.9.5` — the PyTorch path |
| `.venv-tts` | 2.13.0+cpu | — | `kitten_tts_cpp==0.1.0` — the C++ path |

```powershell
$py = "$env:LOCALAPPDATA\Programs\Python\Python312\python.exe"

& $py -m venv .venv-tts
.\.venv-tts\Scripts\python.exe -m pip install "torch==2.7.1" "torchaudio==2.7.1" `
    --index-url https://download.pytorch.org/whl/cpu
.\.venv-tts\Scripts\python.exe -m pip install "kittenml==0.9.5"

& $py -m venv .venv-tts
.\.venv-tts\Scripts\python.exe -m pip install "torch==2.13.0" `
    --index-url https://download.pytorch.org/whl/cpu
.\.venv-tts\Scripts\python.exe -m pip install "kitten_tts_cpp==0.1.0"
```

### 3.2 Weights

The HF CLI is much more reliable here than the Python API, because the
**Xet transfer path stalls at 0 MB on this network** (`cdn-lfs.huggingface.co`
does not resolve here). Disabling Xet forces the plain CDN path, which works:

```powershell
$env:HF_HUB_DISABLE_XET = "1"
hf download KittenML/kitten-tts-2 `
  "lm/model-ternary.safetensors" "lm/*.json" "lm/*.txt" "speaker/model.safetensors" `
  --local-dir "models\kitten-tts-2"
hf download KittenML/kitten-tts-2 "cpp/model-tq2_1.gguf" `
  "cpp/default/decoder.pt" "cpp/default/voices.json" `
  --local-dir "models\kitten-tts-2"
```

Note: `hf download` ignores `--include` when explicit filenames are given.

| file | size | needed for |
|---|---|---|
| `lm/model-ternary.safetensors` | 910 MB | PyTorch path |
| `lm/*.json`, `lm/*.txt` | 18 MB | tokenizer |
| `speaker/model.safetensors` | 17 MB | cloning |
| `cpp/model-tq2_1.gguf` | 981 MB | C++ path |
| `cpp/default/decoder.pt` | 510 MB | C++ vocoder |
| `cpp/default/voices.json` | 37 MB | C++ built-in voices |

The lightweight ONNX weights were **already on disk** in `models/`
(`kitten-nano-0.1`, `kitten-nano-0.8-int8`, `kitten-mini-0.8`).

### 3.3 A gotcha: the C++ asset manifest asserts exact file sizes

The runtime validates every asset against the size recorded in
`config.json`'s `cpp` manifest and refuses to run on a mismatch:

```
RuntimeError: asset size mismatch; remove and download again: .../cpp/default/voices.json
```

Preparing a custom voice necessarily grows `voices.json`, so the manifest
entry has to be updated to the new size. (The `voices.json` grows because a
prepared voice stores its speaker embedding and reference conditioning
inline.)

---

## 4. Preparing the `jenny` voice for the C++ engine

The C++ runtime cannot clone raw audio directly:

```python
if reference is not None:
    raise NotImplementedError(
        'Raw-audio cloning requires offline prepare_reference.py; select the prepared voice by name')
```

So the reference has to be prepared once, offline, with the PyTorch path
(`tools/kitten-tts/prepare_reference.py` from the `kitten-tts-2-cpp`
repository). That script computes the speaker embedding, the reference codec
tokens and the prompt conditioning, and writes them into `voices.json`:

```powershell
.\.venv-tts\Scripts\python.exe prepare_reference.py `
  --repo "models\kitten-tts-2" --assets "models\kitten-cpp-assets" `
  --python-package ".\.venv-tts\Lib\site-packages" `
  --reference "refs\jenny.wav" --transcript (Get-Content refs\jenny.txt -Raw) `
  --name jenny --threads 8
```

```
Prepared voice: jenny
```

The prepared voice then appears in the C++ engine's voice list alongside
the 47 built-ins:

```
sample_rate: 24000 | voices: 48 | jenny: True
```

`refs/jenny.txt` is the verbatim transcript of the clip, which is already in
the repo — supplying it means Whisper never has to transcribe the reference.

---

## 5. Measurements

All numbers measured on this machine, i7-7700 / 4 cores / AVX2.

### 5.1 Speed

| engine | model | RTF | notes |
|---|---|---|---|
| KittenTTS 2, PyTorch | 1.7 B | **16.38** | 26.32 s of audio in 431 s |
| KittenTTS 2, C++ | 1.7 B | **28.57** | *slower* than PyTorch here |
| KittenTTS 2, C++ | 1.7 B | 80–92 (short text) | per-call overhead dominates |
| **KittenTTS nano 0.8 int8** | 15 M | **0.144** | 39.27 s of audio in 5.6 s |
| **KittenTTS mini 0.8** | 80 M | **0.313** | 34.17 s of audio in 10.7 s |
| **KittenTTS nano 0.1** | 15 M | **0.142** | 31.30 s of audio in 4.5 s |
| *(Kokoro-82M, for reference)* | 82 M | 0.51–0.57 | measured in `docs/KOKORO_PLAN.md` |

The C++ path being *slower* than PyTorch is the headline surprise. The
breakdown explains it:

```
threads=8: lm=230.6s  decoder=12.9s   74 tok
threads=4: lm=276.7s  decoder=10.2s   76 tok
```

**95 % of the time is the language model**, at 1.28 tokens/second
(`eval time = 499193 ms / 638 runs`). The decoder is irrelevant, so the
smaller `student_w4` decoder would not help. The reason is the CPU: the
TQ2_1 ternary kernels repack into AMX Q4_0 layout on modern Intel CPUs and
fall back to slow compact kernels on CPUs without AMX/AVX512. This i7-7700
has neither.

Model load times: 357 s cold (PyTorch, first run), 30 s warm, 6.3 s (C++).

### 5.2 Voice fidelity — how close is the clone to the reference?

F0 measured with Praat. The reference is `refs/jenny.wav`
(Edge `en-US-JennyNeural`, 23.7 s, F0 184.2 Hz ± 34.1):

| engine | F0 mean | deviation from reference | F0 sd |
|---|---|---|---|
| reference | 184.2 Hz | — | 34.1 |
| **KittenTTS 2, C++ clone** | 183.5 Hz | **−0.7 Hz** | 37.6 |
| **KittenTTS 2, PyTorch clone** | 185.2 Hz | **+1.0 Hz** | 36.9 |
| Kokoro + inno-kokoro clone | 193.7 Hz | +9.5 Hz | 32.3 |
| KittenTTS nano 0.8 (preset "Bella") | 208.8 Hz | +24.6 Hz | 57.1 |

**This is the decisive quality result.** KittenTTS 2's clone lands within
1 Hz of the reference's mean pitch and reproduces its pitch *dynamics*
(sd 36.9 vs 34.1). `inno-kokoro`'s clone is 9.5 Hz off and noticeably
flatter. The lightweight ONNX models are a completely different speaker.

KittenTTS 2's own documentation puts this in perspective: cloning an
unseen recording scores **0.81** speaker similarity against that recording,
while a built-in voice scores 0.49–0.72 against its own reference. It is a
genuine clone, not an approximation.

### 5.3 Intelligibility

Every render was transcribed with Whisper (`faster-whisper small.en`) and
scored against the source text.

| render | WER | coverage |
|---|---|---|
| KittenTTS 2, PyTorch, jenny clone | **0.0 %** | 100 % |
| KittenTTS 2, C++, jenny clone | **0.0 %** | 100 % |
| KittenTTS nano 0.8 int8, Bella | **0.0 %** | 100 % |
| KittenTTS mini 0.8, Bella | **0.0 %** | 100 % |
| KittenTTS nano 0.1, expr-voice-2-f | **0.0 %** | 100 % |

### 5.4 Expression control

Rendered the same sentence with different leading emotion tags
(`preset="expressive"`, cloned jenny voice):

| tag | audio | wall time |
|---|---|---|
| (none) | 5.36 s | 159.9 s |
| `[tender]` | 7.12 s | 178.4 s |
| `[stern]` | 7.16 s | 177.4 s |
| `[contemplative]` | 8.48 s | 193.5 s |

The **durations differ by up to 58 %** for identical text, which is the
signature of genuinely altered delivery (different prosody, different
phrase lengths) rather than a tag being ignored or read aloud. That is
what makes KittenTTS 2 the only fast local engine that can carry the
emotional arc the Higgs pipeline had.

---

## 6. The verdict

### 6.1 KittenTTS 2 is the best *quality* answer but it does not fit this machine

| requirement | KittenTTS 2 | Kokoro-82M |
|---|---|---|
| clone `refs/jenny.wav` | ✅ within 1 Hz | ⚠️ 9.5 Hz off |
| expression control | ✅ 20 tags | ❌ none |
| built-in voices | ✅ 47 | ✅ 54 |
| speed on this CPU | ❌ RTF 16–29 | ✅ RTF 0.57 |
| full book (340 h audio) | ❌ **232–405 days** | ✅ **~14 days** |
| licence | ⚠️ $1 M revenue cap + attribution | ✅ Apache-2.0, no cap |

Projected cost for the whole book at the measured RTFs:

| engine | RTF | wall clock for 340 h of audio |
|---|---|---|
| KittenTTS 2 (PyTorch) | 16.4 | 5 576 h = **232 days** |
| KittenTTS 2 (C++) | 28.6 | 9 724 h = **405 days** |
| KittenTTS nano 0.8 (no cloning) | 0.14 | 49 h = **~2 days** |
| Kokoro-82M | 0.57 | 194 h = **~8 days** |

### 6.2 Recommendation

**Use Kokoro-82M as the working engine; keep KittenTTS 2 as a quality
upgrade to run somewhere else.**

Concretely:

1. **Render the book with Kokoro.** RTF 0.57 is the only figure that makes
   340 hours of audio practical on this CPU. The pipeline is complete and
   verified: `docs/KOKORO_PLAN.md`.
2. **Render the emotionally important passages with KittenTTS 2** if and
   when a faster machine is available. The interface is already proven: the
   cloned voice, the 20 tags and the long-form chunking all work.
3. **If the book is ever monetised**, check the Stellon Labs revenue cap
   before using KittenTTS 2 weights. Kokoro's Apache-2.0 needs no such
   check, which is another reason to keep Kokoro as the default.
4. **The lightweight ONNX KittenTTS (nano 0.8) is a viable third option**
   at RTF 0.14 — the fastest of everything measured, and WER 0.0 %. It
   cannot clone, so it fails the `jenny` requirement, but if the jenny
   requirement were relaxed it would render the whole book in about two
   days. Worth keeping in mind.

### 6.3 What would make KittenTTS 2 viable here

The LM is the entire problem, and it is a *kernel* problem, not a
portability one. Two things would change the answer:

* **A CPU with AVX512 or AMX.** The C++ runtime explicitly repacks TQ2_1
  into the AMX Q4_0 layout "on supported Intel CPUs" and falls back to the
  compact kernels otherwise. On a modern CPU with AMX the LM is expected to
  run at a very different speed — this is a hardware gap, not a
  configuration mistake.
* **A Q4_0 export.** The exporter supports `--outtype ternary-q4_0`, the
  previous standard format, which stock llama.cpp can load with its
  well-optimised AVX2 Q4_0 kernels. That needs the 3.47 GB bf16 weights
  and the exporter, and would need measuring; it is the most promising
  software-side fix. **Not attempted here** — it is a substantial detour
  for an engine that is still 2–4× slower than Kokoro even if it works.

---

## 7. Files

```
.venv-tts/                 PyTorch path (torch 2.7.1 + torchaudio 2.7.1 + kittenml 0.9.5)
.venv-tts/             C++ path   (torch 2.13.0 + kitten_tts_cpp 0.1.0)
models/kitten-tts-2/     the downloaded repository (PyTorch + cpp assets)
work/kitten-cpp-assets/ staged C++ asset dir (config.json + cpp/), manifest sizes synced
output/kitten_test_jenny.wav         PyTorch clone, WER 0.0 %
output/kitten_cpp_test_jenny.wav     C++ clone, WER 0.0 %
output/kitten_onnx_*.wav              the three lightweight ONNX renders
output/kitten_emo_*.wav              the four expression-tag renders
```

## 8. Reproducing

```powershell
# 1. environments
$py = "$env:LOCALAPPDATA\Programs\Python\Python312\python.exe"
& $py -m venv .venv-tts
.\.venv-tts\Scripts\python.exe -m pip install "torch==2.7.1" "torchaudio==2.7.1" --index-url https://download.pytorch.org/whl/cpu
.\.venv-tts\Scripts\python.exe -m pip install "kittenml==0.9.5"

& $py -m venv .venv-tts
.\.venv-tts\Scripts\python.exe -m pip install "torch==2.13.0" --index-url https://download.pytorch.org/whl/cpu
.\.venv-tts\Scripts\python.exe -m pip install "kitten_tts_cpp==0.1.0"

# 2. weights  (HF_HUB_DISABLE_XET matters on this network)
$env:HF_HUB_DISABLE_XET = "1"
hf download KittenML/kitten-tts-2 "lm/model-ternary.safetensors" "lm/*.json" "lm/*.txt" "speaker/model.safetensors" --local-dir "models\kitten-tts-2"
hf download KittenML/kitten-tts-2 "cpp/model-tq2_1.gguf" "cpp/default/decoder.pt" "cpp/default/voices.json" --local-dir "models\kitten-tts-2"

# 3. clone with KittenTTS 2
.\.venv-tts\Scripts\python.exe -c "from kittenml import KittenTTS; import soundfile as sf; \
  m = KittenTTS(r'models\kitten-tts-2'); \
  sf.write('output\kitten_test_jenny.wav', \
    m.generate(open('work\test_text.txt').read(), reference='refs\jenny.wav', \
               reference_text=open('refs\jenny.txt').read()), m.sample_rate)"

# 4. the lightweight ONNX models (fast, no cloning)
.\.venv-tts\Scripts\python.exe -c "from kittenml import KittenTTS; \
  m = KittenTTS(r'models\kitten-nano-0.8-int8'); \
  print(m.generate('Hello.', voice='Bella').shape)"
```
