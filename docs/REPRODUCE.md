# Reproducing the audiobook from zero

Everything needed to rebuild this pipeline on a clean machine, in order, with
the exact commands that were run. Nothing here depends on state that is not
recreated here.

---

## 0. What you need

| | requirement | why |
|---|---|---|
| OS | Windows, Linux or macOS | the pipeline is pure Python |
| Python | **3.12** (3.10-3.12) | `supertonic` and `faster-whisper` pin the supported range; 3.13+ is not supported |
| disk | ~2 GB free for the model and the environment, plus the audio itself | 383 MB of weights, ~0.5 GB of packages |
| RAM | 2 GB is enough | the model is 99 M parameters |
| ffmpeg | for the MP3 encode | a `soundfile` fallback exists but writes CBR only |
| network | for the one-time model download | |

Hardware notes for the machine this was built on (i7-7700, 4 cores, AVX2, no
AVX512, 23.8 GB RAM, unusable Pascal GPU): the pipeline is **CPU only** and
does not need a GPU at all. See `docs/SUPERTONIC.md` §2.

---

## 1. Install Python 3.12

The system Python on this machine is 3.14, which none of the pinned
packages support. Install 3.12 alongside it.

```powershell
winget install --id Python.Python.3.12 --exact --source winget `
  --silent --accept-package-agreements --accept-source-agreements --disable-interactivity
```

Verify:

```powershell
& "$env:LOCALAPPDATA\Programs\Python\Python312\python.exe" --version
# Python 3.12.10
```

On Linux/macOS, use your package manager or `pyenv install 3.12.10`.

---

## 2. Create the environment

```powershell
$py  = "$env:LOCALAPPDATA\Programs\Python\Python312\python.exe"
$root = "C:\Users\chint\project\ebook"

& $py -m venv "$root\.venv-tts"
```

The environment holds **one** set of packages for the whole system: the
render engine (ONNX Runtime) and the verifier (Whisper) together. There is no
PyTorch in it, which is why the model loads in under two seconds.

```powershell
& "$root\.venv-tts\Scripts\python.exe" -m pip install --upgrade pip
& "$root\.venv-tts\Scripts\python.exe" -m pip install -r "$root\requirements-tts.txt"
```

`requirements-tts.txt`:

```
supertonic==1.3.1      # the TTS engine (onnxruntime, no torch)
faster-whisper==1.2.1   # the verifier
soundfile>=0.13        # audio io, and the MP3 fallback writer
numpy>=2.0
scipy>=1.13            # resampling for the verifier
nltk>=3.9              # the English word list used to decode styled words
```

Verify:

```powershell
& "$root\.venv-tts\Scripts\python.exe" -c "import supertonic, faster_whisper, soundfile; print('ok')"
```

---

## 3. Get the model weights

398 MB of ONNX graphs and ten preset voice styles.

```powershell
$env:HF_HUB_DISABLE_XET = "1"

hf download supertone-oss-archive/supertonic-3 `
  --revision aafc6e32416a594460b32413efc49d7fe4ce6d46 `
  --include "onnx/*" "config.json" "voice_styles/*" `
  --local-dir "$root\models\supertonic-3"
```

> **`HF_HUB_DISABLE_XET=1` is not optional on some networks.** The Xet
> transfer path resolves `cdn-lfs.huggingface.co`, which does not resolve on
> this machine, and the download stalls at 0 MB. Disabling Xet forces the
> plain CDN path.

The revision is pinned so the weights are reproducible. The archive
organisation (`supertone-oss-archive`) replaced the original
(`Supertone/supertonic-3`) when the project was archived in September 2026;
the two hold the same files.

Check it arrived intact:

```powershell
$expected = @{
  "onnx\vector_estimator.onnx" = 256534781
  "onnx\vocoder.onnx"          = 101424195
  "onnx\text_encoder.onnx"     =  36416150
  "onnx\duration_predictor.onnx"=   3700147
  "onnx\tts.json"              =      8253
  "onnx\unicode_indexer.json"  =   277676
  "config.json"                =       174
}
foreach ($k in $expected.Keys) {
  $p = "$root\models\supertonic-3\$k"
  $actual = (Get-Item $p).Length
  "{0,-34} {1,12} {2}" -f $k, $actual, $(if ($actual -eq $expected[$k]) {"OK"} else {"MISMATCH"})
}
```

---

## 4. Run the pipeline

```powershell
$root = "C:\Users\chint\project\ebook"
$py   = "$root\.venv-tts\Scripts\python.exe"
Set-Location $root

# the whole chain, chapters 100 to 150
& $py -m tts_pipeline all --epub shadowslave.epub --from 100 --to 150
```

or stage by stage, which is what you want while iterating:

```powershell
& $py -m tts_pipeline extract --epub shadowslave.epub
& $py -m tts_pipeline clean
& $py -m tts_pipeline prep
& $py -m tts_pipeline render --from 100 --to 150 --voice F2
& $py -m tts_pipeline encode --from 100 --to 150 --bitrate 128k
& $py -m tts_pipeline verify --from 100 --to 150
& $py -m tts_pipeline status
```

The verify stage **samples 5 chapters per batch** by default, spread evenly
across the range and always including the first and the last. Transcribing a
chapter takes about two minutes, so checking all 3 220 would take roughly
115 hours, while 5 per batch takes about 10 minutes and still catches a
systematic fault. Adjust or disable it:

```powershell
& $py -m tts_pipeline verify --from 100 --to 150 --sample 8   # a larger sample
& $py -m tts_pipeline verify --from 100 --to 150 --sample 0   # verify everything
```

or interactively:

```powershell
& $py -m tts_pipeline --interactive
```

---

## 5. Interrupting and resuming

The render is resumable by design.

* **Ctrl-C** stops the current chapter, records it as `render_interrupted`
  and exits cleanly. Re-run the same command; the finished chapters are
  skipped and the interrupted one is redone.
* **A hard kill** (the window closed, the machine rebooted) leaves a
  `render_start` with no end. The ledger reconciles that to
  `interrupted` on the next read, and the chapter is queued again.
* **Deleting a WAV** also queues that chapter again, because "done" means
  *the ledger says so **and** the file is there*.

Nothing is ever half-written: audio is written to a temporary file and
renamed into place, so a file that exists is always complete.

### Deleting the WAVs is safe

Once a chapter is encoded to MP3, its WAV is an intermediate and can be
deleted to reclaim the space - about 43 MB a chapter, so 3.5 GB for a
batch of 81.

The resume check accepts **either** artifact, so a batch whose WAVs have
been cleaned up is still recognised as done and is not re-rendered:

```
=== audio artifacts for the finished chapters ===
  MP3 only (WAV deleted after encoding)      81
```

If you want to re-encode a chapter at a different bitrate, keep its WAV,
or re-render it with `--force`.

---

## 6. Authoring expression cues

The engine's expression tags are inline markup in the text. Put them in with
the per-chapter emotion maps.

```powershell
notepad "$root\work\emotion_maps\ch0100.json"
```

```json
{
  "chapter": 100,
  "title": "Clear Conscience",
  "rules": [
    {"match": "He was still sitting there", "tag": "<sigh>", "mode": "before"}
  ]
}
```

Then re-run the prep and the render:

```powershell
& $py -m tts_pipeline prep
& $py -m tts_pipeline render --from 100 --to 100 --force
```

A rule whose `match` is not unique in the chapter is reported rather than
applied, and a rule using a tag that the engine would *speak aloud* is
rejected. The allowed set is in `tts_pipeline/config.py`
(`SAFE_TAGS`, `RISKY_TAGS`, `BANNED_TAGS`).

---

## 7. Re-measuring the claims

The documentation in `docs/` reports numbers that were measured here. They
can be measured again.

```powershell
# which expression tags are markup and which are spoken aloud
& $py -m tts_pipeline.tools.classify_tags --repeats 3 `
    --report work\logs\tag_classification.json

# what each tag does to the audio
& $py -m tts_pipeline.tools.supertonic_tags `
    --report work\logs\tag_effects.json

# what the text still contains before it is rendered
& $py -m tts_pipeline.tools.audit_chapters --from 100 --to 150

# transcribe a folder of clips in one model load
& $py -m tts_pipeline.tools.batch_transcribe --dir work\logs\tag_clips `
    --pattern "*.wav" --out work\logs\transcripts.json
```

---

## 8. Rebuilding the environment from a different machine

The pipeline has no machine-specific state. To move it:

1. copy the repository;
2. recreate `.venv-tts` with Python 3.12 and install `requirements-tts.txt`;
3. re-download the weights into `models/supertonic-3` (or copy the folder -
   it is 383 MB and self-contained).

`work/` and `output/` are derived data and can be deleted and regenerated at
any time.

---

## 9. Troubleshooting

| symptom | cause | fix |
|---|---|---|
| the download stalls at 0 MB | the Xet transfer path cannot resolve on this network | `HF_HUB_DISABLE_XET=1` |
| `pip` refuses the packages | the system Python is 3.13+ | use Python 3.12 |
| every WER is ~47 % and the transcript is *nearly* right | faster-whisper assumes 16 kHz and does not resample a numpy input | already handled in `tts_pipeline/verify.py`; if you call Whisper yourself, resample first |
| `no such file: voices.npz` | the model directory is wrong | check `models/supertonic-3/onnx/` and `voice_styles/` |
| a chapter renders but is silent | the prepared text is empty | check `work/prepared/chNNNN.txt` and the prep report |
| the render is much slower than RTF 0.3 | a different machine | RTF is hardware-specific; re-measure with `status` |
