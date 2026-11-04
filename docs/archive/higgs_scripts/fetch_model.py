"""Download the ONNX int4 CPU build of Higgs-Audio-v3 TTS (self-contained).

Files land in models/higgs-onnx-int4/. Only the sub-parts needed for
text->speech + voice cloning are pulled:

    llm_decoder.onnx(.data)  genai_config.json  manifest.json
    text_embed.onnx          audio_embed.onnx    audio_heads.onnx
    audio_tokenizer.onnx     audio_encoder.onnx
    tokenizer.json            tokenizer_config.json

Run:  python scripts\fetch_model.py
"""

from __future__ import annotations

import sys
from pathlib import Path

from huggingface_hub import hf_hub_download

REPO = "onnx-community/higgs-audio-v3-tts-4b"
OUT = Path(__file__).resolve().parent.parent / "models" / "higgs-onnx-int4"

FILES = [
    "cpu_int4/llm_decoder.onnx",
    "cpu_int4/llm_decoder.onnx.data",
    "cpu_int4/genai_config.json",
    "cpu_int4/manifest.json",
    "cpu_int4/text_embed.onnx",
    "cpu_int4/audio_embed.onnx",
    "cpu_int4/audio_heads.onnx",
    "cpu_int4/audio_tokenizer.onnx",
    "cpu_int4/audio_encoder.onnx",
    "cpu_int4/tokenizer.json",
    "cpu_int4/tokenizer_config.json",
    "cpu_int4/chat_template.jinja",
    # root-level samples (reference voice + demo output)
    "ref.wav",
    "hello.wav",
]


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    for name in FILES:
        target = OUT / name.replace("/", "__")
        if target.exists():
            print(f"[skip] {name}")
            continue
        print(f"[get ] {name} ...", flush=True)
        try:
            p = hf_hub_download(
                repo_id=REPO,
                filename=name,
                local_dir=str(OUT),
                local_dir_use_symlinks=False,
            )
        except Exception as exc:  # noqa: BLE001
            print(f"  !! failed: {exc}", file=sys.stderr)
            return 1
        # hf_hub_download keeps the repo-relative path; flatten it.
        flat = OUT / name.replace("/", "__")
        got = Path(p)
        if got != flat and got.exists():
            got.replace(flat)
        print(f"  ok -> {flat.name}", flush=True)
    print("\nDownload complete:", OUT)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
