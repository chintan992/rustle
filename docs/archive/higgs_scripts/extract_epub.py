"""Extract clean prose from an EPUB, ordered by the OPF spine.

Writes one .txt per chapter into <out_dir>/, plus an index.json describing
each chapter (title, word count, char count, source file).

Pure stdlib: zipfile + html.parser. No third-party deps.
"""

from __future__ import annotations

import argparse
import html
import json
import re
import unicodedata
import zipfile
from html.parser import HTMLParser
from pathlib import Path

# Block-level tags that imply a paragraph / line break.
BLOCK_TAGS = {
    "p", "div", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6",
    "blockquote", "section", "article", "hr", "pre", "td",
}
# Tags whose content is never part of the prose.
SKIP_TAGS = {"script", "style", "head", "title", "meta", "link"}


class TextExtractor(HTMLParser):
    """Collect visible text, inserting newlines at block boundaries.

    Uses an explicit stack so that void/self-closing elements (e.g. ``<link/>``)
    cannot unbalance the "skip this subtree" depth counter.
    """

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self._skip_stack: list[str] = []

    # -- helpers ----------------------------------------------------------- #
    @property
    def _skipping(self) -> bool:
        return bool(self._skip_stack)

    def handle_starttag(self, tag, attrs):
        if tag in SKIP_TAGS:
            self._skip_stack.append(tag)
        elif tag in BLOCK_TAGS:
            self.parts.append("\n")

    def handle_startendtag(self, tag, attrs):
        # Void element: opens and closes immediately -> never pushed on the stack.
        if tag in BLOCK_TAGS:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if tag in SKIP_TAGS:
            # Pop the most recent matching skip tag (tolerates malformed nesting).
            if tag in self._skip_stack:
                idx = len(self._skip_stack) - 1 - self._skip_stack[::-1].index(tag)
                del self._skip_stack[idx:]
        elif tag in BLOCK_TAGS:
            self.parts.append("\n")

    def handle_data(self, data):
        if not self._skipping:
            self.parts.append(data)

    def text(self) -> str:
        return "".join(self.parts)


def html_to_text(raw: str) -> str:
    p = TextExtractor()
    p.feed(raw)
    p.close()
    s = p.text()
    s = s.replace("\xa0", " ")
    # Normalize unicode punctuation that confuses TTS tokenizers.
    s = unicodedata.normalize("NFKC", s)
    # Drop control characters (keep tab/newline); leave all printable text intact.
    s = "".join(
        ch for ch in s
        if ch in "\n\t" or (unicodedata.category(ch)[0] != "C")
    )
    # Collapse runs of spaces but keep line structure.
    lines = [re.sub(r"[ \t]+", " ", ln).strip() for ln in s.split("\n")]
    out: list[str] = []
    for ln in lines:
        if ln:
            out.append(ln)
    return "\n".join(out)


def find_opf(zf: zipfile.ZipFile) -> str:
    names = [n for n in zf.namelist() if n.lower().endswith(".opf")]
    if not names:
        raise SystemExit("No .opf found in EPUB")
    # Prefer the one whose container.xml points at it.
    try:
        container = zf.read("META-INF/container.xml").decode("utf-8", "replace")
        m = re.search(r'full-path="([^"]+\.opf)"', container, re.I)
        if m and m.group(1) in zf.namelist():
            return m.group(1)
    except KeyError:
        pass
    return names[0]


def parse_spine(zf: zipfile.ZipFile, opf_name: str):
    """Return (list_of_hrefs_in_spine_order, {href: title})."""
    opf = zf.read(opf_name).decode("utf-8", "replace")
    base = opf_name.rsplit("/", 1)[0] if "/" in opf_name else ""

    # manifest: id -> href
    manifest: dict[str, str] = {}
    for m in re.finditer(r"<item\b[^>]*>", opf, re.I):
        tag = m.group(0)
        iid = re.search(r'\bid=["\']([^"\']+)["\']', tag, re.I)
        href = re.search(r'\bhref=["\']([^"\']+)["\']', tag, re.I)
        if iid and href:
            manifest[iid.group(1)] = href.group(1)

    spine: list[str] = []
    for m in re.finditer(r"<itemref\b[^>]*>", opf, re.I):
        idref = re.search(r'\bidref=["\']([^"\']+)["\']', m.group(0), re.I)
        if idref and idref.group(1) in manifest:
            spine.append(manifest[idref.group(1)])

    # titles from the NCX, keyed by href
    titles: dict[str, str] = {}
    for n in zf.namelist():
        if n.lower().endswith(".ncx"):
            ncx = zf.read(n).decode("utf-8", "replace")
            for pm in re.finditer(r"<navPoint\b.*?</navPoint>", ncx, re.S | re.I):
                block = pm.group(0)
                tm = re.search(r"<text>(.*?)</text>", block, re.S | re.I)
                sm = re.search(r'<content\s+src=["\']([^"\']+)["\']', block, re.I)
                if tm and sm:
                    titles[sm.group(1)] = html.unescape(
                        re.sub(r"<[^>]+>", "", tm.group(1))
                    ).strip()
            break

    resolved = []
    for href in spine:
        full = f"{base}/{href}" if base else href
        # Normalize ../
        segs: list[str] = []
        for s in full.split("/"):
            if s == "..":
                if segs:
                    segs.pop()
            elif s not in ("", "."):
                segs.append(s)
        resolved.append(("/".join(segs), titles.get(href, "")))
    return resolved


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--epub", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--limit", type=int, default=0, help="0 = all chapters")
    args = ap.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(args.epub) as zf:
        opf_name = find_opf(zf)
        spine = parse_spine(zf, opf_name)

        index = []
        for i, (href, title) in enumerate(spine):
            if args.limit and i >= args.limit:
                break
            if href not in zf.namelist():
                continue
            raw = zf.read(href).decode("utf-8", "replace")
            text = html_to_text(raw)
            if not text.strip():
                continue
            n = len(index)
            (out_dir / f"ch{n:04d}.txt").write_text(text, encoding="utf-8")
            index.append(
                {
                    "n": n,
                    "title": title,
                    "src": href,
                    "file": f"ch{n:04d}.txt",
                    "words": len(text.split()),
                    "chars": len(text),
                }
            )

    (out_dir / "index.json").write_text(
        json.dumps(index, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    total_words = sum(c["words"] for c in index)
    print(f"Extracted {len(index)} chapters -> {out_dir}")
    print(f"Total words: {total_words:,}")
    for c in index[:5]:
        print(f"  [{c['n']:4d}] {c['title'][:60]!r} {c['words']}w  ({c['src']})")


if __name__ == "__main__":
    main()
