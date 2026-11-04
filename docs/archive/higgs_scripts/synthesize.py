"""Synthesise a chapter of the audiobook with the local Higgs TTS 3 engine.

Pipeline
--------
  work/text/chNNNN.txt          (from scripts/extract_epub.py)
        | prep_text.py
  work/chunks/chNNNN.json        sentences grouped into ~260-char chunks
        | synthesize.py  (this file)
  work/audio/chNNNN/seg_XXXX.wav one wav per chunk  (resumable)
        | assemble
  output/chNNNN.wav  +  output/chNNNN.mp3

Resumability: every chunk is written to disk as it completes, so an
interrupted run continues where it stopped instead of re-synthesising.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from higgs_engine import SR, HiggsTTS, load_wav_mono24k  # noqa: E402
from quality import QualityScorer  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent

# Pacing (seconds of silence).
GAP_CHUNK = 0.22      # between chunks inside one paragraph
GAP_PARA = 0.62       # between paragraphs
GAP_SCENE = 1.60      # scene break (***)
LEAD_IN = 0.90        # silence before the first word
LEAD_OUT = 1.20       # silence after the last word

# Speech rate calibration for the truncation check: a calm audiobook narrator
# runs ~150 wpm, i.e. ~15 characters per second. Measured against the
# reference renderings in work/voice_test (15.0-18.8 chars/s).
CHARS_PER_SEC = 15.0
MIN_RATIO = 0.62      # below this the chunk is almost certainly truncated

# Spoken attribution required by the Boson AI Higgs Audio Creator Use Grant.
CREDIT_LINE = (
    "This audio was created with Boson A I's Higgs Audio. "
    "Boson A I dot, www dot boson dot A I slash higgs audio."
)


def silence(seconds: float) -> np.ndarray:
    return np.zeros(int(seconds * SR), dtype=np.float32)


def ref_codes_cached(engine: HiggsTTS, ref_wav: Path, cache: Path) -> np.ndarray:
    """Encode the reference clip once and cache the codes."""
    if cache.exists():
        return np.load(cache)
    print(f"encoding reference voice: {ref_wav.name}", flush=True)
    wav = load_wav_mono24k(ref_wav)
    codes = engine.encode_audio(wav)
    cache.parent.mkdir(parents=True, exist_ok=True)
    np.save(cache, codes)
    print(f"  -> {codes.shape[0]} frames ({codes.shape[0] / 25:.1f}s) cached", flush=True)
    return codes


def write_wav(path: Path, wav: np.ndarray) -> None:
    import soundfile as sf

    path.parent.mkdir(parents=True, exist_ok=True)
    sf.write(str(path), wav, SR, subtype="PCM_16")


def expected_seconds(text: str) -> float:
    """Expected spoken duration of ``text`` at a calm narration pace."""
    return max(len(text) / CHARS_PER_SEC, 0.4)


def render_chunk(engine, chunk_text, ref_codes, ref_text, args, scorer=None):
    """Render one chunk, retrying until it passes the quality check.

    The INT4 build is noticeably stochastic: the same text and the same
    parameters can come out perfect or degenerate (the model restarting the
    utterance in a loop), and a duration check cannot tell those apart.

    So each attempt varies BOTH the seed and the sampling parameters, and the
    take is validated by transcribing it (Whisper) when a scorer is available.
    The best take is kept when no attempt reaches the acceptance threshold.
    """
    want = expected_seconds(chunk_text)
    check_len = len(chunk_text) >= 80     # short texts are too noisy to judge

    # Alternate sampling configs across attempts — varying the seed alone is
    # not enough, because some (text, config) pairs fail deterministically.
    fallback_configs = [
        (args.temperature, args.top_k, args.top_p),
        (0.8, 50, 0.95),
        (0.9, 50, 0.95),
        (0.8, 80, 0.95),
    ]

    best = None
    best_score = None
    attempts = []

    for attempt in range(args.retries + 1):
        temp, top_k, top_p = fallback_configs[attempt % len(fallback_configs)]
        res = engine.synthesize(
            chunk_text,
            ref_codes=ref_codes,
            ref_text=ref_text,
            max_seconds=args.max_seconds,
            temperature=temp,
            top_k=top_k,
            top_p=top_p,
            seed=args.seed + attempt,
        )
        ratio = res.seconds / want
        score = scorer.score(res.wav, SR, chunk_text) if scorer else {}
        attempts.append(
            {
                "attempt": attempt,
                "seed": args.seed + attempt,
                "params": [temp, top_k, top_p],
                "seconds": res.seconds,
                "ratio": round(ratio, 3),
                "stop": res.stop,
                "wer": round(score["wer"], 3) if score else None,
                "coverage": round(score["coverage"], 3) if score else None,
            }
        )

        # Keep the take that is verifiably closest to the source text.
        if score:
            rank = (score["wer"], -score["coverage"])
            if best_score is None or rank < best_score:
                best_score, best = rank, res
            if QualityScorer.is_good(score):
                return res, attempts
        else:
            complete = (not check_len) or ratio >= MIN_RATIO
            if best is None or res.seconds > best.seconds:
                best = res
            if complete and (res.stop == "eoc" or ratio >= 0.85):
                return res, attempts

    return best, attempts


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--chapter", type=int, default=0)
    ap.add_argument("--title", default="")
    ap.add_argument("--model-dir", default=str(ROOT / "models" / "higgs-onnx-int4"))
    ap.add_argument("--build", default="",
                    help="precision subdir (e.g. cpu_fp16); auto-detected if empty")
    ap.add_argument("--ref", default=str(ROOT / "refs" / "jenny.wav"))
    ap.add_argument("--ref-text", default="", help="defaults to <ref>.txt")
    ap.add_argument("--out-dir", default=str(ROOT / "output"))
    ap.add_argument("--limit", type=int, default=0, help="only synth N chunks (test)")
    ap.add_argument("--start", type=int, default=0, help="first chunk index")
    ap.add_argument("--temperature", type=float, default=0.7)
    ap.add_argument("--top-k", type=int, default=80)
    ap.add_argument("--top-p", type=float, default=0.95)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--max-seconds", type=float, default=60.0)
    ap.add_argument("--retries", type=int, default=2,
                    help="reseed and retry a chunk N times if the repeat guard trips")
    ap.add_argument("--threads", type=int, default=0, help="0 = ORT default")
    ap.add_argument("--provider", default="CPUExecutionProvider",
                    help="ORT execution provider, e.g. CUDAExecutionProvider "
                         "(needs onnxruntime-gpu and a supported GPU)")
    ap.add_argument("--title-card", action="store_true",
                    help="speak the chapter title before the text")
    ap.add_argument("--credit", action="store_true",
                    help="speak the Boson AI license credit line at the end")
    ap.add_argument("--emotion-map", default="",
                    help="JSON file with per-paragraph Higgs control tags")
    ap.add_argument("--fixes", default="",
                    help="JSON file with pronunciation/wording overrides")
    ap.add_argument("--no-verify", action="store_true",
                    help="skip the Whisper check when picking between takes")
    ap.add_argument("--max-chars", type=int, default=200,
                    help="chunk size; re-chunks the chapter before synthesising")
    ap.add_argument("--zero-shot", action="store_true",
                    help="do not clone a reference voice")
    args = ap.parse_args()

    chunks_path = ROOT / "work" / "chunks" / f"ch{args.chapter:04d}.json"
    chapter_txt = ROOT / "work" / "text" / f"ch{args.chapter:04d}.txt"

    # Re-chunk if the caller asked for a different chunk size / emotion map,
    # so the cached segments always match the current text segmentation.
    if chapter_txt.exists() and (args.emotion_map or args.max_chars or args.fixes):
        cmd = [
            sys.executable, str(ROOT / "scripts" / "prep_text.py"),
            "--chapter", str(chapter_txt),
            "--out", str(chunks_path),
            "--max-chars", str(args.max_chars),
        ]
        if args.emotion_map:
            cmd += ["--emotion-map", args.emotion_map]
        if args.fixes:
            cmd += ["--fixes", args.fixes]
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL)

    if not chunks_path.exists():
        print(f"missing {chunks_path}; run prep_text.py first", file=sys.stderr)
        return 1
    chunks = json.loads(chunks_path.read_text(encoding="utf-8"))
    seg_root = ROOT / "work" / "audio" / f"ch{args.chapter:04d}"
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    print(f"loading model from {args.model_dir} ...", flush=True)
    t0 = time.time()
    engine = HiggsTTS(args.model_dir, intra_threads=args.threads or None,
                       provider=args.provider, build=args.build or None)
    print(f"  loaded in {time.time() - t0:.1f}s "
          f"(build={engine.sub.name}, provider={engine.provider}, "
          f"dtype={engine.llm_dtype})", flush=True)

    # The cache is keyed by voice + sampling params + chunking, so switching
    # voices, tuning sampling or re-chunking never reuses stale audio. The
    # precision build is only added to the key when it is not the default, so
    # existing caches stay valid.
    voice_key = "zeroshot" if args.zero_shot else Path(args.ref).stem
    param_key = f"t{args.temperature}_k{args.top_k}_p{args.top_p}_s{args.seed}"
    chunk_key = f"c{args.max_chars}"
    if args.emotion_map:
        chunk_key += "_" + Path(args.emotion_map).stem
    name = f"{voice_key}__{param_key}__{chunk_key}"
    if engine.sub.name != "cpu_int4":
        name += "__" + engine.sub.name
    seg_dir = seg_root / name
    seg_dir.mkdir(parents=True, exist_ok=True)

    scorer = None
    if not args.no_verify:
        print("loading Whisper quality scorer ...", flush=True)
        t0 = time.time()
        scorer = QualityScorer()
        print(f"  {'ready' if scorer.available else 'UNAVAILABLE (duration checks only)'}"
              f" in {time.time() - t0:.1f}s", flush=True)

    ref_codes = None
    ref_text = None
    if not args.zero_shot:
        ref_wav = Path(args.ref)
        ref_txt = (
            Path(args.ref_text) if args.ref_text
            else ref_wav.with_suffix(".txt")
        )
        if not ref_wav.exists():
            print(f"reference clip not found: {ref_wav}", file=sys.stderr)
            return 1
        ref_text = ref_txt.read_text(encoding="utf-8").strip() if ref_txt.exists() else None
        ref_codes = ref_codes_cached(
            engine, ref_wav, ROOT / "work" / "refcodes" / f"{ref_wav.stem}.npy"
        )

    # Title card chunk.
    title_seg = None
    if args.title_card and args.title:
        title_seg = seg_dir / "title.wav"
        if not title_seg.exists():
            print(f"[title] {args.title}", flush=True)
            res, _ = render_chunk(engine, args.title, ref_codes, ref_text, args, scorer)
            write_wav(title_seg, res.wav)
            print(f"  -> {res.seconds:.1f}s (rtf {res.rtf:.1f}x) {res.notes}", flush=True)

    # Spoken license credit (required by the Higgs Creator Use Grant).
    credit_seg = None
    if args.credit:
        credit_seg = seg_dir / "credit.wav"
        if not credit_seg.exists():
            print("[credit] Boson AI attribution", flush=True)
            res, _ = render_chunk(engine, CREDIT_LINE, ref_codes, ref_text, args, scorer)
            write_wav(credit_seg, res.wav)
            print(f"  -> {res.seconds:.1f}s (rtf {res.rtf:.1f}x) {res.notes}", flush=True)

    sel = [c for c in chunks if not c.get("scene_break")]
    if args.start:
        sel = sel[args.start:]
    if args.limit:
        sel = sel[: args.limit]

    t_start = time.time()
    done = 0
    audio_done = 0.0
    meta: list[dict] = []
    for n, c in enumerate(sel):
        seg_path = seg_dir / f"seg_{c['i']:04d}.wav"
        if seg_path.exists():
            import soundfile as sf

            info = sf.info(str(seg_path))
            audio_done += info.frames / info.samplerate
            done += 1
            continue

        t1 = time.time()
        res, attempts = render_chunk(
            engine, c["text"], ref_codes, ref_text, args, scorer
        )
        write_wav(seg_path, res.wav)
        meta.append({"i": c["i"], "seconds": res.seconds, "stop": res.stop,
                     "text": c["text"], "attempts": attempts})
        audio_done += res.seconds
        done += 1
        rate = done / max(time.time() - t_start, 1e-6)
        print(
            f"[{c['i']:4d}] {res.seconds:6.1f}s audio  {time.time() - t1:6.1f}s "
            f"rtf {res.rtf:5.1f}x  stop={res.stop:6s} "
            f"| {c['text'][:58]}",
            flush=True,
        )
        if res.notes:
            for nte in res.notes:
                print(f"        note: {nte}", flush=True)

    # ---------------- assemble ---------------- #
    print("assembling ...", flush=True)
    pieces: list[np.ndarray] = []
    if title_seg is not None and title_seg.exists():
        pieces.append(silence(LEAD_IN))
        pieces.append(load_wav_mono24k(title_seg))
        pieces.append(silence(GAP_PARA))
    else:
        pieces.append(silence(LEAD_IN))

    prev_para = None
    for c in chunks:
        if c.get("scene_break"):
            pieces.append(silence(GAP_SCENE))
            prev_para = None
            continue
        if args.start and c["i"] < args.start:
            continue
        if args.limit and c["i"] >= args.start + args.limit:
            break
        seg_path = seg_dir / f"seg_{c['i']:04d}.wav"
        if not seg_path.exists():
            continue
        if prev_para is not None and c["para"] != prev_para:
            pieces.append(silence(GAP_PARA))
        elif prev_para is not None:
            pieces.append(silence(GAP_CHUNK))
        pieces.append(load_wav_mono24k(seg_path))
        prev_para = c["para"]

    pieces.append(silence(LEAD_OUT))
    if credit_seg is not None and credit_seg.exists():
        pieces.append(load_wav_mono24k(credit_seg))
        pieces.append(silence(LEAD_OUT))
    full = np.concatenate(pieces) if pieces else np.zeros(0, np.float32)

    wav_path = out_dir / f"ch{args.chapter:04d}.wav"
    write_wav(wav_path, full)

    mp3_path = out_dir / f"ch{args.chapter:04d}.mp3"
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav_path),
         "-c:a", "libmp3lame", "-b:a", "96k", "-ar", str(SR), "-ac", "1",
         str(mp3_path)],
        check=True,
    )

    dur = len(full) / SR
    (seg_dir / "render_meta.json").write_text(
        json.dumps(
            {
                "chapter": args.chapter,
                "voice": "zeroshot" if args.zero_shot else Path(args.ref).stem,
                "params": {
                    "temperature": args.temperature,
                    "top_k": args.top_k,
                    "top_p": args.top_p,
                    "seed": args.seed,
                    "max_chars": args.max_chars,
                },
                "segments": meta,
            },
            indent=2,
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    print(
        f"\ndone: {dur / 60:.1f} min  ({len(full):,} samples @ {SR} Hz)\n"
        f"  wav: {wav_path}\n  mp3: {mp3_path}",
        flush=True,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
