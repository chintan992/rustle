"""Benchmark alternative TTS models against Higgs on this machine.

Runs the *same* Chapter 1 chunks through each candidate backend and reports:

  * RTF  - wall-clock seconds per second of generated audio (< 1 is faster
           than real time)
  * WER  - word error rate of a Whisper transcription against the source text
  * coverage - how much of the source text was actually spoken

Backends
  higgs      the ONNX INT4 CPU engine already in this project
  omnivoice  onnx-community/OmniVoice-Onnx  (int4 / fp16 CPU builds)
  kokoro     onnx-community/Kokoro-82M-v1.0-ONNX
  qwen3tts   Qwen/Qwen3-TTS-12Hz-0.6B-Base (torch, CPU)

Usage:
    python scripts\\bench_alternatives.py --backends omnivoice kokoro --chunks 5
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify import get_model, load_16k, normalize, wer  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
SR = 24000


def setup_espeak() -> None:
    """Point `phonemizer` at the espeak-ng build shipped by espeakng-loader.

    Without this, the KittenTTS backend fails with
    "espeak not installed on your system" on Windows.
    """
    import os

    try:
        import espeakng_loader
    except ImportError:
        return
    lib = espeakng_loader.get_library_path()
    data = espeakng_loader.get_data_path()
    os.environ.setdefault("PHONEMIZER_ESPEAK_LIBRARY", lib)
    os.environ.setdefault("PHONEMIZER_ESPEAK_DATA_PATH", data)

# A short passage (3 sentences, ~60 words, ~25 s of speech) taken from the
# opening of Chapter 1. Short enough that every backend finishes quickly,
# long enough that RTF and WER are meaningful.
BENCH_TEXT = (
    "A frail-looking young man with pale skin and dark circles under his eyes "
    "was sitting on a rusty bench across from the police station. He was "
    "cradling a cup of coffee in his hands, not the cheap synthetic type slum "
    "rats like him had access to, but the real deal. This cup of plant-based "
    "coffee, usually available only to higher rank citizens, had cost most of "
    "his savings."
)


# --------------------------------------------------------------------------- #
# helpers
# --------------------------------------------------------------------------- #
def chapter_chunks(n: int) -> list[dict]:
    chunks = json.loads(
        (ROOT / "work" / "chunks" / "ch0000.json").read_text(encoding="utf-8")
    )
    out = [c for c in chunks if not c.get("scene_break")]
    return out[:n]

def score(wav: np.ndarray, text: str, model) -> dict:
    import tempfile

    import soundfile as sf

    wav = np.asarray(wav, dtype=np.float32).reshape(-1)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as t:
        p = Path(t.name)
    try:
        sf.write(str(p), wav, SR, subtype="PCM_16")
        segs, _ = model.transcribe(
            load_16k(p), beam_size=5, language="en", vad_filter=False
        )
        hyp = " ".join(s.text.strip() for s in segs)
    finally:
        p.unlink(missing_ok=True)
    ref_w, hyp_w = normalize(text), normalize(hyp)
    if not ref_w:
        return {}
    return {"wer": wer(ref_w, hyp_w), "coverage": len(hyp_w) / len(ref_w), "hyp": hyp}


def strip_tags(text: str) -> str:
    import re

    return re.sub(r"<\|[^|]*\|>", " ", text)


# --------------------------------------------------------------------------- #
# backends
# --------------------------------------------------------------------------- #
class HiggsBackend:
    name = "higgs (int4 onnx, cpu)"

    def __init__(self, args):
        from higgs_engine import HiggsTTS

        self.args = args
        self.tts = HiggsTTS(args.model_dir)
        self.ref = None
        if not args.no_ref:
            from higgs_engine import load_wav_mono24k

            wav = load_wav_mono24k(args.ref)
            self.ref = self.tts.encode_audio(wav)
        self.ref_text = (
            Path(args.ref).with_suffix(".txt").read_text(encoding="utf-8").strip()
        )

    def run(self, text: str):
        a = self.args
        res = self.tts.synthesize(
            text,
            ref_codes=self.ref,
            ref_text=self.ref_text,
            max_seconds=90,
            temperature=a.temperature,
            top_k=a.top_k,
            top_p=a.top_p,
            seed=a.seed,
        )
        return res.wav, res.seconds


class OmniVoiceBackend:
    """onnx-community/OmniVoice-Onnx — 32-step iterative unmasking."""

    def __init__(self, args):
        import sys as _sys

        _sys.path.insert(0, str(Path(args.omnivoice_dir)))
        import inference as ov  # type: ignore

        self.args = args
        self.ov = ov
        self.sessions = ov.load_sessions(args.omnivoice_dir, "CPUExecutionProvider")
        self.higgs = ov.load_higgs_sessions(args.omnivoice_higgs_dir,
                                            "CPUExecutionProvider")
        from transformers import AutoTokenizer

        self.tok = AutoTokenizer.from_pretrained(args.omnivoice_dir)
        self.prefix = None
        if not args.no_ref:
            self.prefix = ov.higgs_encode(self.higgs, args.ref)

    def run(self, text: str):
        a = self.args
        tokens = self.tok.encode(text, add_special_tokens=True)
        codes = self.ov.iterative_unmask(
            self.sessions,
            tokens,
            num_audio_tokens=a.omnivoice_tokens,
            num_steps=a.omnivoice_steps,
            prefix_codes=self.prefix,
        )
        wav = self.ov.higgs_decode(self.higgs, codes[:, None, :])
        return wav.astype(np.float32), len(wav) / SR


class KokoroBackend:
    """onnx-community/Kokoro-82M-v1.0-ONNX — single-pass, no voice cloning."""

    name = "kokoro-82m (onnx, cpu)"

    def __init__(self, args):
        from kokoro_onnx import Kokoro

        self.args = args
        self.k = Kokoro(
            str(Path(args.kokoro_dir) / "onnx" / "model.onnx"),
            str(Path(args.kokoro_dir) / "voices" / "voices.npz"),
        )
        # The onnx-community v1.0 export expects a rank-2 "style" input
        # ([B, 256]) and a float "speed"; kokoro_onnx passes a rank-1 style
        # and an int32 speed. Patch the session to match the graph.
        orig_run = self.k.sess.run

        def run_fixed(output_names, input_feed, run_options=None):
            if input_feed:
                feeds = dict(input_feed)
                if "style" in feeds:
                    st = np.asarray(feeds["style"], np.float32)
                    if st.ndim == 1:
                        feeds["style"] = st[None, :]
                if "speed" in feeds:
                    feeds["speed"] = np.asarray(feeds["speed"], np.float32)
                input_feed = feeds
            return orig_run(output_names, input_feed, run_options)

        self.k.sess.run = run_fixed
        self.voice = args.kokoro_voice

    def run(self, text: str):
        wav, _ = self.k.create(
            text, voice=self.voice, speed=1.0, lang="en-us"
        )
        # Kokoro returns (1, N); soundfile would read a 2-D array as N channels.
        wav = np.asarray(wav, dtype=np.float32).reshape(-1)
        return wav, len(wav) / SR


class KittenBackend:
    """KittenTTS 0.8 (nano/mini) — Apache-2.0, single-file ONNX, non-AR."""

    name = "kitten-tts-0.8 (onnx, cpu)"

    def __init__(self, args):
        from kittentts import KittenTTS

        self.args = args
        root = Path(args.kitten_dir)
        cfg = json.loads((root / "config.json").read_text(encoding="utf-8"))
        model_file = root / cfg.get("model_file", "kitten_tts_nano_v0_8.onnx")
        voices_file = root / cfg.get("voices", "voices.npz")
        self.k = KittenTTS(str(model_file), str(voices_file))
        self.voice = args.kitten_voice or self._pick_female(voices_file)
        print(f"  model: {model_file.name}", flush=True)
        print(f"  voice: {self.voice}", flush=True)

    @staticmethod
    def _pick_female(voices_file: Path) -> str:
        """Prefer a female preset voice."""
        import numpy as np

        try:
            keys = list(np.load(voices_file, allow_pickle=True).files)
        except Exception:
            return "expr-voice-2-f"
        female = [k for k in keys if k.endswith("-f")]
        return female[0] if female else (keys[0] if keys else "expr-voice-2-f")

    def run(self, text: str):
        wav = self.k.generate(text, voice=self.voice, speed=1.0)
        wav = np.asarray(wav, dtype=np.float32).reshape(-1)
        return wav, len(wav) / SR


# --------------------------------------------------------------------------- #
def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--backends", nargs="+", default=["higgs"])
    ap.add_argument("--text", default="",
                    help="text to synthesise (default: a 3-sentence passage "
                         "from Chapter 1)")
    ap.add_argument("--chunks", type=int, default=0,
                    help="use the first N chapter chunks instead of --text")
    ap.add_argument("--model-dir", default=str(ROOT / "models" / "higgs-onnx-int4"))
    ap.add_argument("--ref", default=str(ROOT / "refs" / "jenny.wav"))
    ap.add_argument("--no-ref", action="store_true")
    ap.add_argument("--omnivoice-dir",
                    default=str(ROOT / "models" / "omnivoice-onnx" / "int4"))
    ap.add_argument("--omnivoice-higgs-dir",
                    default=str(ROOT / "models" / "omnivoice-onnx" / "audio_tokenizer"))
    ap.add_argument("--omnivoice-tokens", type=int, default=400)
    ap.add_argument("--omnivoice-steps", type=int, default=32)
    ap.add_argument("--kokoro-dir", default=str(ROOT / "models" / "kokoro"))
    ap.add_argument("--kokoro-voice", default="af_heart")
    ap.add_argument("--kitten-dir",
                    default=str(ROOT / "models" / "kitten-nano-0.1"),
                    help="KittenTTS model dir. NOTE: the 0.8 weights need the "
                         "kittentts 0.8 wheel, which requires Python <= 3.12; "
                         "the 0.1 weights work with the published package on "
                         "Python 3.14.")
    ap.add_argument("--kitten-voice", default="")
    ap.add_argument("--temperature", type=float, default=0.7)
    ap.add_argument("--top-k", type=int, default=80)
    ap.add_argument("--top-p", type=float, default=0.95)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--out", default=str(ROOT / "work" / "bench.json"))
    args = ap.parse_args()
    setup_espeak()

    if args.chunks:
        chunks = [
            {"i": c["i"], "text": c["text"]} for c in chapter_chunks(args.chunks)
        ]
    else:
        chunks = [{"i": 0, "text": args.text or BENCH_TEXT}]
    model = get_model("small.en")

    backends = {
        "higgs": HiggsBackend,
        "omnivoice": OmniVoiceBackend,
        "kokoro": KokoroBackend,
        "kitten": KittenBackend,
    }

    results = []
    for name in args.backends:
        cls = backends.get(name)
        if cls is None:
            print(f"unknown backend {name}")
            continue
        print(f"\n=== {name} ===", flush=True)
        try:
            t0 = time.time()
            backend = cls(args)
            print(f"  loaded in {time.time() - t0:.1f}s", flush=True)
        except Exception as exc:  # noqa: BLE001
            print(f"  LOAD FAILED: {type(exc).__name__}: {exc}")
            continue

        for c in chunks:
            text = c["text"]
            try:
                t0 = time.time()
                wav, audio_s = backend.run(text)
                wall = time.time() - t0
            except Exception as exc:  # noqa: BLE001
                print(f"  [{c['i']:4d}] FAILED: {type(exc).__name__}: {exc}")
                continue
            sc = score(wav, strip_tags(text), model) if len(wav) else {}
            rtf = wall / audio_s if audio_s else 0.0
            print(
                f"  [{c['i']:4d}] audio {audio_s:6.1f}s  wall {wall:6.1f}s  "
                f"RTF {rtf:5.2f}  "
                f"WER {sc.get('wer', float('nan')) * 100:5.1f}%  "
                f"cov {sc.get('coverage', 0) * 100:5.1f}%",
                flush=True,
            )
            results.append(
                {
                    "backend": name,
                    "chunk": c["i"],
                    "audio_s": audio_s,
                    "wall_s": wall,
                    "rtf": rtf,
                    "wer": sc.get("wer"),
                    "coverage": sc.get("coverage"),
                }
            )

    print("\n=== summary ===")
    for name in dict.fromkeys(r["backend"] for r in results):
        rs = [r for r in results if r["backend"] == name]
        rtf = sum(r["rtf"] for r in rs) / len(rs)
        wers = [r["wer"] for r in rs if r["wer"] is not None]
        covs = [r["coverage"] for r in rs if r["coverage"] is not None]
        mean_wer = sum(wers) / len(wers) * 100 if wers else float("nan")
        mean_cov = sum(covs) / len(covs) * 100 if covs else float("nan")
        speed = "FASTER than real-time" if rtf < 1 else f"{rtf:.1f}x slower than real-time"
        print(
            f"  {name:12s} RTF {rtf:5.2f}  ({speed})  "
            f"WER {mean_wer:5.1f}%  coverage {mean_cov:5.1f}%"
        )

    Path(args.out).write_text(
        json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    print(f"\n-> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
