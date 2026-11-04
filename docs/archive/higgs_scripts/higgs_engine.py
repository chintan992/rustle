"""Local Higgs Audio v3 TTS engine (INT4 ONNX, CPU execution provider).

Self-contained re-implementation of the generation pipeline described in
https://huggingface.co/onnx-community/higgs-audio-v3-tts-4b (STATUS.md /
inference.py), adapted for this project:

  * flat on-disk layout (files fetched as `<sub>__<name>`),
  * voice cloning from a local reference clip + verbatim transcript,
  * Higgs inline control tags (<|emotion:...|>, <|prosody:...|>, <|style:...|>,
    <|sfx:...|>) which the tokenizer already knows as special tokens,
  * resumable, chunked synthesis.

Model anatomy
-------------
  llm_decoder.onnx   Qwen3-4B decoder, inputs_embeds -> hidden_states
  text_embed.onnx   input_ids -> inputs_embeds   (token-embedding Gather)
  audio_embed.onnx  codes[B,L,8] -> embeds[B,L,2560]  (fused multi-codebook)
  audio_heads.onnx  hidden[B,L,2560] -> logits[B,L,8,1026]
  audio_encoder.onnx  waveform -> codes   (needed for voice cloning)
  audio_tokenizer.onnx codes -> 24 kHz waveform (neural codec decode)

Generation follows Higgs' delay pattern: codebook c is shifted by c steps,
padded with BOC (1024) before and EOC (1025) after its data span.
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

SR = 24000
FPS = 25
HOP = SR // FPS  # 960 samples per audio frame
N_CODEBOOKS = 8
CB_VOCAB = 1026
BOC_ID = 1024
EOC_ID = 1025
HEAD_DIM = 128


# --------------------------------------------------------------------------- #
# delay pattern
# --------------------------------------------------------------------------- #
def apply_delay_pattern(codes_TN: np.ndarray) -> np.ndarray:
    """[T, N] raw codes -> [T+N-1, N] delayed, BOC/EOC padded."""
    T, N = codes_TN.shape
    out = np.full((T + N - 1, N), EOC_ID, dtype=np.int64)
    for c in range(N):
        out[c : c + T, c] = codes_TN[:, c]
        out[:c, c] = BOC_ID
    return out


def reverse_delay_pattern(delayed_LN: np.ndarray) -> np.ndarray:
    """[L, N] delayed -> [L-(N-1), N] raw codes."""
    L, N = delayed_LN.shape
    T = L - (N - 1)
    if T <= 0:
        return np.zeros((0, N), dtype=np.int64)
    out = np.zeros((T, N), dtype=np.int64)
    for c in range(N):
        out[:, c] = delayed_LN[c : c + T, c]
    return out


@dataclass
class SynthResult:
    wav: np.ndarray
    stop: str            # "eoc" | "cap" | "repeat"
    seconds: float
    frames: int
    elapsed: float
    rtf: float
    notes: list[str] = field(default_factory=list)


class HiggsTTS:
    """Higgs Audio v3 TTS, CPU/ONNX INT4."""

    def __init__(
        self,
        model_dir: str | Path,
        *,
        provider: str = "CPUExecutionProvider",
        intra_threads: int | None = None,
        inter_threads: int = 1,
        build: str | None = None,
    ) -> None:
        import onnxruntime as ort
        from transformers import AutoTokenizer

        self.root = Path(model_dir)
        if not self.root.exists():
            raise FileNotFoundError(self.root)
        # ONNX external-data files must sit next to the graph under their
        # original names, so the sub-parts live in <root>/<build>/.
        self.sub = self._find_build(build)

        so = ort.SessionOptions()
        so.intra_op_num_threads = intra_threads or 0  # 0 = let ORT decide
        so.inter_op_num_threads = inter_threads
        so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        so.log_severity_level = 3

        providers = [provider]
        self.provider = provider

        def sess(name: str):
            path = self.sub / name
            if not path.exists():
                return None
            return ort.InferenceSession(str(path), so, providers=providers)

        self.llm = sess("llm_decoder.onnx")
        self.text_embed = sess("text_embed.onnx")
        self.audio_embed = sess("audio_embed.onnx")
        self.audio_heads = sess("audio_heads.onnx")
        self.codec = sess("audio_tokenizer.onnx")
        self.audio_encoder = sess("audio_encoder.onnx")

        if self.llm is None:
            raise FileNotFoundError(f"llm_decoder.onnx missing in {self.sub}")

        manifest_path = self.sub / "manifest.json"
        self.manifest = (
            json.loads(manifest_path.read_text(encoding="utf-8"))
            if manifest_path.exists()
            else {}
        )

        # LLM geometry + expected float dtype, read from the graph.
        self.n_layers = 36
        self.n_kv = 8
        sub = (self.manifest.get("sub_models") or {}).get("llm_decoder") or {}
        self.n_layers = int(sub.get("num_layers", self.n_layers))
        self.n_kv = int(sub.get("num_kv_heads", self.n_kv))
        self.llm_dtype = np.float32
        for inp in self.llm.get_inputs():
            if inp.name == "inputs_embeds":
                self.llm_dtype = np.float16 if "float16" in inp.type else np.float32
        self.llm_input_names = {i.name for i in self.llm.get_inputs()}

        # Tokenizer. The fetch layout uses flat names ("cpu_int4__tokenizer.json"),
        # so stage a small sidecar directory with the names AutoTokenizer expects.
        self.tok = AutoTokenizer.from_pretrained(
            str(self._tokenizer_dir()), local_files_only=True
        )
        self.added = dict(self.tok.get_added_vocab())

    def _find_build(self, build: str | None) -> Path:
        """Locate the directory holding llm_decoder.onnx."""
        if build:
            p = self.root / build
            if (p / "llm_decoder.onnx").exists():
                return p
            raise FileNotFoundError(f"no llm_decoder.onnx in {p}")
        if (self.root / "llm_decoder.onnx").exists():
            return self.root
        cands = [
            d for d in sorted(self.root.iterdir())
            if d.is_dir() and (d / "llm_decoder.onnx").exists()
        ]
        # Prefer an int4 build (smallest) when several are present.
        cands.sort(key=lambda d: (0 if "int4" in d.name else 1, d.name))
        if cands:
            return cands[0]
        raise FileNotFoundError(f"no llm_decoder.onnx under {self.root}")

    def _tokenizer_dir(self) -> Path:
        """Directory containing tokenizer.json / tokenizer_config.json."""
        if (self.sub / "tokenizer.json").exists():
            return self.sub
        staged = self.root / "_tokenizer"
        staged.mkdir(parents=True, exist_ok=True)
        for name in ("tokenizer.json", "tokenizer_config.json", "chat_template.jinja"):
            src = self.sub / name
            dst = staged / name
            if src.exists() and not dst.exists():
                dst.write_bytes(src.read_bytes())
        if not (staged / "tokenizer.json").exists():
            raise FileNotFoundError(f"tokenizer.json not found under {self.root}")
        return staged

    # ------------------------------------------------------------------ #
    # helpers
    # ------------------------------------------------------------------ #
    def _run(self, sess, feeds):
        """Run a session, casting float feeds to the dtype the graph expects."""
        want = {i.name: i.type for i in sess.get_inputs()}
        cast = {}
        for k, v in feeds.items():
            t = want.get(k, "")
            if "float16" in t:
                cast[k] = np.asarray(v, np.float16)
            elif "float" in t:
                cast[k] = np.asarray(v, np.float32)
            else:
                cast[k] = v
        return sess.run(None, cast)

    def _empty_past(self):
        z = np.zeros((1, self.n_kv, 0, HEAD_DIM), dtype=self.llm_dtype)
        return {
            f"past_key_values.{i}.{kv}": z
            for i in range(self.n_layers)
            for kv in ("key", "value")
        }

    def _llm_step(self, inputs_embeds, attn_len, past):
        feeds = {
            "inputs_embeds": inputs_embeds.astype(self.llm_dtype),
            "attention_mask": np.ones((1, attn_len), dtype=np.int64),
            **past,
        }
        outs = self.llm.run(None, feeds)
        d = dict(zip([o.name for o in self.llm.get_outputs()], outs))
        hidden = np.asarray(d["hidden_states"], np.float32)
        new_past = {
            f"past_key_values.{i}.{kv}": d[f"present.{i}.{kv}"]
            for i in range(self.n_layers)
            for kv in ("key", "value")
        }
        return hidden, new_past

    def embed_ids(self, input_ids: np.ndarray) -> np.ndarray:
        if self.text_embed is None:
            raise RuntimeError("text_embed.onnx missing")
        return self.text_embed.run(
            None, {"input_ids": np.asarray(input_ids, np.int64)}
        )[0]

    # ------------------------------------------------------------------ #
    # codec
    # ------------------------------------------------------------------ #
    def decode_codes(self, codes_TN: np.ndarray) -> np.ndarray:
        codes_BNT = codes_TN.T[None].astype(np.int64)
        wav = self.codec.run(None, {"audio_codes": codes_BNT})[0]
        return wav[0, 0]

    def encode_audio(self, wav: np.ndarray) -> np.ndarray:
        """mono 24 kHz waveform -> [frames, 8] codes."""
        x = np.asarray(wav, np.float32).reshape(-1)
        # The traced encoder folded the acoustic/semantic length-alignment branch,
        # so the input must be a whole number of codec frames (hop = 960).
        pad_to = max(HOP, ((x.shape[0] + HOP - 1) // HOP) * HOP)
        if pad_to != x.shape[0]:
            x = np.pad(x, (0, pad_to - x.shape[0]))
        codes = self._run(
            self.audio_encoder, {"input_values": x.reshape(1, 1, -1)}
        )[0]
        return codes[0].T.astype(np.int64)

    # ------------------------------------------------------------------ #
    # prompt assembly
    # ------------------------------------------------------------------ #
    def _tts_prompt_ids(self, text: str) -> np.ndarray:
        av = self.added
        tts_id, text_id, audio_id = av["<|tts|>"], av["<|text|>"], av["<|audio|>"]
        body = self.tok.encode(text, add_special_tokens=False)
        return np.array([[tts_id, text_id, *body, audio_id]], dtype=np.int64)

    def _seg(self, ids):
        return self.embed_ids(np.asarray([ids], np.int64))

    def build_prefill(
        self, text: str, ref_codes: np.ndarray | None = None,
        ref_text: str | None = None,
    ) -> np.ndarray:
        av = self.added
        if ref_codes is None:
            return self.embed_ids(self._tts_prompt_ids(text))

        if self.audio_embed is None:
            raise RuntimeError("audio_embed.onnx missing (needed for voice cloning)")
        delayed = apply_delay_pattern(ref_codes)
        ref_embeds = self._run(
            self.audio_embed, {"codes": delayed[None].astype(np.int64)}
        )[0]
        ref_text_toks = (
            self.tok.encode(ref_text, add_special_tokens=False) if ref_text else []
        )
        body = self.tok.encode(text, add_special_tokens=False)
        return np.concatenate(
            [
                self._seg([av["<|tts|>"]]),
                self._seg([av["<|ref_text|>"], *ref_text_toks]),
                self._seg([av["<|ref_audio|>"]]),
                ref_embeds.astype(np.float32),
                self._seg([av["<|text|>"], *body]).astype(np.float32),
                self._seg([av["<|audio|>"]]).astype(np.float32),
            ],
            axis=1,
        )

    # ------------------------------------------------------------------ #
    # sampling
    # ------------------------------------------------------------------ #
    @staticmethod
    def _sample(logits_NV: np.ndarray, temperature: float, top_k: int,
                top_p: float, rng) -> np.ndarray:
        N, V = logits_NV.shape
        if not np.isfinite(logits_NV).all():
            raise RuntimeError("audio_heads produced non-finite logits")
        if temperature <= 0:
            return logits_NV.argmax(-1).astype(np.int64)
        logits = logits_NV.astype(np.float64) / temperature
        if 0 < top_k < V:
            kth = np.partition(logits, V - top_k, axis=-1)[:, V - top_k][:, None]
            logits = np.where(logits < kth, -np.inf, logits)
        logits -= logits.max(-1, keepdims=True)
        probs = np.exp(logits)
        probs /= probs.sum(-1, keepdims=True)
        if 0 < top_p < 1.0:
            order = np.argsort(probs, axis=-1)[:, ::-1]
            sorted_p = np.take_along_axis(probs, order, axis=-1)
            csum = np.cumsum(sorted_p, axis=-1)
            keep = csum - sorted_p < top_p
            sorted_p = np.where(keep, sorted_p, 0.0)
            probs = np.zeros_like(probs)
            np.put_along_axis(probs, order, sorted_p, axis=-1)
            probs /= probs.sum(-1, keepdims=True)
        return np.array([rng.choice(V, p=probs[i]) for i in range(N)], dtype=np.int64)

    # ------------------------------------------------------------------ #
    # main entry point
    # ------------------------------------------------------------------ #
    def synthesize(
        self,
        text: str,
        *,
        ref_codes: np.ndarray | None = None,
        ref_text: str | None = None,
        max_seconds: float = 120.0,
        temperature: float = 0.8,
        top_k: int = 50,
        top_p: float = 1.0,
        seed: int = 0,
        max_repeat: int = 32,
        progress=None,
    ) -> SynthResult:
        t0 = time.time()
        notes: list[str] = []
        prefill = self.build_prefill(text, ref_codes, ref_text)
        max_frames = int(max_seconds * FPS)

        rng = np.random.default_rng(seed)
        past = self._empty_past()
        hidden, past = self._llm_step(prefill, prefill.shape[1], past)

        if float(np.abs(hidden).mean()) < 1e-8:
            raise RuntimeError(
                "llm_decoder produced all-zero hidden states — the INT4 kernels "
                "are not computing on this runtime. Try the CPU provider."
            )

        delayed: list[np.ndarray] = []
        delay_count = 0
        eoc_countdown = None
        last_cb0 = None
        repeat = 0
        stop = "cap"
        total = prefill.shape[1]

        for _ in range(max_frames):
            logits = self._run(
                self.audio_heads, {"hidden_states": hidden[:, -1:, :]}
            )[0]
            codes = self._sample(logits[0, 0], temperature, top_k, top_p, rng)

            if codes[0] == last_cb0:
                repeat += 1
                if repeat >= max_repeat:
                    stop = "repeat"
                    break
            else:
                repeat = 0
            last_cb0 = int(codes[0])

            if delay_count < N_CODEBOOKS:
                nxt = delay_count + 1
                if nxt < N_CODEBOOKS:
                    codes[nxt:] = BOC_ID
                delay_count += 1
            elif eoc_countdown is not None:
                eoc_countdown -= 1
                if eoc_countdown <= 0:
                    delayed.append(codes)
                    stop = "eoc"
                    break
            elif int(codes[0]) == EOC_ID:
                eoc_countdown = N_CODEBOOKS - 2

            delayed.append(codes)
            emb = self.audio_embed.run(
                None, {"codes": codes[None, None]}
            )[0]
            total += 1
            hidden, past = self._llm_step(emb, total, past)

            if progress is not None and len(delayed) % 25 == 0:
                progress(len(delayed), max_frames)

        if stop == "cap":
            notes.append(
                f"hit max_seconds={max_seconds:.0f} — output likely truncated"
            )
        elif stop == "repeat":
            notes.append(
                f"stopped by repeat guard (codebook-0 repeated {max_repeat}x)"
            )
            if len(delayed) > max_repeat:
                del delayed[-max_repeat:]

        if not delayed:
            return SynthResult(
                np.zeros(0, np.float32), stop, 0.0, 0, time.time() - t0, 0.0, notes
            )

        codes_TN = reverse_delay_pattern(np.stack(delayed))
        codes_TN = np.clip(codes_TN, 0, 1023)
        wav = self.decode_codes(codes_TN).astype(np.float32)

        elapsed = time.time() - t0
        seconds = len(wav) / SR
        return SynthResult(
            wav=wav,
            stop=stop,
            seconds=seconds,
            frames=len(delayed),
            elapsed=elapsed,
            rtf=(elapsed / seconds) if seconds > 0 else 0.0,
            notes=notes,
        )


def load_wav_mono24k(path: str | Path) -> np.ndarray:
    """Read a wav/mp3-ish file as mono float32 at 24 kHz (ffmpeg fallback)."""
    path = Path(path)
    try:
        import soundfile as sf

        wav, sr = sf.read(str(path), dtype="float32", always_2d=False)
        if wav.ndim > 1:
            wav = wav.mean(axis=1)
        if sr != SR:
            import subprocess

            tmp = path.with_suffix(".24k.wav")
            subprocess.run(
                ["ffmpeg", "-y", "-loglevel", "error", "-i", str(path),
                 "-ac", "1", "-ar", str(SR), "-c:a", "pcm_s16le", str(tmp)],
                check=True,
            )
            wav, _ = sf.read(str(tmp), dtype="float32")
            tmp.unlink(missing_ok=True)
        return wav.astype(np.float32)
    except Exception:
        import subprocess

        tmp = path.with_suffix(".24k.tmp.wav")
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-i", str(path),
             "-ac", "1", "-ar", str(SR), "-c:a", "pcm_s16le", str(tmp)],
            check=True,
        )
        import soundfile as sf

        wav, _ = sf.read(str(tmp), dtype="float32")
        tmp.unlink(missing_ok=True)
        return wav.astype(np.float32)
