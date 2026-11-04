"""Verify a rendered chapter and re-render the segments that fail.

The INT4 build is stochastic, so a chapter can come out with a handful of bad
segments even when every take was scored during synthesis. This pass:

  1. transcribes every segment and scores WER + coverage,
  2. deletes the segments that miss the thresholds (so the resume cache
     treats them as missing),
  3. re-runs the synthesis for just those segments,
  4. repeats until the chapter is clean or the round budget runs out.

Usage:
    python scripts\\repair_chapter.py --chapter 0 --ref refs\\jenny.wav --rounds 3
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify import get_model, load_16k, normalize, wer  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent


def score_chapter(chapter: int, seg_dir: Path, model) -> list[dict]:
    chunks_path = ROOT / "work" / "chunks" / f"ch{chapter:04d}.json"
    chunks = {c["i"]: c for c in json.loads(chunks_path.read_text(encoding="utf-8"))}
    rows = []
    for c in sorted(chunks.values(), key=lambda x: x["i"]):
        if c.get("scene_break"):
            continue
        seg = seg_dir / f"seg_{c['i']:04d}.wav"
        if not seg.exists():
            continue
        audio = load_16k(seg)
        segments, _ = model.transcribe(
            audio, beam_size=5, language="en", vad_filter=False
        )
        hyp = " ".join(s.text.strip() for s in segments)
        ref_w, hyp_w = normalize(c["text"]), normalize(hyp)
        rows.append(
            {
                "i": c["i"],
                "wer": wer(ref_w, hyp_w) if ref_w else 0.0,
                "coverage": len(hyp_w) / max(len(ref_w), 1),
                "text": c["text"],
                "hyp": hyp,
            }
        )
    return rows


def stale_segments(args, seg_dir: Path) -> list[int]:
    """Segment indices whose chunk text changed because of a --fixes file."""
    chunks_path = ROOT / "work" / "chunks" / f"ch{args.chapter:04d}.json"
    old = {c["i"]: c["text"] for c in
           json.loads(chunks_path.read_text(encoding="utf-8"))}
    tmp = ROOT / "work" / "chunks" / f"ch{args.chapter:04d}.new.json"
    subprocess.run(
        [
            sys.executable, str(ROOT / "scripts" / "prep_text.py"),
            "--chapter", str(ROOT / "work" / "text" / f"ch{args.chapter:04d}.txt"),
            "--out", str(tmp),
            "--max-chars", str(args.max_chars),
            "--fixes", args.fixes,
        ]
        + (["--emotion-map", args.emotion_map] if args.emotion_map else []),
        check=True, stdout=subprocess.DEVNULL,
    )
    new = {c["i"]: c["text"] for c in json.loads(tmp.read_text(encoding="utf-8"))}
    tmp.unlink(missing_ok=True)
    return [i for i in new if i in old and new[i] != old[i]]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--chapter", type=int, default=0)
    ap.add_argument("--ref", default=str(ROOT / "refs" / "jenny.wav"))
    ap.add_argument("--seg-dir", default="")
    ap.add_argument("--emotion-map", default="")
    ap.add_argument("--fixes", default="",
                    help="JSON file with pronunciation/wording overrides")
    ap.add_argument("--max-chars", type=int, default=200)
    ap.add_argument("--retries", type=int, default=3)
    ap.add_argument("--rounds", type=int, default=3)
    ap.add_argument("--max-wer", type=float, default=0.15)
    ap.add_argument("--min-coverage", type=float, default=0.90)
    ap.add_argument("--model", default="small.en")
    args = ap.parse_args()

    seg_dir = Path(args.seg_dir) if args.seg_dir else None
    if seg_dir is None:
        base = ROOT / "work" / "audio" / f"ch{args.chapter:04d}"
        cands = [d for d in base.iterdir() if d.is_dir()] if base.exists() else []
        if not cands:
            print(f"no segment directory under {base}")
            return 1
        seg_dir = max(cands, key=lambda d: d.stat().st_mtime)
    print(f"segment dir: {seg_dir}")

    model = get_model(args.model)
    for rnd in range(1, args.rounds + 1):
        rows = score_chapter(args.chapter, seg_dir, model)
        bad = [
            r for r in rows
            if r["wer"] > args.max_wer or r["coverage"] < args.min_coverage
        ]
        mean = sum(r["wer"] for r in rows) / max(len(rows), 1)
        print(
            f"\nround {rnd}: {len(rows)} segments, mean WER {mean * 100:.1f}%, "
            f"{len(bad)} failing"
        )
        if not bad:
            print("chapter is clean.")
            report = {
                "chapter": args.chapter,
                "segments": len(rows),
                "wer_mean": mean,
                "wer_max": max((r["wer"] for r in rows), default=0.0),
                "under_10pct": sum(1 for r in rows if r["wer"] < 0.10),
                "under_25pct": sum(1 for r in rows if r["wer"] < 0.25),
                "failing": len(bad),
                "rows": [{k: v for k, v in r.items() if k != "text"} for r in rows],
            }
            (ROOT / "work" / f"verify_ch{args.chapter:04d}.json").write_text(
                json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8"
            )
            return 0

        for r in bad:
            seg = seg_dir / f"seg_{r['i']:04d}.wav"
            seg.unlink(missing_ok=True)
            print(f"  dropped seg {r['i']:4d}  wer {r['wer'] * 100:5.1f}%  "
                  f"cov {r['coverage'] * 100:5.1f}%")

        # If a fixes file is in play the chunk text may have changed, which
        # makes the cached segment stale even if it scored well.
        if args.fixes:
            stale = stale_segments(args, seg_dir)
            for i in stale:
                seg = seg_dir / f"seg_{i:04d}.wav"
                seg.unlink(missing_ok=True)
                print(f"  dropped seg {i:4d}  (text changed by --fixes)")

        cmd = [
            sys.executable, str(ROOT / "scripts" / "synthesize.py"),
            "--chapter", str(args.chapter),
            "--ref", args.ref,
            "--max-chars", str(args.max_chars),
            "--retries", str(args.retries),
        ]
        if args.emotion_map:
            cmd += ["--emotion-map", args.emotion_map]
        if args.fixes:
            cmd += ["--fixes", args.fixes]
        # Keep the title card and the spoken licence credit in the re-assembly.
        titles = {
            c["n"]: c["title"]
            for c in json.loads(
                (ROOT / "work" / "text" / "index.json").read_text(encoding="utf-8")
            )
        }
        if args.chapter in titles:
            cmd += ["--title", titles[args.chapter], "--title-card", "--credit"]
        print("  re-rendering ...", flush=True)
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL)

    print("\nrounds exhausted; re-run to continue repairing.")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
