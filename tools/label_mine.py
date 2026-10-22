#!/usr/bin/env python3
"""Turn the user's own inbox into a labelled evaluation set, without asking him to label 11k messages.

Labels come from two sources that do NOT depend on the model, so evaluating the model against
them is not circular:
  IMPORTANT <- hard guard: OTP wording, transaction confirmations, government/service templates
  SPAM      <- strong promotional boilerplate (لغو11, تخفیف, حراج, وام بدون ضامن, ...)
Everything else is skipped rather than guessed.

  python3 tools/label_mine.py            # writes data/labeled.jsonl + prints model metrics
"""
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from mirror import classify, normalize, RULES  # noqa: E402

ROOT = HERE.parent
DATA = ROOT / "data"

# boilerplate that occurs in ads and essentially never in a bank/government notice
SPAM_MARKERS = [
    "لغو11", "لغو 11", "لغو۱۱", "کد تخفیف", "تخفیف", "حراج", "فروش ویژه", "فروش فوق",
    "پیشنهاد ویژه", "ارسال رایگان", "قرعه کشی", "قرعه‌کشی", "برنده جایزه", "بدون ضامن",
    "تسهیلات", "شرط بندی", "شرط‌بندی", "کازینو", "لاغری", "کاهش وزن", "انبارتکانی",
    "آخر فصل", "کلیک کنید", "ثبت نام کنید", "همین حالا سفارش", "کد هدیه", "گردونه شانس",
]
HARD_GROUPS = ("otp", "gov", "transaction_confirm")


def label_of(text: str):
    norm = normalize(text)
    sq = norm.replace(" ", "")
    for g in HARD_GROUPS:
        for t in RULES["guard"].get(g, []):
            tn = normalize(t).replace(" ", "")
            if tn and tn in sq:
                return "IMPORTANT"
    hits = sum(1 for w in SPAM_MARKERS if normalize(w).replace(" ", "") in sq)
    if hits >= 2:
        return "SPAM"
    # single very distinctive marker is enough for these
    if "لغو11" in sq or "کد تخفیف" in sq:
        return "SPAM"
    return None


def main():
    rows = [json.loads(l) for l in (DATA / "sms.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
    labelled = []
    for r in rows:
        lab = label_of(r["body"])
        if lab and len(r["body"]) > 15:
            labelled.append({"text": r["body"], "sender": r["address"], "label": lab})
    (DATA / "labeled.jsonl").write_text(
        "\n".join(json.dumps(x, ensure_ascii=False) for x in labelled), encoding="utf-8")

    n_spam = sum(1 for x in labelled if x["label"] == "SPAM")
    print(f"labelled {len(labelled)} of {len(rows)} messages  (spam={n_spam}, important={len(labelled) - n_spam})")

    tp = fp = tn = fn = 0
    false_block, leaked = [], []
    for x in labelled:
        got = classify(x["text"], x["sender"])["label"]
        spam = got == "SPAM"
        if x["label"] == "SPAM":
            if spam:
                tp += 1
            else:
                fp += 1
                leaked.append(x["text"])
        else:
            if spam:
                fn += 1
                false_block.append(x["text"])
            else:
                tn += 1
    n = len(labelled)
    print(f"\non the user's own inbox:")
    print(f"  accuracy            {(tp + tn) / n:.3f}")
    print(f"  ads filtered        {tp / max(tp + fp, 1):.3f}  ({tp}/{tp + fp})")
    print(f"  important kept      {tn / max(tn + fn, 1):.3f}  ({fn} wrongly filtered)")
    if false_block:
        print("\n  wrongly filtered (should be protected):")
        for t in false_block[:8]:
            print("   ", t[:90].replace("\n", " "))
    if leaked:
        print("\n  ads that leaked (sample):")
        for t in leaked[:5]:
            print("   ", t[:90].replace("\n", " "))
    return 0


if __name__ == "__main__":
    sys.exit(main())
