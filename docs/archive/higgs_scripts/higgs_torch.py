"""PyTorch backend for Higgs Audio v3 TTS (CUDA / GPU friendly).

Mirrors the interface of ``higgs_engine.py`` (the INT4 ONNX CPU backend) so
``synthesize.py`` can drive either one.

Uses the transformers ``trust_remote_code`` port of the original checkpoint:
  https://huggingface.co/multimodalart/higgs-audio-v3-tts-4b-transformers

* LM backbone  -> the dtype you load it in (fp16 on a T4, bf16 on Ampere+)
* neural codec -> always fp32 (decode is unstable in fp16/bf16)

Weights: 9.3 GB for the LM + 0.8 GB for the codec.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

REPO = "multimodalart/higgs-audio-v3-tts-4b-transformers"
CODEC_REPO = "bosonai/higgs-audio-v2-tokenizer"
SR = 24000
FPS = 25


@dataclass
class SynthResult:
    wav: np.ndarray
    stop: str
    seconds: float
    frames: int
    elapsed: float
    rtf: float
    notes: list[str] = field(default_factory=list)


class HiggsTorchTTS:
    """Higgs Audio v3 TTS through the transformers port (GPU)."""

    def __init__(
        self,
        repo: str = REPO,
        *,
        device: str = "cuda",
        dtype: str = "float16",
        local_files_only: bool = False,
    ) -> None:
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer

        self.torch = torch
        self.device = device
        self.dtype = getattr(torch, dtype)
        self.repo = repo

        self.tok = AutoTokenizer.from_pretrained(
            repo, local_files_only=local_files_only
        )
        # device_map streams the weights straight onto the GPU, which keeps
        # host RAM down (Colab gives ~13 GB of it for a 9.3 GB checkpoint).
        self.model = AutoModelForCausalLM.from_pretrained(
            repo,
            trust_remote_code=True,
            dtype=self.dtype,
            device_map={"": 0} if device.startswith("cuda") else None,
            low_cpu_mem_usage=True,
            local_files_only=local_files_only,
        )
        if not device.startswith("cuda"):
            self.model = self.model.to(device)
        self.model.eval()
        self.sample_rate = int(getattr(self.model.config, "sample_rate", SR))

    # ------------------------------------------------------------------ #
    def synthesize(
        self,
        text: str,
        *,
        ref_audio: np.ndarray | None = None,
        ref_text: str | None = None,
        ref_codes=None,                 # accepted for interface parity
        max_seconds: float = 60.0,
        temperature: float = 0.7,
        top_k: int = 80,
        top_p: float = 0.95,
        seed: int = 0,
        max_repeat: int = 0,            # unused: the port has no repeat guard
        progress=None,
    ) -> SynthResult:
        torch = self.torch
        t0 = time.time()
        torch.manual_seed(seed)

        kwargs = {}
        if ref_audio is not None:
            ref = torch.as_tensor(np.asarray(ref_audio, dtype=np.float32))
            kwargs["reference_audio"] = ref
            kwargs["reference_sample_rate"] = self.sample_rate
            if ref_text:
                kwargs["reference_text"] = ref_text

        wav = self.model.generate_speech(
            text,
            self.tok,
            max_new_tokens=int(max_seconds * FPS),
            temperature=temperature,
            top_k=top_k,
            top_p=top_p,
            **kwargs,
        )
        arr = wav.detach().float().cpu().numpy().reshape(-1)
        elapsed = time.time() - t0
        seconds = len(arr) / self.sample_rate

        notes: list[str] = []
        stop = "eoc"
        if seconds >= max_seconds * 0.98:
            stop = "cap"
            notes.append(f"hit max_seconds={max_seconds:.0f} — possibly truncated")
        return SynthResult(
            wav=arr,
            stop=stop,
            seconds=seconds,
            frames=int(seconds * FPS),
            elapsed=elapsed,
            rtf=(elapsed / seconds) if seconds > 0 else 0.0,
            notes=notes,
        )


def load_wav_mono24k(path: str | Path) -> np.ndarray:
    """Read any audio file as mono float32 at 24 kHz."""
    import subprocess
    import tempfile

    import soundfile as sf

    path = Path(path)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        tmp_path = Path(tmp.name)
    try:
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-i", str(path),
             "-ac", "1", "-ar", str(SR), "-c:a", "pcm_s16le", str(tmp_path)],
            check=True,
        )
        wav, _ = sf.read(str(tmp_path), dtype="float32")
    finally:
        tmp_path.unlink(missing_ok=True)
    return np.ascontiguousarray(wav)
