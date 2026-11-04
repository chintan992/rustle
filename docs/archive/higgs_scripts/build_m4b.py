"""Package rendered chapters into a single M4B audiobook with chapter markers.

ffmpeg's M4B muxer takes an ffmetadata file describing one chapter per
rendered chapter, so the result is a single file that audiobook players show
with a navigable table of contents.

Usage:
    python scripts\\build_m4b.py --chapters 0-20 --out output/shadowslave-1-21.m4b
"""

from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def parse_range(spec: str) -> list[int]:
    out: list[int] = []
    for part in spec.split(","):
        part = part.strip()
        if "-" in part:
            a, b = part.split("-", 1)
            out.extend(range(int(a), int(b) + 1))
        elif part:
            out.append(int(part))
    return sorted(set(out))


def fmt_time(seconds: float) -> str:
    h = int(seconds // 3600)
    m = int((seconds % 3600) // 60)
    s = int(seconds % 60)
    return f"{h:02d}:{m:02d}:{s:02d}.{int((seconds % 1) * 1000):03d}"


def probe_duration(path: Path) -> float:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=nw=1:nk=1", str(path)],
        capture_output=True, text=True, check=True,
    )
    return float(out.stdout.strip())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--chapters", required=True,
                    help="chapter numbers or ranges, e.g. 0-20 or 0,5,9-12")
    ap.add_argument("--out", default=str(ROOT / "output" / "shadowslave.m4b"))
    ap.add_argument("--title", default="Shadow Slave")
    ap.add_argument("--author", default="Cassandra Khaw")
    ap.add_argument("--out-dir", default=str(ROOT / "output"))
    ap.add_argument("--bitrate", default="64k")
    args = ap.parse_args()

    out_dir = Path(args.out_dir)
    index = json.loads((ROOT / "work" / "text" / "index.json").read_text(encoding="utf-8"))
    titles = {c["n"]: c["title"] for c in index}

    nums = parse_range(args.chapters)
    files = [(n, out_dir / f"ch{n:04d}.mp3") for n in nums]
    files = [(n, p) for n, p in files if p.exists()]
    if not files:
        print("no rendered chapters found")
        return 1

    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False,
                                     encoding="utf-8") as fh:
        meta_path = Path(fh.name)
        fh.write(";FFMETADATA1\n")
        fh.write(f"title={args.title}\n")
        fh.write(f"artist={args.author}\n")
        fh.write("genre=Fantasy\n")
        fh.write("comment=This audio was created with Boson AI's Higgs Audio "
                 "— https://www.boson.ai/higgs-audio\n")
        t = 0.0
        for n, p in files:
            dur = probe_duration(p)
            fh.write("\n[CHAPTER]\n")
            fh.write("TIMEBASE=1/1000\n")
            fh.write(f"START={int(t * 1000)}\n")
            fh.write(f"END={int((t + dur) * 1000)}\n")
            fh.write(f"title={titles.get(n, f'Chapter {n}')}\n")
            t += dur

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    # The chapter metadata file supplies title/artist and the chapter table;
    # the concat demuxer supplies the audio in order.
    cmd = [
        "ffmpeg", "-y", "-loglevel", "error",
        "-i", str(meta_path),
        "-f", "concat", "-safe", "0", "-i", str(_concat_list(files)),
        "-map_metadata", "1", "-map_chapters", "1",
        "-c:a", "aac", "-b:a", args.bitrate, "-movflags", "+faststart",
        str(out_path),
    ]
    subprocess.run(cmd, check=True)
    meta_path.unlink(missing_ok=True)
    print(f"wrote {out_path}  ({t / 3600:.2f} h, {len(files)} chapters)")
    return 0


def _concat_list(files) -> Path:
    p = Path(tempfile.gettempdir()) / "m4b_concat.txt"
    with p.open("w", encoding="utf-8") as fh:
        for _, f in files:
            fh.write(f"file '{f.resolve()}'\n")
    return p


if __name__ == "__main__":
    raise SystemExit(main())
