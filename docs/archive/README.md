# Archive — the superseded Higgs Audio v3 pipeline

This is the earlier attempt at the audiobook, built on **Boson AI's Higgs
Audio v3 TTS** (`bosonai/higgs-audio-v3-4b`, 4.65 B parameters).

It is **kept for reference only and is not part of the working pipeline**.
Nothing here is imported, run, or referenced by `tts_pipeline/`.

## Why it was superseded

| | Higgs Audio v3 | Supertonic 3 (in use) |
|---|---|---|
| RTF on this CPU | ~10 (measured: 10.5 min of audio in ~3 h) | **0.28-0.31** |
| full book (340 h) | 10-12 weeks | **~8 days** |
| expression control | 43 tags | 20 inline tags |
| weights | 2.3 GB int4 | 383 MB |
| licence | research + Creator Use Grant (audiobooks allowed, with attribution) | MIT code + OpenRAIL-M weights |

The decisive factor is speed. Higgs is an autoregressive speech language
model that performs 25 sequential decoder steps per second of audio, so it is
memory-bandwidth bound on a 4-core CPU. Supertonic generates the whole
utterance in one forward pass.

## What is in here

| path | what it is |
|---|---|
| `higgs_scripts/` | the original 27-script pipeline: EPUB extraction, text prep, the ONNX engine, Whisper scoring, take selection, M4B assembly, and a Colab notebook generator |
| `higgs_colab/` | the generated Google Colab notebook (T4 GPU) for the same pipeline |
| `ALTERNATIVES.md` | the survey that compared Higgs against Kokoro, KittenTTS, OmniVoice, Qwen3-TTS, Fish Speech and others |
| `higgs_requirements.txt` | the packages that pipeline needed (Python 3.14, ONNX Runtime, transformers) |

## If you want to run it anyway

The research in `ALTERNATIVES.md` and `docs/TTS_OPTIONS.md` still stands, and
the code is intact. It needs:

* Python 3.14 (its requirements pin `transformers>=5.5` and
  `onnxruntime>=1.31`);
* the ONNX weights, about 2.3 GB for the int4 build or 8.3 GB for fp16;
* roughly 30 GB of disk for the work directories.

Run it from its own directory so it does not collide with `work/` and
`output/`.

## A note on the measurements

`ALTERNATIVES.md` and `docs/TTS_OPTIONS.md` contain numbers measured on this
machine for several engines. Those environments and weights were removed
during the disk cleanup, so re-running them means recreating the
environments from the documented `pip` lines. The conclusions do not change:
every model with the same feature set as Higgs was measured at 20-50x
slower.
