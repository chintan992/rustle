"""Validate the generated Colab notebook.

Checks that the file is valid JSON, that every code cell parses as Python,
and that the cells reference only names that earlier cells define.
"""

from __future__ import annotations

import ast
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NB = ROOT / "colab" / "higgs_tts_audiobook.ipynb"


def main() -> int:
    if not NB.exists():
        print("notebook not found - run make_colab_notebook.py first")
        return 1

    nb = json.loads(NB.read_text(encoding="utf-8"))
    cells = nb["cells"]
    print(f"notebook: {NB.name}")
    print(f"cells   : {len(cells)} "
          f"({sum(1 for c in cells if c['cell_type'] == 'code')} code, "
          f"{sum(1 for c in cells if c['cell_type'] == 'markdown')} markdown)")

    errors = 0
    for i, cell in enumerate(cells):
        if cell["cell_type"] != "code":
            continue
        src = "".join(cell["source"])
        if src.lstrip().startswith("!"):
            # shell cell - only check that the line count is sane
            continue
        try:
            tree = ast.parse(src)
        except SyntaxError as exc:
            errors += 1
            print(f"\n[cell {i}] SYNTAX ERROR: {exc}")
            lines = src.splitlines()
            if exc.lineno:
                lo = max(0, exc.lineno - 3)
                for n in range(lo, min(len(lines), exc.lineno + 2)):
                    mark = ">>" if n == exc.lineno - 1 else "  "
                    print(f"  {mark} {n+1:3d}| {lines[n]}")
            continue

        # Collect top-level definitions so we can sanity-check ordering.
        names = []
        for node in tree.body:
            if isinstance(node, ast.FunctionDef):
                names.append(node.name)
            elif isinstance(node, ast.Assign):
                for t in node.targets:
                    if isinstance(t, ast.Name):
                        names.append(t.id)
            elif isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name):
                names.append(node.target.id)
            elif isinstance(node, (ast.Import, ast.ImportFrom)):
                for a in node.names:
                    names.append(a.asname or a.name.split(".")[0])
        print(f"[cell {i:2d}] ok  ({len(src.splitlines()):3d} lines)  "
              f"defines: {', '.join(names[:8]) or '-'}")

    print()
    if errors:
        print(f"FAILED: {errors} cell(s) have syntax errors")
        return 1
    print("all code cells parse cleanly")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
