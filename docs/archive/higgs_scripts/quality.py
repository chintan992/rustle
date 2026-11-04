"""Quality scoring for generated speech, used to pick the best take of a chunk.

The INT4 CPU build is stochastic: the same text rendered with different seeds
can come out perfect or degenerate (the model restarting the utterance in a
loop), and a *duration* check cannot tell those apart — a loop can be shorter
or longer than the text warrants.

So we score takes directly: transcribe with Whisper and compare against the
source text (word error rate + coverage).

The scorer is optional; if faster-whisper / the model is unavailable it
degrades to "no opinion" and the caller falls back to duration heuristics.
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify import get_model, load_16k, normalize, wer  # noqa: E402

_ACCEPT_WER = 0.15      # a take at or below this is considered good
_ACCEPT_COVERAGE = 0.90


class QualityScorer:
    """Transcribe-and-score helper (Whisper, CPU, int8)."""

    def __init__(self, model_size: str = "small.en") -> None:
        self.model_size = model_size
        self._model = None
        self.available = True
        try:
            get_model(model_size)
        except Exception:  # noqa: BLE001
            self.available = False

    def _transcribe(self, wav: np.ndarray, sr: int) -> str:
        if self._model is None:
            self._model = get_model(self.model_size)
        import tempfile

        import soundfile as sf

        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
            tmp_path = Path(tmp.name)
        try:
            sf.write(str(tmp_path), wav, sr, subtype="PCM_16")
            audio = load_16k(tmp_path)
        finally:
            tmp_path.unlink(missing_ok=True)
        segments, _ = self._model.transcribe(
            np.ascontiguousarray(audio), beam_size=5, language="en", vad_filter=False
        )
        return " ".join(s.text.strip() for s in segments)

    def score(self, wav: np.ndarray, sr: int, text: str) -> dict:
        """Return {'wer': float, 'coverage': float, 'text': str} or {}."""
        if not self.available or wav.size == 0:
            return {}
        try:
            hyp = self._transcribe(wav, sr)
        except Exception:  # noqa: BLE001
            return {}
        ref_w, hyp_w = normalize(text), normalize(hyp)
        if not ref_w:
            return {}
        e = wer(ref_w, hyp_w)
        cov = len(hyp_w) / len(ref_w)
        return {"wer": e, "coverage": cov, "text": hyp}

    @staticmethod
    def is_good(score: dict) -> bool:
        if not score:
            return False
        return score["wer"] <= _ACCEPT_WER and score["coverage"] >= _ACCEPT_COVERAGE
