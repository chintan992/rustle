"""Per-segment intelligibility report for a synthesised chapter.

Transcribes every cached segment wav, compares it against the matching chunk
text from the chunk JSON, and reports WER per segment plus an aggregate.

    WER < 10%   : good
    WER 10-25%  : acceptable
    WER > 25%   : inspect

Also flags segments that look truncated: the whisper transcript is much
shorter than the chunk text, or the audio stopped on the repeat guard.

Usage:
    python scripts\\verify_chapter.py --chapter 0 --seg-dir work\\audio\\ch0000\\jenny__t0.8_k50_p1.0_s0
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify import get_model, load_16k, normalize, wer  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--chapter", type=int, default=0)
    ap.add_argument("--chunks", default="")
    ap.add_argument("--seg-dir", required=True)
    ap.add_argument("--model", default="small.en")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--show-worst", type=int, default=5)
    args = ap.parse_args()

    chunks_path = (
        Path(args.chunks)
        if args.chunks
        else ROOT / "work" / "chunks" / f"ch{args.chapter:04d}.json"
    )
    chunks = {c["i"]: c for c in json.loads(chunks_path.read_text(encoding="utf-8"))}
    seg_dir = Path(args.seg_dir)

    model = get_model(args.model)
    rows = []
    for c in sorted(chunks.values(), key=lambda x: x["i"]):
        if c.get("scene_break"):
            continue
        seg = seg_dir / f"seg_{c['i']:04d}.wav"
        if not seg.exists():
            continue
        if args.limit and len(rows) >= args.limit:
            break
        audio = load_16k(seg)
        segments, _ = model.transcribe(
            audio, beam_size=5, language="en", vad_filter=False
        )
        hyp = " ".join(s.text.strip() for s in segments)
        ref_w, hyp_w = normalize(c["text"]), normalize(hyp)
        e = wer(ref_w, hyp_w) if ref_w else 0.0
        # Coverage: how much of the chunk was actually spoken.
        coverage = len(hyp_w) / max(len(ref_w), 1)
        rows.append(
            {
                "i": c["i"],
                "wer": e,
                "coverage": coverage,
                "audio_s": len(audio) / 16000,
                "text": c["text"],
                "hyp": hyp,
            }
        )

    if not rows:
        print("no segments found")
        return 1

    import soundfile as sf

    total_audio = 0.0
    for r in rows:
        p = seg_dir / f"seg_{r['i']:04d}.wav"
        info = sf.info(str(p))
        r["audio_s"] = info.frames / info.samplerate
        total_audio += r["audio_s"]

    wers = [r["wer"] for r in rows]
    good = sum(1 for r in rows if r["wer"] < 0.10)
    ok = sum(1 for r in rows if r["wer"] < 0.25)
    trunc = [r for r in rows if r["coverage"] < 0.85]

    print(f"segments: {len(rows)}   total audio: {total_audio / 60:.1f} min")
    print(f"WER  mean {sum(wers) / len(wers) * 100:.1f}%   "
          f"median {sorted(wers)[len(wers) // 2] * 100:.1f}%   "
          f"max {max(wers) * 100:.1f}%")
    print(f"  <10% WER: {good}/{len(rows)}    <25% WER: {ok}/{len(rows)}")
    print(f"  truncated (coverage<85%): {len(trunc)}")

    worst = sorted(rows, key=lambda r: -r["wer"])[: args.show_worst]
    if worst:
        print("\nworst segments:")
        for r in worst:
            print(f"  [{r['i']:4d}] wer {r['wer'] * 100:5.1f}%  "
                  f"cov {r['coverage'] * 100:5.1f}%  {r['audio_s']:5.1f}s")
            print(f"        exp: {r['text'][:150]}")
            print(f"        hyp: {r['hyp'][:150]}")

    report = {
        "segments": len(rows),
        "total_audio_s": total_audio,
        "wer_mean": sum(wers) / len(wers),
        "wer_max": max(wers),
        "under_10pct": good,
        "under_25pct": ok,
        "truncated": len(trunc),
        "rows": [
            {k: v for k, v in r.items() if k != "text"} for r in rows
        ],
    }
    out = ROOT / "work" / f"verify_ch{args.chapter:04d}.json"
    out.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"\nreport -> {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
