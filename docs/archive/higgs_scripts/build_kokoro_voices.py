"""Combine Kokoro's per-voice .bin files into one .npz.

The `onnx-community/Kokoro-82M-v1.0-ONNX` repo ships one raw float32
``.bin`` per voice (510 x 256), but the `kokoro_onnx` package expects a
single ``.npz`` with one key per voice.

Usage:
    python scripts\\build_kokoro_voices.py
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
VOICE_DIR = ROOT / "models" / "kokoro" / "voices"
OUT = VOICE_DIR / "voices.npz"


def main() -> int:
    bins = sorted(p for p in VOICE_DIR.glob("*.bin"))
    if not bins:
        print(f"no .bin voice files in {VOICE_DIR}")
        return 1

    voices: dict[str, np.ndarray] = {}
    for b in bins:
        a = np.fromfile(b, dtype=np.float32)
        if a.size % 256:
            print(f"  skip {b.name} (size {a.size} not a multiple of 256)")
            continue
        voices[b.stem] = a.reshape(-1, 256)

    np.savez(OUT, **voices)
    print(f"wrote {OUT}  ({len(voices)} voices, "
          f"{OUT.stat().st_size / 1e6:.1f} MB)")
    female = [k for k in voices if k.startswith(("af_", "bf_"))]
    print(f"  female voices: {', '.join(sorted(female))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
