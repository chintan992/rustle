"""A/B a single chunk across sampling configs and control-tag settings.

Answers: does the Higgs control tag change the degeneration behaviour?

    python scripts\\ab_test.py --text "..." --tags "<|emotion:contemplation|>"
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from higgs_engine import HiggsTTS, load_wav_mono24k  # noqa: E402
from verify import get_model, load_16k, normalize, wer  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent

CONFIGS = [
    (0.7, 80, 0.95),
    (0.8, 50, 0.95),
    (0.8, 50, 1.00),
    (0.9, 50, 0.95),
]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--text", required=True)
    ap.add_argument("--tags", default="")
    ap.add_argument("--ref", default=str(ROOT / "refs" / "jenny.wav"))
    ap.add_argument("--model-dir", default=str(ROOT / "models" / "higgs-onnx-int4"))
    ap.add_argument("--out-dir", default=str(ROOT / "work" / "ab"))
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    engine = HiggsTTS(args.model_dir)
    model = get_model("small.en")

    ref_wav = Path(args.ref)
    ref_text = ref_wav.with_suffix(".txt").read_text(encoding="utf-8").strip()
    codes = engine.encode_audio(load_wav_mono24k(ref_wav))

    import soundfile as sf

    ref_words = normalize(args.text)
    for tag in ["", args.tags]:
        for temp, top_k, top_p in CONFIGS:
            t0 = time.time()
            res = engine.synthesize(
                args.tags + args.text if tag else args.text,
                ref_codes=codes, ref_text=ref_text, max_seconds=90,
                temperature=temp, top_k=top_k, top_p=top_p, seed=args.seed,
            )
            name = f"{'tag' if tag else 'notag'}_t{temp}_k{top_k}_p{top_p}.wav"
            path = out_dir / name
            sf.write(str(path), res.wav, 24000, subtype="PCM_16")
            audio = load_16k(path)
            segs, _ = model.transcribe(
                audio, beam_size=5, language="en", vad_filter=False
            )
            hyp = " ".join(s.text.strip() for s in segs)
            hw = normalize(hyp)
            e = wer(ref_words, hw)
            cov = len(hw) / max(len(ref_words), 1)
            print(
                f"{'TAG  ' if tag else 'NOTAG'} T{temp} K{top_k} P{top_p}: "
                f"{res.seconds:5.1f}s wer {e * 100:5.1f}% cov {cov * 100:6.1f}% "
                f"stop={res.stop:6s} ({time.time() - t0:.0f}s)",
                flush=True,
            )
            if e > 0.2:
                print(f"        hyp: {hyp[:160]}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
