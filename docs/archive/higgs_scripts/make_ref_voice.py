"""Generate local reference-voice clips for Higgs TTS 3 voice cloning.

Higgs recommends 5-30 s of clean, single-speaker speech plus a verbatim
transcript of that clip. We synthesise the reference with Microsoft Edge
neural voices (free, no API key) and pick a female narrator whose delivery
matches the intended audiobook voice: friendly, clear, engaging, soothing.

Output: refs/<name>.wav  (24 kHz mono PCM16)  and  refs/<name>.txt  (transcript)
"""

from __future__ import annotations

import argparse
import asyncio
import shutil
import subprocess
import sys
from pathlib import Path

import edge_tts

OUT = Path(__file__).resolve().parent.parent / "refs"

# The spoken reference text. Kept neutral, warm and declarative so the cloned
# voice carries a "narrator" register rather than a "demo" register.
REFERENCE_TEXT = (
    "The rain had not stopped for three days, and the city had learned to live "
    "underneath it. People moved quickly between the awnings, heads down, "
    "saying very little. There was a kind of quiet in the streets that was not "
    "peace at all, but rather the held breath before something changes. "
    "And in a small room above the old market, someone finally began to speak."
)

# Edge neural voices -> a short label for the candidate filename.
CANDIDATES = {
    "aria": "en-US-AriaNeural",
    "jenny": "en-US-JennyNeural",
    "ava": "en-US-AvaMultilingualNeural",
    "ana": "en-US-AnaNeural",
}

RATE = "-8%"      # slightly slower -> calmer, more soothing
PITCH = "+0Hz"


def to_wav_24k(mp3: Path, wav: Path) -> None:
    """Convert the mp3 edge-tts returns into 24 kHz mono PCM16 wav."""
    subprocess.run(
        [
            "ffmpeg", "-y", "-loglevel", "error",
            "-i", str(mp3),
            "-ac", "1", "-ar", "24000", "-c:a", "pcm_s16le",
            str(wav),
        ],
        check=True,
    )


async def synth_one(name: str, voice: str, retries: int = 4) -> Path:
    OUT.mkdir(parents=True, exist_ok=True)
    mp3 = OUT / f"{name}.mp3"
    wav = OUT / f"{name}.wav"
    txt = OUT / f"{name}.txt"

    last: Exception | None = None
    for attempt in range(1, retries + 1):
        try:
            comm = edge_tts.Communicate(REFERENCE_TEXT, voice, rate=RATE, pitch=PITCH)
            await comm.save(str(mp3))
            last = None
            break
        except Exception as exc:  # noqa: BLE001
            last = exc
            print(f"  retry {attempt}/{retries}: {type(exc).__name__}: {exc}", file=sys.stderr)
            await asyncio.sleep(2 * attempt)
    if last is not None:
        raise last

    to_wav_24k(mp3, wav)
    txt.write_text(REFERENCE_TEXT, encoding="utf-8")
    return wav


async def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", nargs="*", default=None, help="subset of candidate names")
    args = ap.parse_args()

    names = args.only or list(CANDIDATES)
    for name in names:
        if name not in CANDIDATES:
            print(f"unknown candidate {name!r}", file=sys.stderr)
            return 1
        wav = await synth_one(name, CANDIDATES[name])
        dur = 0.0
        try:
            out = subprocess.run(
                ["ffprobe", "-v", "error", "-show_entries", "format=duration",
                 "-of", "default=nw=1:nk=1", str(wav)],
                capture_output=True, text=True, check=True,
            )
            dur = float(out.stdout.strip())
        except Exception:  # noqa: BLE001
            pass
        print(f"[ok] {name:6s} {CANDIDATES[name]:32s} -> {wav.name}  ({dur:.1f}s)")


if __name__ == "__main__":
    if shutil.which("edge-tts") is None and "edge_tts" not in sys.modules:
        pass
    raise SystemExit(asyncio.run(main()))
