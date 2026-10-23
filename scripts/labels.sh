#!/usr/bin/env bash
# Pull the labels the user made in the app into data/user_labels.jsonl, merged and deduped.
#
#   scripts/labels.sh            # pull + merge + print counts
#
# The app records every "this is an ad" / "not an ad" / "delete this ad" tap in an append-only
# table, so triage is done once: the same file feeds training and every future regression check.
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
DEVICE="${DEVICE:-be1a246a}"
OUT=data/user_labels.jsonl

tmp=$(mktemp -d)
# newest export written by Ads ⋮ → Export labels for training
file=$("$ADB" -s "$DEVICE" shell "ls -t /sdcard/Android/data/ir.smsfilter/files/labels-*.jsonl 2>/dev/null | head -1" | tr -d '\r')
if [[ -z "$file" ]]; then
  echo "no export on the device: open Ads ⋮ → Export labels for training first" >&2
  exit 1
fi
"$ADB" -s "$DEVICE" pull "$file" "$tmp/pulled.jsonl" >/dev/null
echo "pulled $(wc -l < "$tmp/pulled.jsonl") labels from $file"

python3 - "$tmp/pulled.jsonl" "$OUT" <<'EOF'
import json, sys, collections
from pathlib import Path

pulled, out = Path(sys.argv[1]), Path(sys.argv[2])
seen = {}
if out.exists():
    for line in out.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        d = json.loads(line)
        seen[(d["text"], d["label"])] = d
added = 0
for line in pulled.read_text(encoding="utf-8").splitlines():
    if not line.strip():
        continue
    d = json.loads(line)
    key = (d["text"], d["label"])
    if key not in seen:
        seen[key] = d
        added += 1
out.write_text("\n".join(json.dumps(d, ensure_ascii=False) for d in seen.values()) + "\n", encoding="utf-8")
c = collections.Counter(d["label"] for d in seen.values())
print(f"merged: +{added} new, {len(seen)} total {dict(c)} -> {out}")
EOF
rm -rf "$tmp"
