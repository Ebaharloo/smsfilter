#!/usr/bin/env python3
"""Where do the errors actually come from: the guard or the model?

Runs three pipelines over a frozen holdout of the user's own labels:
  guard+model   what the phone ships today
  model only    no guard, p >= 0.5
  guard+model with the distilled weights

Prints a proper confusion matrix (spam recall, important kept) plus the guard reason behind every
ad that the guard wrongly protected, so the tuning is driven by data instead of guessing.
"""
import json
import random
import sys
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import mirror  # noqa: E402

DATA = HERE.parent / "data"
SEED = 7


def main():
    user = []
    for line in (DATA / "user_labels.jsonl").read_text(encoding="utf-8").splitlines():
        if line.strip():
            d = json.loads(line)
            user.append((d["text"], 1 if d["label"] == "SPAM" else 0))
    spam = [r for r in user if r[1] == 1]
    ham = [r for r in user if r[1] == 0]
    rnd = random.Random(SEED)
    rnd.shuffle(spam)
    rnd.shuffle(ham)
    hold = spam[: max(1, int(len(spam) * 0.2))] + ham[: max(1, int(len(ham) * 0.2))]
    print(f"holdout: {len(hold)} ({sum(y for _, y in hold)} ads, {sum(1 - y for _, y in hold)} not ads)")

    rows = []
    for text, y in hold:
        v = mirror.classify(text) or {}
        rows.append((text, y, v.get("label", "?"), v.get("p", 0.0), ",".join(v.get("why", []) or [])))

    def report(name, pred):
        tp = sum(1 for r in rows if r[1] == 1 and pred(r))
        fp = sum(1 for r in rows if r[1] == 0 and pred(r))
        tn = sum(1 for r in rows if r[1] == 0 and not pred(r))
        fn = sum(1 for r in rows if r[1] == 1 and not pred(r))
        print(f"  {name:22} ads caught {tp}/{tp + fn} ({tp / max(tp + fn, 1):.3f})   "
              f"not-ads kept {tn}/{tn + fp} ({tn / max(tn + fp, 1):.3f})   acc {(tp + tn) / len(rows):.3f}")

    print("\nthree pipelines on the same holdout:")
    report("guard+model (shipped)", lambda r: r[2] == "SPAM")
    report("model only p>=0.5", lambda r: r[3] >= 0.5)
    report("model only p>=0.3", lambda r: r[3] >= 0.3)

    missed = [r for r in rows if r[1] == 1 and r[2] != "SPAM"]
    print(f"\nads the shipped pipeline let through: {len(missed)}")
    why = Counter()
    for r in missed:
        v = mirror.classify(r[0]) or {}
        reasons = [w for w in (v.get("why") or []) if w.startswith(("guard", "allow", "learned", "contact"))]
        why[",".join(reasons) or "model-low-score"] += 1
    for k, n in why.most_common(12):
        print(f"  {n:4}  {k}")
    model_low = [r for r in missed if r[3] < 0.5]
    print(f"  of those, {len(model_low)} had p<0.5 (model's fault), "
          f"{len(missed) - len(model_low)} had p>=0.5 (guard overrode the model)")


if __name__ == "__main__":
    main()
