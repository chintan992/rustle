"""Download the candidate TTS model artifacts with the HuggingFace Python API.

The `hf` CLI stalls on large files in this environment; `hf_hub_download` has
been reliable (it resumes from .incomplete files).

Usage:
    python scripts\\fetch_alternatives.py omnivoice
    python scripts\\fetch_alternatives.py kokoro
    python scripts\\fetch_alternatives.py all
"""

from __future__ import annotations

import sys
import time
from pathlib import Path

from huggingface_hub import hf_hub_download

ROOT = Path(__file__).resolve().parent.parent

OMNIVOICE_REPO = "onnx-community/OmniVoice-Onnx"
OMNIVOICE_OUT = ROOT / "models" / "omnivoice-onnx"

KOKORO_REPO = "onnx-community/Kokoro-82M-v1.0-ONNX"
KOKORO_OUT = ROOT / "models" / "kokoro"

OMNIVOICE_FILES = [
    # ---- int4 build (CPU) ----
    "int4/llm_decoder.onnx",
    "int4/llm_decoder.onnx.data",
    "int4/audio_embeddings_encoder.onnx",
    "int4/audio_embeddings_encoder.onnx.data",
    "int4/audio_heads_decoder.onnx",
    # ---- fp16 build (CPU) ----
    "audio_embeddings_encoder.onnx",
    "audio_embeddings_encoder.onnx.data",
    "audio_heads_decoder.onnx",
    # ---- Higgs v2 codec (shared) ----
    "audio_tokenizer/acoustic_encoder.onnx",
    "audio_tokenizer/higgs_decoder.onnx",
    "audio_tokenizer/quantizer_encoder.onnx",
    "audio_tokenizer/semantic_encoder.onnx",
    "audio_tokenizer/fp16/acoustic_encoder.onnx",
    "audio_tokenizer/fp16/acoustic_encoder.onnx.data",
    "audio_tokenizer/fp16/higgs_decoder.onnx",
    "audio_tokenizer/fp16/higgs_decoder.onnx.data",
    "audio_tokenizer/fp16/quantizer_encoder.onnx",
    "audio_tokenizer/fp16/quantizer_encoder.onnx.data",
    "audio_tokenizer/fp16/semantic_encoder.onnx",
    "audio_tokenizer/fp16/semantic_encoder.onnx.data",
    # ---- code + config ----
    "inference.py",
    "higgs_inference.py",
    "eval.py",
    "config.json",
    "model_config.json",
    "omnivoice_manifest.json",
    "genai_config.json",
    "tokenizer.json",
    "tokenizer_config.json",
    "chat_template.jinja",
    "requirements.txt",
    "audio_tokenizer/higgs_inference.py",
]

KOKORO_FILES = [
    "onnx/model.onnx",
    "onnx/model_q8f16.onnx",
    "onnx/model_quantized.onnx",
    "config.json",
    "voices/af_heart.bin",
    "voices/af_bella.bin",
    "voices/af_nicole.bin",
    "voices/af_sarah.bin",
    "voices/af_sky.bin",
    "voices/bf_emma.bin",
    "voices/bf_isabella.bin",
    "voices/bf_lily.bin",
]


def fetch(repo: str, files: list[str], out: Path, tries: int = 3) -> None:
    out.mkdir(parents=True, exist_ok=True)
    for name in files:
        target = out / name
        if target.exists() and target.stat().st_size > 0:
            print(f"[skip] {name}")
            continue
        for attempt in range(1, tries + 1):
            t0 = time.time()
            try:
                p = hf_hub_download(
                    repo_id=repo, filename=name, local_dir=str(out)
                )
                got = Path(p)
                if got != target and got.exists():
                    target.parent.mkdir(parents=True, exist_ok=True)
                    got.replace(target)
                print(f"  ok  {name}  ({time.time() - t0:.0f}s)", flush=True)
                break
            except Exception as exc:  # noqa: BLE001
                print(f"  !! {name} attempt {attempt}/{tries}: {exc}", flush=True)
                time.sleep(3 * attempt)
        else:
            print(f"  FAILED {name}", flush=True)


def main() -> int:
    what = sys.argv[1] if len(sys.argv) > 1 else "all"
    if what in ("omnivoice", "all"):
        print("== OmniVoice ONNX ==")
        fetch(OMNIVOICE_REPO, OMNIVOICE_FILES, OMNIVOICE_OUT)
    if what in ("kokoro", "all"):
        print("== Kokoro-82M ONNX ==")
        fetch(KOKORO_REPO, KOKORO_FILES, KOKORO_OUT)
    print("done")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
