"""Generate the Google Colab notebook for the Higgs TTS audiobook pipeline.

    python scripts\\make_colab_notebook.py

Writes colab/higgs_tts_audiobook.ipynb.

The notebook is self-contained (no dependency on this repo's scripts/ folder)
and targets the free Colab T4: 16 GB VRAM, sm_75 — which clears the
compute-capability floor for ONNX Runtime's int4/GQA CUDA kernels and has
native fp16.
"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "colab" / "higgs_tts_audiobook.ipynb"

MD = "markdown"
CODE = "code"


def md(text: str) -> dict:
    return {"cell_type": MD, "metadata": {}, "source": text.splitlines(keepends=True)}


def code(text: str) -> dict:
    return {
        "cell_type": CODE,
        "execution_count": None,
        "metadata": {},
        "outputs": [],
        "source": text.splitlines(keepends=True),
    }


CELLS: list[dict] = []

# ---------------------------------------------------------------- title ----
CELLS.append(md("""# Shadow Slave -> audiobook with Higgs Audio v3 TTS (Google Colab, T4)

Turns the *Shadow Slave* EPUB into a narrated audiobook using
**Boson AI's Higgs Audio v3 TTS** (`bosonai/higgs-tts-3-4b`).

**What this notebook does**

1. installs the dependencies,
2. downloads the Higgs weights (LM + neural codec),
3. builds a female reference voice and clones it with Higgs,
4. extracts the requested chapter from the EPUB you upload,
5. renders it with Higgs' own control tags for emotional direction,
6. verifies every chunk by transcribing it (Whisper) and re-rendering failures,
7. assembles a 24 kHz WAV + MP3.

**Hardware.** The free Colab **T4** has 16 GB VRAM and `sm_75`, so the
original FP16 weights fit and run at roughly real time. A P100 (16 GB) works
too; prefer the T4.

**Licence.** Higgs Audio v3 is research/non-commercial with a *Creator Use
Grant* that covers audiobooks, provided the work credits Boson AI:

> This audio was created with Boson AI's Higgs Audio — https://www.boson.ai/higgs-audio
"""))

# ------------------------------------------------------------- install ----
CELLS.append(md("## 1. Install\n\n`transformers >= 5.5` is required by the model port.\n\n`torchaudio` has to be the **same version** as Colab's prebuilt torch, so it is\ninstalled with `--no-deps` (otherwise pip would replace torch with a CPU\nbuild)."))
CELLS.append(code("""!pip install -q -U "transformers>=5.5" "huggingface_hub>=0.34" \\
    tokenizers safetensors accelerate soundfile edge-tts faster-whisper

# torchaudio's C++ extension must match the prebuilt torch ABI exactly.
!pip install -q --no-deps "torchaudio==$(python -c 'import torch; print(torch.__version__.split(\"+\")[0])')"

import torch, torchaudio, transformers
print("torch", torch.__version__, "| torchaudio", torchaudio.__version__,
      "| transformers", transformers.__version__)

# If the model load below complains about the port API, pin the transformers
# version the port was written against:
# !pip install -q "transformers==5.5.4"
"""))

# ----------------------------------------------------------- gpu check ----
CELLS.append(code("""import sys, torch

print("python", sys.version.split()[0])
print("torch  ", torch.__version__, "| cuda", torch.version.cuda,
      "| available", torch.cuda.is_available())
if torch.cuda.is_available():
    p = torch.cuda.get_device_properties(0)
    free, total = torch.cuda.mem_get_info()
    print(f"GPU    : {p.name}  {total/1e9:.1f} GB  sm_{p.major}{p.minor} "
          f"(free {free/1e9:.1f} GB)")
else:
    raise SystemExit("No GPU - this notebook needs the T4 runtime.")
"""))

# ---------------------------------------------------------------- config --
CELLS.append(md("## 2. Configuration\n\nUpload the EPUB to `/content/` (Files panel -> the upload icon next to\n`/content/`), then set `EPUB_PATH`. If you prefer, the cell below can open a\nfile picker instead."))
CELLS.append(code("""from pathlib import Path

# ------------------------------------------------------------------ edit me
EPUB_PATH = "/content/shadowslave.epub"     # <- your uploaded epub
CHAPTER   = 0                               # 0 = "Chapter 1 Nightmare Begins"
VOICE     = "jenny"      # jenny | aria | ava | ana  (all female)
# -------------------------------------------------------------------------

OUT_DIR   = Path("/content/out")
OUT_DIR.mkdir(parents=True, exist_ok=True)

# Higgs sampling. top_p < 1.0 is essential: without nucleus filtering the
# sampler can fall into repetition loops (see the notes further down).
TEMPERATURE, TOP_K, TOP_P = 0.7, 80, 0.95
MAX_CHARS = 200          # chunk size in characters
RETRIES   = 3            # extra render attempts per chunk
SEED      = 0
MAX_SECONDS = 60         # per-chunk cap
DTYPE     = "float16"    # T4 is sm_75 -> fp16, not bf16
VERIFY    = True         # transcribe each take with Whisper before accepting

REPO        = "multimodalart/higgs-audio-v3-tts-4b-transformers"
CODEC_REPO  = "bosonai/higgs-audio-v2-tokenizer"

print("epub exists:", Path(EPUB_PATH).exists())
"""))
CELLS.append(md("""If the path above is wrong, use the picker:

```python
from google.colab import files
uploaded = files.upload()          # -> pick shadowslave.epub
EPUB_PATH = "/content/" + next(iter(uploaded))
```
"""))

# -------------------------------------------------------------- download --
CELLS.append(md("## 3. Download the Higgs weights\n\n~10.1 GB: the 9.3 GB Qwen3-4B backbone plus the 0.8 GB neural codec that turns\ndiscrete audio codes into a waveform."))
CELLS.append(code("""from huggingface_hub import snapshot_download

lm_path = snapshot_download(REPO)
codec_path = snapshot_download(CODEC_REPO)
print("LM   :", lm_path)
print("codec:", codec_path)
"""))

# --------------------------------------------------------- reference voice -
CELLS.append(md("""## 4. Reference voice

Higgs TTS 3 is a zero-shot cloner — it has no built-in speaker list, so the
voice comes from a short reference clip plus its verbatim transcript.

`jenny` is Microsoft Edge's `en-US-JennyNeural` (friendly, considerate,
comforting); `aria` is `en-US-AriaNeural` (positive, confident — the classic
audiobook narrator); `ava` is `en-US-AvaMultilingualNeural` (expressive,
caring); `ana` is `en-US-AnaNeural` (light and cute).

The clip is generated with Microsoft's free Edge TTS endpoint — no account,
no key. It is a *synthetic* voice, not a clone of any real person."""))
CELLS.append(code("""import asyncio, subprocess
from pathlib import Path

import edge_tts
import numpy as np
import soundfile as sf

VOICES = {
    "jenny": "en-US-JennyNeural",
    "aria":  "en-US-AriaNeural",
    "ava":   "en-US-AvaMultilingualNeural",
    "ana":   "en-US-AnaNeural",
}

REF_TEXT = (
    "The rain had not stopped for three days, and the city had learned to live "
    "underneath it. People moved quickly between the awnings, heads down, "
    "saying very little. There was a kind of quiet in the streets that was not "
    "peace at all, but rather the held breath before something changes. "
    "And in a small room above the old market, someone finally began to speak."
)

REF_DIR = Path("/content/refs"); REF_DIR.mkdir(parents=True, exist_ok=True)
REF_WAV = REF_DIR / f"{VOICE}.wav"
REF_TXT = REF_DIR / f"{VOICE}.txt"


async def _synth_ref(voice: str, out_mp3: Path, tries: int = 4):
    last = None
    for i in range(1, tries + 1):
        try:
            comm = edge_tts.Communicate(REF_TEXT, voice, rate="-8%", pitch="+0Hz")
            await comm.save(str(out_mp3))
            return
        except Exception as exc:                      # transient network errors
            last = exc
            print(f"  retry {i}/{tries}: {exc}")
            await asyncio.sleep(2 * i)
    raise last


if not REF_WAV.exists():
    mp3 = REF_DIR / f"{VOICE}.mp3"
    asyncio.run(_synth_ref(VOICES[VOICE], mp3))
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", str(mp3),
         "-ac", "1", "-ar", "24000", "-c:a", "pcm_s16le", str(REF_WAV)],
        check=True,
    )
    REF_TXT.write_text(REF_TEXT, encoding="utf-8")

ref_wav, ref_sr = sf.read(str(REF_WAV), dtype="float32")
if ref_wav.ndim > 1:
    ref_wav = ref_wav.mean(axis=1)
assert ref_sr == 24000, ref_sr
print(f"reference: {REF_WAV.name}  {len(ref_wav)/ref_sr:.1f}s @ {ref_sr} Hz")
"""))

# ------------------------------------------------------------------ epub --
CELLS.append(md("## 5. Extract the chapter from the EPUB\n\nThe OPF spine gives the true reading order (this book has **3,220 chapters**)."))
CELLS.append(code('''import html
import re
import zipfile
from html.parser import HTMLParser

BLOCK = {"p","div","br","li","tr","h1","h2","h3","h4","h5","h6","blockquote",
         "section","article","hr","pre","td"}
SKIP  = {"script","style","head","title","meta","link"}


class Text(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts, self.stack = [], []

    def handle_starttag(self, tag, attrs):
        if tag in SKIP:
            self.stack.append(tag)
        elif tag in BLOCK:
            self.parts.append("\\n")

    def handle_startendtag(self, tag, attrs):
        if tag in BLOCK:
            self.parts.append("\\n")

    def handle_endtag(self, tag):
        if tag in SKIP:
            if tag in self.stack:
                del self.stack[len(self.stack) - 1 - self.stack[::-1].index(tag):]
        elif tag in BLOCK:
            self.parts.append("\\n")

    def handle_data(self, data):
        if not self.stack:
            self.parts.append(data)

    def text(self) -> str:
        return "".join(self.parts)


def html_to_text(raw: str) -> str:
    p = Text(); p.feed(raw); p.close()
    s = p.text().replace("\\xa0", " ")
    s = "".join(c for c in s if c in "\\n\\t" or unicodedata_category(c) != "C")
    lines = [re.sub(r"[ \\t]+", " ", ln).strip() for ln in s.split("\\n")]
    return "\\n".join(ln for ln in lines if ln)


def unicodedata_category(c: str) -> str:
    import unicodedata
    return unicodedata.category(c)


def epub_chapters(path: str):
    """[(title, text)] for every chapter, in spine order."""
    with zipfile.ZipFile(path) as zf:
        opf_name = None
        try:
            c = zf.read("META-INF/container.xml").decode("utf-8", "replace")
            m = re.search(r\'full-path="([^"]+\\.opf)"\', c)
            if m:
                opf_name = m.group(1)
        except KeyError:
            pass
        if opf_name is None:
            opf_name = next(n for n in zf.namelist() if n.lower().endswith(".opf"))

        opf = zf.read(opf_name).decode("utf-8", "replace")
        base = opf_name.rsplit("/", 1)[0] if "/" in opf_name else ""
        manifest = {}
        for m in re.finditer(r"<item\\b[^>]*>", opf, re.I):
            t = m.group(0)
            i = re.search(r\'\\bid=["\\\']([^"\\\']+)["\\\']\', t, re.I)
            h = re.search(r\'\\bhref=["\\\']([^"\\\']+)["\\\']\', t, re.I)
            if i and h:
                manifest[i.group(1)] = h.group(1)

        titles = {}
        for n in zf.namelist():
            if n.lower().endswith(".ncx"):
                ncx = zf.read(n).decode("utf-8", "replace")
                for pm in re.finditer(r"<navPoint\\b.*?</navPoint>", ncx, re.S | re.I):
                    blk = pm.group(0)
                    tm = re.search(r"<text>(.*?)</text>", blk, re.S | re.I)
                    sm = re.search(r\'<content\\s+src=["\\\']([^"\\\']+)["\\\']\', blk, re.I)
                    if tm and sm:
                        titles[sm.group(1)] = html.unescape(
                            re.sub(r"<[^>]+>", "", tm.group(1))).strip()
                break

        out = []
        for m in re.finditer(r"<itemref\\b[^>]*>", opf, re.I):
            idref = re.search(r\'\\bidref=["\\\']([^"\\\']+)["\\\']\', m.group(0), re.I)
            if not idref or idref.group(1) not in manifest:
                continue
            href = manifest[idref.group(1)]
            full = f"{base}/{href}" if base else href
            segs = []
            for s in full.split("/"):
                if s == "..":
                    if segs: segs.pop()
                elif s not in ("", "."):
                    segs.append(s)
            full = "/".join(segs)
            if full not in zf.namelist():
                continue
            text = html_to_text(zf.read(full).decode("utf-8", "replace"))
            if text.strip():
                out.append((titles.get(href, ""), text))
        return out


CHAPTERS = epub_chapters(EPUB_PATH)
print(f"{len(CHAPTERS)} chapters found")
title, chapter_text = CHAPTERS[CHAPTER]
print(f"chapter {CHAPTER}: {title!r}  ({len(chapter_text.split())} words)")
'''))

# ------------------------------------------------------------------ chunk -
CELLS.append(md("""## 6. Chunk the chapter and apply emotional direction

Higgs renders one utterance at a time, so the prose is split at sentence
boundaries into ~200-character chunks. 200 characters is deliberate: long
inputs degenerate far more often.

`EMOTION_MAP` assigns Higgs control tags per paragraph. The tags are
sentence-level, so they are attached to every sentence of a matched
paragraph. `EMOTION_MAP` below is a hand-written arc for Chapter 1
(gallows humour -> procedural dread -> flat exposition -> exhaustion). The
same mechanism can be used for any chapter; for the whole book you would
want a per-chapter map or a heuristic."""))
CELLS.append(code('''import json
import re

JUNK = re.compile(
    r"^\\s*(?:\\.me|\\.com|\\.net|\\.org|www\\.\\S+|https?://\\S*|ads? by\\s.*|"
    r"copyright\\s+\\d{4}.*|share this.*|download.*|read more.*)\\s*$", re.I)
ABBREV = {"mr","mrs","ms","dr","prof","st","sr","jr","vs","etc","e.g","i.e",
          "fig","no","vol","approx","al","inc","ltd","co","gen","col","capt",
          "sgt","lt","rev","hon","ave","rd","mt","ft"}
SENT_END = re.compile(r"(?<=[.!?…])[\\"\\'”’)\\]]*\\s+")

# paragraph prefix -> Higgs control tags
EMOTION_MAP = {
    "A frail-looking young man": "<|emotion:contemplation|>",
    "After all, his life was coming to an end.": "<|emotion:bitterness|>",
    "Enjoying the warmth of the luxurious drink": "<|emotion:contentment|>",
    "Giving the cup of coffee an intense look": "<|emotion:amusement|>",
    "He stared into the distance, dozing off": "<|emotion:helplessness|>",
    "Shaking his head and cursing": "<|emotion:amusement|>",
    "The cup missed the bin by a wide margin": "<|emotion:amusement|>",
    "Inside, a tired-looking officer": "<|emotion:contemplation|>",
    "Sunny looked around with curiosity": "<|emotion:contemplation|>",
    "Sunny cleared his throat.": "<|emotion:helplessness|>",
    "Then he scratched the back of his head": "<|emotion:helplessness|>",
    "The officer\\'s expression instantly changed": "<|emotion:surprise|>",
    "The officer became visibly paler.": "<|emotion:fear|>",
    "Then, with a hurried motion": "<|emotion:determination|>",
    "The Nightmare Spell first appeared": "<|emotion:contemplation|><|prosody:speed_slow|>",
    "At first, the emergence of a new disease": "<|emotion:contemplation|><|prosody:speed_slow|>",
    "When the infected started dying in their sleep": "<|emotion:bitterness|><|prosody:speed_slow|>",
    "No one knew what the Spell was": "<|emotion:contemplation|><|prosody:speed_slow|>",
    "In the end, it was the Awakened": "<|emotion:awe|><|prosody:speed_slow|>",
    "Of course, it was only the first of the catastrophes": "<|emotion:bitterness|><|prosody:speed_slow|>",
    "For an average person, being chosen by the Spell": "<|emotion:contemplation|><|prosody:speed_slow|>",
    "The richer your family was": "<|emotion:contemplation|><|prosody:speed_slow|>",
    "But for Sunny, who had no family": "<|emotion:sadness|><|prosody:speed_slow|>",
    "A few minutes later, Sunny was yawning": "<|emotion:helplessness|>",
    "Sunny did not particularly care about them.": "<|emotion:helplessness|>",
    "Finally, the vault door opened": "<|emotion:determination|>",
    "Sunny blinked a few times": "<|emotion:helplessness|>",
    "Sunny tried to shrug": "<|emotion:helplessness|>",
    "That\\'s why he got this weird-ass name": "<|emotion:sadness|>",
    "Sunny simply shook his head.": "<|emotion:sadness|>",
    "For a second, there was a dark look on the policeman\\'s face": "<|emotion:contemplation|>",
    "The policeman sighed.": "<|emotion:helplessness|>",
    "Not waiting for a response, he added:": "<|emotion:determination|>",
    "Sunny gave him a questioning look.": "<|emotion:confusion|>",
    "That was a hard question to answer.": "<|emotion:confusion|>",
    "The old policeman shook his head.": "<|emotion:determination|>",
    "The old policeman waited for a second": "<|emotion:contemplation|>",
    "Sunny was getting more and more sleepy.": "<|emotion:helplessness|>",
    "The voice of the old policeman sounded more and more distant.": "<|emotion:helplessness|>",
    "The armored room was growing dimmer.": "<|emotion:helplessness|>",
    "Already half-asleep, Sunny felt a bit touched": "<|emotion:affection|>",
    "With that last thought, Sunny finally slipped": "<|emotion:helplessness|>",
    "Everything became black.": "<|emotion:awe|>",
    "And then, in the darkness, a faintly familiar voice rang:": "<|emotion:surprise|>",
}


def clean_text(t: str) -> str:
    t = t.replace("\\u2014", ", ").replace("\\u2013", ", ")
    t = t.replace("\\u2026", "...").replace("\\xa0", " ")
    t = re.sub(r"\\s+", " ", t)
    t = re.sub(r"\\s+([,.;:!?])", r"\\1", t)
    t = re.sub(r"([,.;:!?])(?=[A-Za-z])", r"\\1 ", t)
    t = re.sub(r"!{2,}", "!", t)
    t = re.sub(r"\\?{2,}", "?", t)
    t = re.sub(r"\\.{4,}", "...", t)
    t = re.sub(r"[*\\u2605\\u2606\\u2665\\u2666\\u00b7]+", " ", t)
    return t.strip()


def split_sentences(text: str):
    out, start = [], 0
    for m in SENT_END.finditer(text):
        cand = text[start:m.end()].strip()
        words = re.findall(r"[\\w\\']+", cand)
        if words and words[-1].lower() in ABBREV and m.group(0).strip() == ".":
            continue
        if cand:
            out.append(cand)
        start = m.end()
    tail = text[start:].strip()
    if tail:
        out.append(tail)
    return out


def chunk_chapter(raw: str, max_chars: int, emotion_map: dict):
    chunks, para_idx = [], 0
    for line in (l.strip() for l in raw.split("\\n")):
        if not line or JUNK.match(line) or not re.search(r"[A-Za-z]", line):
            if re.fullmatch(r"[*\\u2014\\u2013\\u2026\\s]{3,}", line) and not re.search(r"[A-Za-z]", line):
                chunks.append({"text": "", "words": 0, "para": para_idx,
                               "scene_break": True})
                para_idx += 1
            continue
        para = clean_text(line)
        if not para:
            continue
        tags = ""
        for prefix, t in emotion_map.items():
            if para.startswith(prefix):
                tags = t
                break
        sents = split_sentences(para)
        if tags:
            sents = [tags + s for s in sents]
        buf = ""
        for s in sents:
            if not buf:
                buf = s
            elif len(buf) + 1 + len(s) <= max_chars:
                buf = f"{buf} {s}"
            else:
                chunks.append({"text": buf, "words": len(buf.split()),
                               "para": para_idx, "scene_break": False})
                buf = s
            while len(buf) > max_chars * 1.6:
                cut = buf.rfind(", ", 0, max_chars)
                if cut <= 0:
                    cut = buf.rfind(" ", 0, max_chars)
                if cut <= 0:
                    break
                chunks.append({"text": buf[:cut].strip(),
                               "words": len(buf[:cut].split()),
                               "para": para_idx, "scene_break": False})
                buf = buf[cut + 1:].strip()
        if buf:
            chunks.append({"text": buf, "words": len(buf.split()),
                           "para": para_idx, "scene_break": False})
        para_idx += 1
    for i, c in enumerate(chunks):
        c["i"] = i
    return chunks


CHUNKS = chunk_chapter(chapter_text, MAX_CHARS, EMOTION_MAP)
spoken = [c for c in CHUNKS if not c["scene_break"]]
words = sum(c["words"] for c in spoken)
print(f"{len(spoken)} chunks, {words:,} words, ~{words/150:.1f} min of speech")
for c in spoken[:3]:
    print("  ", c["text"][:100])
'''))

# ------------------------------------------------------------------ model -
CELLS.append(md("""## 7. Load the model

`AutoModelForCausalLM` with `trust_remote_code=True` loads the port's
`modeling_higgs_multimodal_qwen3.py`. The neural codec is fetched lazily from
`bosonai/higgs-audio-v2-tokenizer` and is always kept in fp32 (decoding is
unstable in fp16).

FP16 is used because the T4 is `sm_75` — it has native fp16 but not bf16."""))
CELLS.append(code("""from transformers import AutoModelForCausalLM, AutoTokenizer

tok = AutoTokenizer.from_pretrained(REPO)
model = AutoModelForCausalLM.from_pretrained(
    REPO, trust_remote_code=True, dtype=getattr(torch, DTYPE),
    device_map={"": 0}, low_cpu_mem_usage=True,
).eval()

free, total = torch.cuda.mem_get_info()
print(f"model loaded | {free/1e9:.1f} GB free of {total/1e9:.1f} GB VRAM")
print("sample rate:", model.config.sample_rate)
"""))

# --------------------------------------------------------------- synthesis -
CELLS.append(md("""## 8. Render the chapter

**Why the render/score/retry loop?** Higgs is stochastic: the same text and
parameters can come out perfect or degenerate (the model restarting the
utterance in a loop). A duration check cannot tell those apart, so each take
is transcribed with Whisper and scored (word error rate + coverage); a take
that fails is re-rendered with a different seed *and* different sampling
parameters.

Set `VERIFY = False` to skip the Whisper check and render straight through
(much faster, no quality guarantee)."""))
CELLS.append(code('''import time

import soundfile as sf

# ---------------------------------------------------- optional Whisper gate
_scorer = None


def get_scorer():
    """Transcribe-and-score helper (Whisper small.en on CPU)."""
    global _scorer
    if _scorer is None:
        from faster_whisper import WhisperModel
        _scorer = WhisperModel("small.en", device="cpu", compute_type="int8")
    return _scorer


_TAG = re.compile(r"<\\|[^|]*\\|>")


def _norm(s: str):
    s = _TAG.sub(" ", s).lower()
    s = s.replace("\\u2019", "\\'")
    s = re.sub(r"[^a-z0-9\\'\\s]", " ", s)
    return [w for w in s.split() if w]


def _wer(ref, hyp):
    if not ref:
        return 0.0
    prev = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        cur = [i] + [0] * len(hyp)
        for j, h in enumerate(hyp, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (r != h))
        prev = cur
    return prev[len(hyp)] / len(ref)


def _to_16k(wav):
    """24 kHz float32 -> 16 kHz float32, via ffmpeg.

    faster-whisper's own file reader relies on a PyAV API that recent `av`
    releases removed, so we hand Whisper a numpy array instead of a path.
    """
    import tempfile
    import subprocess
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as t1:
        p1 = Path(t1.name)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as t2:
        p2 = Path(t2.name)
    try:
        sf.write(str(p1), wav, 24000, subtype="PCM_16")
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-i", str(p1),
             "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", str(p2)],
            check=True,
        )
        return sf.read(str(p2), dtype="float32")[0]
    finally:
        p1.unlink(missing_ok=True)
        p2.unlink(missing_ok=True)


def score(wav, text):
    """-> (wer, coverage) or None if the scorer is unavailable."""
    try:
        scorer = get_scorer()
    except Exception as exc:                      # faster-whisper missing, etc.
        print("  (Whisper scorer unavailable:", exc, ")")
        return None
    try:
        segs, _ = scorer.transcribe(_to_16k(wav), beam_size=5, language="en",
                                    vad_filter=False)
        hyp = " ".join(s.text.strip() for s in segs)
    except Exception as exc:
        print("  (transcription failed:", exc, ")")
        return None
    ref_w, hyp_w = _norm(text), _norm(hyp)
    if not ref_w:
        return None
    return _wer(ref_w, hyp_w), len(hyp_w) / len(ref_w)


# ------------------------------------------------------------- the renderer
CONFIGS = [
    (TEMPERATURE, TOP_K, TOP_P),
    (0.8, 50, 0.95),
    (0.9, 50, 0.95),
    (0.8, 80, 0.95),
]
ACCEPT_WER, ACCEPT_COV = 0.15, 0.90

SEG_DIR = OUT_DIR / f"ch{CHAPTER:04d}"
SEG_DIR.mkdir(parents=True, exist_ok=True)

ref_tensor = torch.as_tensor(ref_wav, dtype=torch.float32)


def render(text, attempt=0):
    temp, tk, tp = CONFIGS[attempt % len(CONFIGS)]
    t0 = time.time()
    with torch.no_grad():
        wav = model.generate_speech(
            text, tok,
            reference_audio=ref_tensor, reference_sample_rate=24000,
            reference_text=REF_TEXT,
            max_new_tokens=int(MAX_SECONDS * 25),
            temperature=temp, top_k=tk, top_p=tp,
        )
    arr = wav.detach().float().cpu().numpy().reshape(-1)
    return arr, (temp, tk, tp), time.time() - t0


t_start = time.time()
done = 0
for c in spoken:
    seg_path = SEG_DIR / f"seg_{c['i']:04d}.wav"
    if seg_path.exists():
        done += 1
        continue

    best, best_rank, chosen = None, None, None
    for attempt in range(RETRIES + 1):
        arr, params, dt = render(c["text"], attempt)
        sc = score(arr, c["text"]) if VERIFY else None
        if sc is None:
            best, chosen = arr, params
            break
        wer, cov = sc
        rank = (wer, -cov)
        if best_rank is None or rank < best_rank:
            best_rank, best, chosen = rank, arr, params
        if wer <= ACCEPT_WER and cov >= ACCEPT_COV:
            best, chosen = arr, params
            break
        print(f"    attempt {attempt}: wer {wer*100:.1f}% cov {cov*100:.1f}% "
              f"-> retrying", flush=True)

    sf.write(str(seg_path), best, 24000, subtype="PCM_16")
    done += 1
    print(f"[{c['i']:4d}/{len(spoken)}] {len(best)/24000:5.1f}s  params={chosen}  "
          f"{c['text'][:60]}", flush=True)

print(f"rendered {done} chunks in {(time.time()-t_start)/60:.1f} min")
'''))

# ---------------------------------------------------------------- assemble -
CELLS.append(md("""## 9. Assemble the chapter

Pacing is built from real silence rather than model-generated pauses:
220 ms between chunks, 620 ms between paragraphs, 1.6 s at scene breaks
(`***`). The spoken Boson AI credit line closes the file so the audio is
compliant on its own."""))
CELLS.append(code('''GAP_CHUNK, GAP_PARA, GAP_SCENE = 0.22, 0.62, 1.60
LEAD_IN, LEAD_OUT = 0.90, 1.20
CREDIT = ("This audio was created with Boson A I's Higgs Audio. "
          "Boson A I dot, www dot boson dot A I slash higgs audio.")


def sil(sec):
    return np.zeros(int(sec * 24000), dtype=np.float32)


pieces = [sil(LEAD_IN)]
prev_para = None
for c in CHUNKS:
    if c.get("scene_break"):
        pieces.append(sil(GAP_SCENE))
        prev_para = None
        continue
    p = SEG_DIR / f"seg_{c['i']:04d}.wav"
    if not p.exists():
        continue
    if prev_para is not None:
        pieces.append(sil(GAP_PARA if c["para"] != prev_para else GAP_CHUNK))
    pieces.append(sf.read(str(p), dtype="float32")[0])
    prev_para = c["para"]
pieces.append(sil(LEAD_OUT))

# spoken licence credit
credit_arr, _, _ = render(CREDIT, 0)
pieces.append(credit_arr)
pieces.append(sil(LEAD_OUT))

full = np.concatenate(pieces)
wav_out = OUT_DIR / f"ch{CHAPTER:04d}.wav"
sf.write(str(wav_out), full, 24000, subtype="PCM_16")
print(f"wav: {wav_out}  ({len(full)/24000/60:.1f} min)")
'''))
CELLS.append(code('''import subprocess

mp3_out = OUT_DIR / f"ch{CHAPTER:04d}.mp3"
subprocess.run(
    ["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav_out),
     "-c:a", "libmp3lame", "-b:a", "96k", "-ar", "24000", "-ac", "1",
     str(mp3_out)],
    check=True,
)
print("mp3:", mp3_out, f"({mp3_out.stat().st_size/1e6:.1f} MB)")
'''))

# ----------------------------------------------------------------- verify -
CELLS.append(md("""## 10. Verify the whole chapter

Transcribes every rendered chunk and reports word error rate, so the result
can be judged objectively instead of by ear."""))
CELLS.append(code('''rows = []
for c in spoken:
    p = SEG_DIR / f"seg_{c['i']:04d}.wav"
    if not p.exists():
        continue
    sc = score(sf.read(str(p), dtype="float32")[0], c["text"])
    if sc is None:
        continue
    wer, cov = sc
    rows.append((c["i"], wer, cov))

wers = [r[1] for r in rows]
print(f"chunks        : {len(rows)}")
print(f"mean WER      : {sum(wers)/max(len(wers),1)*100:.1f} %")
print(f"median WER    : {sorted(wers)[len(wers)//2]*100:.1f} %")
print(f"WER < 10 %    : {sum(1 for w in wers if w < 0.10)}/{len(rows)}")
print(f"truncated     : {sum(1 for r in rows if r[2] < 0.85)}")
worst = sorted(rows, key=lambda r: -r[1])[:5]
for i, w, cov in worst:
    print(f"  worst [{i:4d}] wer {w*100:5.1f}%  cov {cov*100:5.1f}%")
'''))

# ------------------------------------------------------------------ notes --
CELLS.append(md("""## Notes and next steps

**Full book.** This book has **3,220 chapters** (~340 hours of audio). On a T4
that is roughly 340 GPU-hours of rendering; on the free tier that is many
sessions, so for the whole book either rent a GPU (~20x faster per hour than
Colab) or use the hosted Boson API, which the licence's Creator Use Grant
explicitly permits for audiobooks.

**Why `top_p = 0.95`.** With `top_p = 1.0` the sampler can draw
low-probability tail tokens, which is what starts the repetition loops.
Nucleus filtering removed them in every measurement.

**Why chunks of 200 characters.** Long inputs degenerate far more often.

**Tags.** Higgs supports 43 inline control tags: 21 emotions, 3 styles
(whispering / shouting / singing), 10 prosody (speed, pitch, pauses) and 9
sound effects. They are real special tokens in the tokenizer, so they can be
dropped straight into the text. The `PROMPTING.md` in the model repo has the
full catalogue.

**Licence.** Boson Higgs TTS 3 Research and Non-Commercial License, with a
Creator Use Grant covering audiobooks (including monetised creator work)
provided Boson AI is credited. Do not clone a voice without consent.

**Other backends.** This repo also contains an INT4 ONNX CPU backend
(`scripts/higgs_engine.py`) that runs on any machine with no GPU — about 8-12x
slower than realtime on a 4-core CPU — plus `scripts/build_m4b.py` to package
finished chapters into a single M4B with chapter markers."""))

CELLS.append(md("""### Optional: package a range of chapters into one M4B

```python
# after rendering chapters 0..20
!ffmpeg -y -i ch0000.mp3 -i ch0001.mp3 ... -c copy book.m4b
```

(`scripts/build_m4b.py` in the project repo does this properly, writing a
real chapter table so audiobook players show a navigable TOC.)"""))


def build() -> dict:
    return {
        "cells": CELLS,
        "metadata": {
            "accelerator": "GPU",
            "colab": {"provenance": [], "name": "higgs_tts_audiobook.ipynb"},
            "kernelspec": {"display_name": "Python 3", "name": "python3"},
            "language_info": {"name": "python"},
        },
        "nbformat": 4,
        "nbformat_minor": 0,
    }


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(build(), indent=1, ensure_ascii=False), encoding="utf-8")
    print(f"wrote {OUT}  ({len(CELLS)} cells)")


if __name__ == "__main__":
    main()
