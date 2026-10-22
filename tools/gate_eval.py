#!/usr/bin/env python3
"""Filter-power gate: fails (exit 1) if any change weakens ad filtering or hides important SMS.

Numbers come from the same mirror the app uses, so a regression here is a regression on the phone.
Thresholds are pinned just under the last measured values; raise them only after measuring better.

  python3 tools/gate_eval.py          # after any rules/model change
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from eval_real import gheychi, pssc  # noqa: E402
from mirror import classify  # noqa: E402

# name -> (min spam recall, min important kept)
THRESHOLDS = {
    "Gheychi": (0.85, 0.96),
    "PSSC": (0.85, 0.96),
    "my_inbox": (0.95, 0.92),
}


def measure(rows):
    tp = fp = tn = fn = 0
    for text, gold in rows:
        label = (classify(text) or {}).get("label")
        spam_gold = str(gold).strip().lower() in ("spam", "1", "true", "ad", "ads")
        spam_got = label == "SPAM"
        if spam_gold and spam_got:
            tp += 1
        elif spam_gold:
            fp += 1
        elif spam_got:
            fn += 1
        else:
            tn += 1
    spam_recall = tp / max(tp + fp, 1)
    kept = tn / max(tn + fn, 1)
    return spam_recall, kept, fn


def my_inbox():
    path = HERE.parent / "data" / "labeled.jsonl"
    if not path.exists():
        return None
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        yield row["text"], row.get("label", "spam")


def main():
    corpora = [("Gheychi", gheychi()), ("PSSC", pssc())]
    mine = my_inbox()
    if mine is not None:
        corpora.append(("my_inbox", mine))

    failures = []
    for name, rows in corpora:
        spam_recall, kept, hidden = measure(rows)
        min_recall, min_kept = THRESHOLDS.get(name, (0.0, 0.0))
        ok = spam_recall >= min_recall and kept >= min_kept
        print(
            f"{'PASS' if ok else 'FAIL'}  {name:9s} spam recall {spam_recall:.3f} "
            f"(min {min_recall:.2f})  important kept {kept:.3f} (min {min_kept:.2f})  "
            f"wrongly hidden {hidden}"
        )
        if not ok:
            failures.append(name)

    if failures:
        print(f"\nREGRESSION in: {', '.join(failures)}")
        return 1
    print("\nfilter power unchanged or better")
    return 0


if __name__ == "__main__":
    sys.exit(main())
