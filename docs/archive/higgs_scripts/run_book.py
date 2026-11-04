"""Batch-narrate a range of chapters.

For each chapter: chunk the text (if needed), synthesise every segment,
assemble the wav/mp3, then score intelligibility with Whisper.

    python scripts\\run_book.py --from 0 --to 10 --ref refs\\jenny.wav
    python scripts\\run_book.py --from 0 --to 10 --skip-verify   # faster

State lives in work/ and output/, so re-running skips finished chapters.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PY = sys.executable
SCRIPTS = ROOT / "scripts"


def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, check=True, **kw)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--from", dest="start", type=int, default=0)
    ap.add_argument("--to", dest="end", type=int, default=0,
                    help="inclusive; 0 = just the start chapter")
    ap.add_argument("--ref", default=str(ROOT / "refs" / "jenny.wav"))
    ap.add_argument("--temperature", type=float, default=0.8)
    ap.add_argument("--top-k", type=int, default=50)
    ap.add_argument("--top-p", type=float, default=1.0)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--max-chars", type=int, default=200)
    ap.add_argument("--retries", type=int, default=2)
    ap.add_argument("--skip-verify", action="store_true")
    ap.add_argument("--skip-existing", action="store_true",
                    help="skip chapters that already have an mp3")
    args = ap.parse_args()

    end = args.end if args.end else args.start
    index = json.loads((ROOT / "work" / "text" / "index.json").read_text(encoding="utf-8"))

    summary = []
    for n in range(args.start, end + 1):
        meta = next((c for c in index if c["n"] == n), None)
        if meta is None:
            print(f"chapter {n} not in index")
            continue

        mp3 = ROOT / "output" / f"ch{n:04d}.mp3"
        if args.skip_existing and mp3.exists():
            print(f"[{n}] exists, skipping")
            continue

        title = meta["title"]
        print(f"\n{'=' * 70}\n[chapter {n}] {title}  ({meta['words']} words)\n{'=' * 70}",
              flush=True)

        txt = ROOT / "work" / "text" / f"ch{n:04d}.txt"
        chunks = ROOT / "work" / "chunks" / f"ch{n:04d}.json"
        run([PY, str(SCRIPTS / "prep_text.py"), "--chapter", str(txt),
             "--out", str(chunks), "--max-chars", str(args.max_chars)])

        t0 = time.time()
        run([PY, str(SCRIPTS / "synthesize.py"),
             "--chapter", str(n),
             "--ref", args.ref,
             "--title", title,
             "--title-card",
             "--temperature", str(args.temperature),
             "--top-k", str(args.top_k),
             "--top-p", str(args.top_p),
             "--seed", str(args.seed),
             "--retries", str(args.retries)])
        elapsed = time.time() - t0

        if not args.skip_verify:
            seg_dir = (ROOT / "work" / "audio" / f"ch{n:04d}")
            if seg_dir.exists():
                for d in sorted(seg_dir.iterdir()):
                    if d.is_dir():
                        run([PY, str(SCRIPTS / "verify_chapter.py"),
                             "--chapter", str(n), "--seg-dir", str(d)])

        summary.append({"n": n, "title": title, "words": meta["words"],
                        "seconds": elapsed})

    (ROOT / "work" / "batch_summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    print("\n=== batch summary ===")
    for s in summary:
        print(f"  ch{s['n']:04d}  {s['seconds'] / 60:6.1f} min  {s['words']:6d} w  {s['title'][:50]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
