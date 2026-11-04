"""Objective intelligibility check for generated audio.

Transcribes a wav with Whisper (running locally via faster-whisper) and reports
word error rate against the expected text. Used to verify that the INT4 ONNX
build actually produces intelligible speech, and to compare narrator voices /
sampling settings without having to listen to every sample.

Usage:
    python scripts\\verify.py work\\voice_test\\*.wav
    python scripts\\verify.py --expect "expected text" path\\to.wav
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

_MODEL = None


def get_model(size: str = "small.en", compute: str = "int8"):
    global _MODEL
    if _MODEL is None:
        from faster_whisper import WhisperModel

        _MODEL = WhisperModel(size, device="cpu", compute_type=compute)
    return _MODEL


CONTROL_TAG = re.compile(r"<\|[^|]*\|>")


def normalize(s: str) -> list[str]:
    # Strip Higgs control tags: they are instructions to the model, never spoken.
    s = CONTROL_TAG.sub(" ", s)
    s = s.lower()
    s = s.replace("\u2019", "'").replace("\u2018", "'")
    s = re.sub(r"[^a-z0-9'\s]", " ", s)
    # Apostrophes are not part of the spoken word - drop them so that
    # 'What?' and What? compare equal.
    s = re.sub(r"(?<![a-z])'|'(?![a-z])", " ", s)
    s = re.sub(r"'\s+(?=[a-z])", " ", s)
    return [w for w in s.split() if w]


def wer(ref: list[str], hyp: list[str]) -> float:
    """Levenshtein distance over word sequences -> WER."""
    if not ref:
        return 0.0 if not hyp else 1.0
    prev = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        cur = [i] + [0] * len(hyp)
        for j, h in enumerate(hyp, 1):
            cur[j] = min(
                prev[j] + 1,        # deletion
                cur[j - 1] + 1,     # insertion
                prev[j - 1] + (r != h),  # substitution
            )
        prev = cur
    return prev[len(hyp)] / len(ref)


def load_16k(path: Path) -> "np.ndarray":
    """Load any audio file as mono float32 @ 16 kHz (what Whisper wants).

    faster-whisper's file reader relies on a PyAV API that is not present in
    the installed PyAV build, so we decode with ffmpeg and hand Whisper a
    numpy array instead.
    """
    import subprocess
    import tempfile

    import numpy as np
    import soundfile as sf

    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        tmp_path = Path(tmp.name)
    try:
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-i", str(path),
             "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", str(tmp_path)],
            check=True,
        )
        wav, _ = sf.read(str(tmp_path), dtype="float32")
        return np.ascontiguousarray(wav)
    finally:
        tmp_path.unlink(missing_ok=True)


def transcribe(path: Path, size: str = "small.en") -> str:
    model = get_model(size)
    audio = load_16k(path)
    segments, info = model.transcribe(
        audio, beam_size=5, language="en", vad_filter=False
    )
    text = " ".join(s.text.strip() for s in segments)
    return text


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("wavs", nargs="+")
    ap.add_argument("--expect", default="", help="expected text (all files)")
    ap.add_argument("--expect-file", default="", help="file with the expected text")
    ap.add_argument("--model", default="small.en")
    ap.add_argument("--show", action="store_true", help="print the transcript")
    args = ap.parse_args()

    expect = args.expect
    if args.expect_file:
        expect = Path(args.expect_file).read_text(encoding="utf-8").strip()

    files: list[Path] = []
    for w in args.wavs:
        p = Path(w)
        if p.is_dir():
            files.extend(sorted(p.glob("*.wav")))
        else:
            files.append(p)

    if not expect:
        print("(no --expect given: transcript only)")
    for f in files:
        if not f.exists():
            print(f"{f}: MISSING")
            continue
        hyp = transcribe(f, args.model)
        line = f"{f.name:28s}"
        if expect:
            r, h = normalize(expect), normalize(hyp)
            e = wer(r, h)
            line += f"  WER {e * 100:5.1f}%  ({len(h)} words)"
        print(line)
        if args.show or not expect:
            print(f"    -> {hyp}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
