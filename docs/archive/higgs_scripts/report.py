"""Summarise the generated audiobook.

Lists every rendered chapter with duration, file sizes, and (if a
verification report exists) the measured intelligibility.

    python scripts\\report.py
"""

from __future__ import annotations

import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def probe_duration(path: Path) -> float:
    try:
        out = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "default=nw=1:nk=1", str(path)],
            capture_output=True, text=True, check=True,
        )
        return float(out.stdout.strip())
    except Exception:  # noqa: BLE001
        return 0.0


def main() -> int:
    out_dir = ROOT / "output"
    index_path = ROOT / "work" / "text" / "index.json"
    titles: dict[int, str] = {}
    if index_path.exists():
        for c in json.loads(index_path.read_text(encoding="utf-8")):
            titles[c["n"]] = c["title"]

    mp3s = sorted(out_dir.glob("ch*.mp3"))
    if not mp3s:
        print("no rendered chapters yet")
        return 0

    total = 0.0
    print(f"{'file':<16} {'minutes':>8} {'MB':>7}  {'WER':>6}  title")
    print("-" * 78)
    for m in mp3s:
        n = int(m.stem.replace("ch", ""))
        dur = probe_duration(m)
        total += dur
        ver = ROOT / "work" / f"verify_ch{n:04d}.json"
        wer_s = "-"
        if ver.exists():
            v = json.loads(ver.read_text(encoding="utf-8"))
            if v.get("wer_mean") is not None:
                wer_s = f"{v['wer_mean'] * 100:.1f}%"
        mb = m.stat().st_size / 1e6
        print(f"{m.name:<16} {dur / 60:8.1f} {mb:7.1f}  {wer_s:>6}  "
              f"{titles.get(n, '')[:38]}")

    print("-" * 78)
    print(f"{'TOTAL':<16} {total / 60:8.1f} min  "
          f"({total / 3600:.2f} h) across {len(mp3s)} chapter(s)")

    credits = out_dir / "credits.txt"
    if credits.exists():
        print(f"\ncredit: {credits.read_text(encoding='utf-8').splitlines()[0]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
