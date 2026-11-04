"""Download an ONNX build of Higgs-Audio-v3 TTS into models/<name>/<subdir>/.

Keeps the repo-relative subdirectory layout, because ONNX external-data files
must sit next to their graph under the original names.

Usage:
    python scripts\\fetch_onnx_build.py cpu_fp16
    python scripts\\fetch_onnx_build.py cuda_fp16
"""

from __future__ import annotations

import sys
from pathlib import Path

from huggingface_hub import hf_hub_download

REPO = "onnx-community/higgs-audio-v3-tts-4b"
ROOT = Path(__file__).resolve().parent.parent

# The audio sub-parts are shared between builds; only the LLM differs.
COMMON = [
    "text_embed.onnx",
    "audio_embed.onnx",
    "audio_heads.onnx",
    "audio_tokenizer.onnx",
    "audio_encoder.onnx",
    "tokenizer.json",
    "tokenizer_config.json",
    "chat_template.jinja",
    "genai_config.json",
    "manifest.json",
]


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    build = sys.argv[1]              # e.g. cpu_fp16
    out = ROOT / "models" / f"higgs-onnx-{build.replace('_', '-')}" / build
    out.mkdir(parents=True, exist_ok=True)

    files = [f"{build}/llm_decoder.onnx", f"{build}/llm_decoder.onnx.data"]
    files += [f"{build}/{n}" for n in COMMON]

    for name in files:
        target = out / name.split("/")[-1]
        if target.exists() and target.stat().st_size > 0:
            print(f"[skip] {name}")
            continue
        print(f"[get ] {name}", flush=True)
        p = hf_hub_download(repo_id=REPO, filename=name, local_dir=str(out))
        got = Path(p)
        flat = out / name.split("/")[-1]
        if got != flat and got.exists():
            got.replace(flat)
        print(f"  ok -> {flat.name}", flush=True)

    print("\nDownload complete:", out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
