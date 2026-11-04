"""Render the same passage with every candidate reference voice.

Useful for picking the narrator voice before committing to a full chapter:
each candidate is a female Edge neural voice whose clipped audio is used as
the Higgs TTS 3 cloning reference.

Usage:
    python scripts\\voice_test.py                 # all candidates
    python scripts\\voice_test.py --only jenny    # one candidate
    python scripts\\voice_test.py --zero-shot    # model default voice
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from higgs_engine import SR, HiggsTTS, load_wav_mono24k  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent

PASSAGE = (
    "The rain had not stopped for three days, and the city had learned to live "
    "underneath it. People moved quickly between the awnings, heads down, "
    "saying very little."
)

CANDIDATES = ["aria", "jenny", "ava", "ana"]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model-dir", default=str(ROOT / "models" / "higgs-onnx-int4"))
    ap.add_argument("--refs-dir", default=str(ROOT / "refs"))
    ap.add_argument("--out-dir", default=str(ROOT / "work" / "voice_test"))
    ap.add_argument("--only", nargs="*", default=None)
    ap.add_argument("--zero-shot", action="store_true")
    ap.add_argument("--text", default=PASSAGE)
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    print(f"loading model ...", flush=True)
    t0 = time.time()
    engine = HiggsTTS(args.model_dir)
    print(f"  loaded in {time.time() - t0:.1f}s", flush=True)

    import soundfile as sf

    def save(name: str, wav: np.ndarray) -> None:
        path = out_dir / f"{name}.wav"
        sf.write(str(path), wav, SR, subtype="PCM_16")
        print(f"  wrote {path}  ({len(wav) / SR:.1f}s)", flush=True)

    if args.zero_shot:
        print("\n[zero-shot] model's own default voice", flush=True)
        res = engine.synthesize(args.text, max_seconds=60, seed=args.seed)
        print(f"  {res.seconds:.1f}s audio, rtf {res.rtf:.1f}x, stop={res.stop} "
              f"{res.notes}", flush=True)
        save("zero_shot", res.wav)
        return 0

    names = args.only or CANDIDATES
    for name in names:
        ref = Path(args.refs_dir) / f"{name}.wav"
        if not ref.exists():
            print(f"skip {name}: {ref} missing", file=sys.stderr)
            continue
        ref_text = ref.with_suffix(".txt")
        ref_text = ref_text.read_text(encoding="utf-8").strip() if ref_text.exists() else None

        codes_path = ROOT / "work" / "refcodes" / f"{name}.npy"
        codes_path.parent.mkdir(parents=True, exist_ok=True)
        if codes_path.exists():
            codes = np.load(codes_path)
        else:
            wav = load_wav_mono24k(ref)
            codes = engine.encode_audio(wav)
            np.save(codes_path, codes)

        print(f"\n[{name}] ref={ref.name} ({codes.shape[0]} frames)", flush=True)
        res = engine.synthesize(
            args.text, ref_codes=codes, ref_text=ref_text,
            max_seconds=60, seed=args.seed,
        )
        print(f"  {res.seconds:.1f}s audio, rtf {res.rtf:.1f}x, stop={res.stop} "
              f"{res.notes}", flush=True)
        save(name, res.wav)

    print(f"\nListen to the wavs in {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
