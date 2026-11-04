import json
import sys
from pathlib import Path

p = Path(sys.argv[1])
m = json.loads(p.read_text(encoding="utf-8"))
for s in m["segments"]:
    print(f"[{s['i']}] {s['seconds']:.1f}s stop={s['stop']}")
    for a in s["attempts"]:
        wer = a.get("wer")
        cov = a.get("coverage")
        w = f"{wer * 100:5.1f}%" if wer is not None else "  n/a"
        c = f"{cov * 100:6.1f}%" if cov is not None else "   n/a"
        print(f"     attempt {a['attempt']} seed={a['seed']} "
              f"params={a.get('params')} {a['seconds']:5.1f}s "
              f"ratio={a['ratio']:.2f} wer={w} cov={c} stop={a['stop']}")
