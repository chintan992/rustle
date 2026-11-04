"""Sweep Higgs sampling parameters over one chunk and score each result.

Finds the configuration that avoids the repetition/degeneration failure mode
(the "repeat guard" trip) while keeping the text intelligible.

Usage:
    python scripts\\tune.py --text "..." [--ref refs\\jenny.wav]
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from higgs_engine import HiggsTTS, load_wav_mono24k  # noqa: E402
from verify import get_model, load_16k, normalize, wer  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent

GRID = [
    # (temperature, top_k, top_p, max_repeat)
    (0.8, 50, 1.0, 32),    # current default
    (0.7, 50, 1.0, 32),
    (0.9, 50, 1.0, 32),
    (0.8, 50, 0.95, 32),
    (0.8, 80, 1.0, 32),
    (0.7, 80, 0.95, 32),
    (0.6, 50, 1.0, 32),
    (0.8, 50, 1.0, 64),    # looser repeat guard
    (0.85, 50, 0.95, 48),
]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--text", required=True)
    ap.add_argument("--ref", default=str(ROOT / "refs" / "jenny.wav"))
    ap.add_argument("--model-dir", default=str(ROOT / "models" / "higgs-onnx-int4"))
    ap.add_argument("--out-dir", default=str(ROOT / "work" / "tune"))
    ap.add_argument("--max-seconds", type=float, default=60.0)
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    engine = HiggsTTS(args.model_dir)
    model = get_model("small.en")

    ref_wav = Path(args.ref)
    ref_text = ref_wav.with_suffix(".txt").read_text(encoding="utf-8").strip()
    codes = engine.encode_audio(load_wav_mono24k(ref_wav))

    ref_words = normalize(args.text)
    print(f"expected: {len(ref_words)} words\n")

    results = []
    for temp, top_k, top_p, max_rep in GRID:
        t0 = time.time()
        res = engine.synthesize(
            args.text,
            ref_codes=codes,
            ref_text=ref_text,
            max_seconds=args.max_seconds,
            temperature=temp,
            top_k=top_k,
            top_p=top_p,
            seed=args.seed,
            max_repeat=max_rep,
        )
        path = out_dir / f"t{temp}_k{top_k}_p{top_p}_r{max_rep}.wav"
        import soundfile as sf

        sf.write(str(path), res.wav, 24000, subtype="PCM_16")

        audio = load_16k(path)
        segs, _ = model.transcribe(
            audio, beam_size=5, language="en", vad_filter=False
        )
        hyp = " ".join(s.text.strip() for s in segs)
        hyp_words = normalize(hyp)
        e = wer(ref_words, hyp_words)
        cov = len(hyp_words) / max(len(ref_words), 1)

        tag = f"T{temp} K{top_k} P{top_p} R{max_rep}"
        print(
            f"{tag:26s} {res.seconds:5.1f}s  wer {e * 100:5.1f}%  "
            f"cov {cov * 100:5.1f}%  stop={res.stop:6s}  ({time.time() - t0:.0f}s)"
        )
        results.append(
            {
                "temp": temp, "top_k": top_k, "top_p": top_p, "max_repeat": max_rep,
                "seconds": res.seconds, "wer": e, "coverage": cov,
                "stop": res.stop, "hyp": hyp, "file": path.name,
            }
        )

    results.sort(key=lambda r: (r["wer"] + (1 - r["coverage"])))
    print("\n--- ranked by (WER + missing) ---")
    for r in results[:5]:
        print(
            f"  T{r['temp']} K{r['top_k']} P{r['top_p']} R{r['max_repeat']}: "
            f"wer {r['wer'] * 100:.1f}% cov {r['coverage'] * 100:.1f}% "
            f"stop={r['stop']}"
        )
    (out_dir / "results.json").write_text(
        json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
