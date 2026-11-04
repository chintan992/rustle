"""Turn a chapter's plain text into TTS-sized chunks.

Design notes
------------
* Higgs renders one utterance at a time. Very long inputs get truncated or
  garbled, so text is split at *sentence* boundaries into chunks of roughly
  ``--max-chars`` characters (default 260, well inside the range the model
  handles cleanly).
* Paragraph boundaries are preserved as their own chunk boundary so the
  narrator can breathe between them; the assembler inserts real silence.
* EPUB junk (watermark lines such as ``.me``) is dropped.
* Em dashes are replaced with a comma + short pause marker, because em dashes
  are frequently read as a pause anyway and some TTS front-ends mishandle them.

Output: JSON list of ``{"i": idx, "text": str, "words": int}``.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

# Lines that are watermark/advertising noise rather than prose.
JUNK_LINE = re.compile(
    r"^\s*(?:\.me|\.com|\.net|\.org|www\.\S+|https?://\S*|ads? by\s.*|"
    r"copyright\s+\d{4}.*|share this.*|download.*|read more.*)\s*$",
    re.I,
)

# Abbreviations that must not end a sentence.
ABBREV = {
    "mr", "mrs", "ms", "dr", "prof", "st", "sr", "jr", "vs", "etc", "e.g", "i.e",
    "fig", "no", "vol", "approx", "al", "inc", "ltd", "co", "gen", "col", "capt",
    "sgt", "lt", "rev", "hon", "st", "ave", "rd", "mt", "ft",
}

SENT_END = re.compile(r'(?<=[.!?…])["\'”’)\]]*\s+')


def is_junk(line: str) -> bool:
    if not line.strip():
        return True
    if JUNK_LINE.match(line):
        return True
    # A line with no letters is not prose.
    if not re.search(r"[A-Za-z]", line):
        return True
    return False


def split_sentences(text: str) -> list[str]:
    """Split a paragraph into sentences, keeping terminal punctuation."""
    text = text.strip()
    if not text:
        return []
    out: list[str] = []
    start = 0
    for m in SENT_END.finditer(text):
        end = m.end()
        candidate = text[start:end].strip()
        # Guard against splitting on abbreviations ("Dr. Smith", "e.g. ").
        words = re.findall(r"[\w']+", candidate)
        last_word = words[-1].lower() if words else ""
        if last_word in ABBREV and m.group(0).strip() == ".":
            continue
        if candidate:
            out.append(candidate)
        start = end
    tail = text[start:].strip()
    if tail:
        out.append(tail)
    return out


def clean_text(text: str) -> str:
    # Normalise dashes/quotes that trip up TTS front-ends.
    text = text.replace("\u2014", ", ").replace("\u2013", ", ")
    text = text.replace("\u2026", "...").replace("\u00a0", " ")
    text = re.sub(r"\s+", " ", text)
    # The dash->comma swap can leave "word , word"; tidy the spacing.
    text = re.sub(r"\s+([,.;:!?])", r"\1", text)
    text = re.sub(r"([,.;:!?])(?=[A-Za-z])", r"\1 ", text)
    # Collapse repeated punctuation.
    text = re.sub(r"!{2,}", "!", text)
    text = re.sub(r"\?{2,}", "?", text)
    text = re.sub(r"\.{4,}", "...", text)
    # Remove decorative unicode (stars, hearts, box drawing) used as breaks.
    text = re.sub(r"[*\u2605\u2606\u2665\u2666\u00b7\u2013\u2014]+", " ", text)
    return text.strip()


def is_dialogue(text: str) -> bool:
    """True if the paragraph is (mostly) spoken dialogue."""
    t = text.strip()
    if t.startswith(('"', "\u201c", "'")):
        return True
    quotes = t.count('"') + t.count("\u201c")
    return quotes >= 2 and len(t) < 260


def tag_paragraph(text: str, profile: str) -> str:
    """Return the control tags for the simple heuristic 'narrator' profile.

    Deliberately conservative: only expository-looking prose (no first person,
    no dialogue) gets a slightly slower delivery. Emotion is left to the
    explicit emotion map, because guessing an emotion is far more likely to
    sound wrong than to help.
    """
    if profile == "none" or not text:
        return ""
    if is_dialogue(text):
        return ""
    if re.search(r"\b(I|my|me|we|our)\b", text):
        return ""
    if len(text) < 140:
        return ""
    return "<|prosody:speed_slow|>"


def load_emotion_map(path: str | None) -> list[dict]:
    """Load a text-keyed emotion map.

    Format:
        {"rules": [{"match": "<paragraph prefix>", "tags": "<|emotion:...|>"}]}

    Keying by text prefix (rather than index) keeps the map stable when the
    chunker changes.
    """
    if not path:
        return []
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    return data.get("rules", [])


def apply_emotion_map(text: str, rules: list[dict]) -> str:
    """Prefix a paragraph with its mapped tags (paragraph level)."""
    if not rules or not text:
        return text
    for rule in rules:
        prefix = rule.get("match", "")
        if prefix and text.startswith(prefix):
            tags = rule.get("tags", "")
            return (tags + text) if tags else text
    return text


def tags_for(text: str, rules: list[dict]) -> str:
    """Return the control tags that apply to a paragraph (matched by prefix)."""
    if not rules or not text:
        return ""
    for rule in rules:
        prefix = rule.get("match", "")
        if prefix and text.startswith(prefix):
            return rule.get("tags", "")
    return ""


def load_fixes(path: str | None) -> list[tuple[str, str]]:
    """Load pronunciation / wording overrides.

    Format:  {"fixes": [{"find": "Tsk.", "replace": "Tssk."}]}

    These are applied to the paragraph text before chunking, so the model is
    given a spelling it can actually pronounce. Standard practice for
    audiobook production (proper nouns, archaic words, sound effects).
    """
    if not path:
        return []
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    return [(r["find"], r.get("replace", "")) for r in data.get("fixes", [])]


def apply_fixes(text: str, fixes: list[tuple[str, str]]) -> str:
    for find, replace in fixes:
        if find:
            text = text.replace(find, replace)
    return text


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--chapter", required=True, help="path to chapter .txt")
    ap.add_argument("--out", required=True, help="output .json")
    ap.add_argument("--max-chars", type=int, default=260)
    ap.add_argument("--min-chars", type=int, default=40)
    ap.add_argument("--tags", choices=["none", "narrator"], default="none",
                    help="'narrator' adds a conservative speed-slow heuristic")
    ap.add_argument("--emotion-map", default="",
                    help="JSON file with per-paragraph Higgs control tags")
    ap.add_argument("--fixes", default="",
                    help="JSON file with pronunciation/wording overrides")
    args = ap.parse_args()

    emotion_rules = load_emotion_map(args.emotion_map or None)
    fixes = load_fixes(args.fixes or None)

    raw = Path(args.chapter).read_text(encoding="utf-8")
    paragraphs: list[tuple[str, bool]] = []
    for ln in (l.strip() for l in raw.split("\n")):
        # A line of asterisks/dashes is a scene break in the source text.
        # (Checked before the junk filter, which would otherwise drop it.)
        if re.fullmatch(r"[*\u2014\u2013\u2026\s]{3,}", ln) and not re.search(r"[A-Za-z]", ln):
            paragraphs.append(("", True))
            continue
        if is_junk(ln):
            continue
        paragraphs.append((ln, False))

    chunks: list[dict] = []
    para_idx = 0
    for para, is_break in paragraphs:
        if is_break:
            chunks.append(
                {"text": "", "words": 0, "para": para_idx, "scene_break": True}
            )
            para_idx += 1
            continue
        para = clean_text(para)
        if not para:
            continue
        if fixes:
            para = apply_fixes(para, fixes)
        if emotion_rules:
            # Sentence-level tags (Higgs docs: emotion/style/speed/pitch colour
            # the whole sentence, so each sentence of a mapped paragraph gets
            # the tag).
            para_tags = tags_for(para, emotion_rules)
        elif args.tags == "narrator":
            para_tags = tag_paragraph(para, args.tags)
        else:
            para_tags = ""
        sentences = split_sentences(para)
        if para_tags:
            sentences = [para_tags + s for s in sentences]
        buf = ""
        for sent in sentences:
            if not buf:
                buf = sent
            elif len(buf) + 1 + len(sent) <= args.max_chars:
                buf = f"{buf} {sent}"
            else:
                chunks.append({"text": buf, "words": len(buf.split()),
                               "para": para_idx, "scene_break": False})
                buf = sent
            # A very long single sentence gets hard-split on commas.
            while len(buf) > args.max_chars * 1.6:
                cut = buf.rfind(", ", 0, args.max_chars)
                if cut <= 0:
                    cut = buf.rfind(" ", 0, args.max_chars)
                if cut <= 0:
                    break
                chunks.append({"text": buf[:cut].strip(),
                               "words": len(buf[:cut].split()),
                               "para": para_idx, "scene_break": False})
                buf = buf[cut + 1 :].strip()
        if buf:
            chunks.append({"text": buf, "words": len(buf.split()),
                           "para": para_idx, "scene_break": False})
        para_idx += 1

    for i, c in enumerate(chunks):
        c["i"] = i

    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(
        json.dumps(chunks, indent=2, ensure_ascii=False), encoding="utf-8"
    )

    total_words = sum(c["words"] for c in chunks)
    minutes = total_words / 150.0
    print(f"{len(chunks)} chunks, {sum(len(c['text']) for c in chunks):,} chars, "
          f"{total_words:,} words, ~{minutes:.1f} min of speech @ 150 wpm")
    if chunks:
        print("--- first 3 ---")
        for c in chunks[:3]:
            print(f"  [{c['i']}] {c['text'][:110]}")


if __name__ == "__main__":
    main()
