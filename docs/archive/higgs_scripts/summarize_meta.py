import json
import sys
from pathlib import Path

p = Path(sys.argv[1])
m = json.loads(p.read_text(encoding="utf-8"))
segs = m["segments"]

wers = []
covs = []
for s in segs:
    for a in s["attempts"]:
        if a.get("wer") is not None:
            wers.append(a["wer"])
        if a.get("coverage") is not None:
            covs.append(a["coverage"])

attempts = sum(len(s["attempts"]) for s in segs)
retries = attempts - len(segs)
first_wer = [
    s["attempts"][0]["wer"] for s in segs if s["attempts"][0].get("wer") is not None
]

print(f"segments                : {len(segs)}")
print(f"total attempts          : {attempts}   (retries: {retries})")
if wers:
    print(f"mean WER (all attempts) : {sum(wers) / len(wers) * 100:.1f}%   max {max(wers) * 100:.1f}%")
    print(f"first-attempt WER<10%   : {sum(1 for w in first_wer if w < 0.10)}/{len(first_wer)}")
    print(f"first-attempt WER<25%   : {sum(1 for w in first_wer if w < 0.25)}/{len(first_wer)}")
if covs:
    print(f"coverage min/mean       : {min(covs) * 100:.1f}% / {sum(covs) / len(covs) * 100:.1f}%")
audio = sum(s["seconds"] for s in segs)
print(f"total speech            : {audio / 60:.1f} min")
stops = {}
for s in segs:
    stops[s["stop"]] = stops.get(s["stop"], 0) + 1
print(f"stop reasons             : {stops}")
