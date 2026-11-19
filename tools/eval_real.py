#!/usr/bin/env python3
"""Honest evaluation of rules.json against real labeled Persian corpora.

Not synthetic. The classifier here is exactly tools/mirror.py's, so numbers transfer
to the app (same rules, same stage order).

  python3 tools/eval_real.py            # both corpora
Datasets (fetched by research, see ~/research/persian_sms/BRIEF.md):
  Gheychi  5707 msgs, spam=1, NO sender, NO digits (numbers replaced)
  PSSC     4324 msgs, label=spam|ham, no sender
No sender field in either corpus => sender rules are inert here; this measures
content-only precision/recall, which is the worst case.
"""
import json
import os
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mirror import classify  # noqa: E402

DATA = Path(os.path.expanduser("~/research/persian_sms"))


def gheychi():
    for r in json.loads((DATA / "gheychi.json").read_text(encoding="utf-8")):
        yield r["Text"], ("spam" if r["Label"] == 1 else "ham")


def pssc():
    c = sqlite3.connect(DATA / "pssc.sqlite3")
    for text, label in c.execute("SELECT text, label FROM SMS"):
        yield text, label


def run(name, rows):
    tp = fp = tn = fn = 0
    missed, false_block = [], []
    for text, gold in rows:
        got = classify(text).get("label")
        spam = got == "SPAM"
        if gold == "spam" and spam:
            tp += 1
        elif gold == "spam":
            fp += 1
            missed.append(text)
        elif spam:
            fn += 1
            false_block.append((text, got))
        else:
            tn += 1
    n = tp + fp + tn + fn
    print(f"\n== {name}  n={n}")
    print(f"   spam recall   {tp / max(tp + fp, 1):.3f}   ({tp} of {tp + fp} ads filtered)")
    print(f"   ham kept      {tn / max(tn + fn, 1):.3f}   ({fn} important WRONGLY filtered)")
    print(f"   precision     {tp / max(tp + fn, 1):.3f}")
    if false_block:
        print(f"   -- {len(false_block)} wrongly filtered (sample):")
        for t, g in false_block[:6]:
            print(f"      {t[:70]}")
    if missed[:3]:
        print(f"   -- ads that leaked (sample):")
        for t in missed[:3]:
            print(f"      {t[:70]}")
    return fn


if __name__ == "__main__":
    bad = 0
    bad += run("Gheychi (5707)", gheychi())
    bad += run("PSSC (4324)", pssc())
    print(f"\ntotal important messages wrongly hidden: {bad}")
