#!/usr/bin/env python3
"""Teacher -> student distillation for the SMS model, in the spirit of "train a strong core
first, then let a light model use it".

  teacher : char 2-6 grams + word uni/bi-grams, 1M buckets, trained on every labelled source
            (his own 761 labels at 3x weight, template labels, Gheychi, PSSC)
  student : exactly the app's feature space (char 2-5, 131072 buckets, int16 export), trained on
            the labelled sources PLUS the teacher's soft predictions over his 11.9k raw messages

Honest evaluation: 20 % of his own labels are held out from BOTH models and never trained on.

  python3 tools/distill.py            # prints metrics, writes tools/model_distilled.json
"""
import base64
import json
import math
import random
import struct
import sys
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import train as T                      # noqa: E402  (grams, train, metrics, load, normalize)
import mirror                          # noqa: E402

DATA = HERE.parent / "data"
OUT = HERE / "model_distilled.json"
SEED = 7
TEACHER_M = 1 << 20
TEACHER_NG = (2, 6)


def fnv(text: str) -> int:
    return T.fnv1a(text.encode("utf-8"))


def teacher_grams(text: str):
    s = T.normalize(text).replace(" ", "")
    out = Counter()
    for n in range(TEACHER_NG[0], TEACHER_NG[1] + 1):
        for i in range(len(s) - n + 1):
            out[fnv("c" + s[i:i + n]) & (TEACHER_M - 1)] += 1
    # word unigrams + bigrams: a linear model over whole words generalises differently than chars
    words = T.normalize(text).split()
    for w in words:
        out[fnv("w" + w) & (TEACHER_M - 1)] += 1
    for a, b in zip(words, words[1:]):
        out[fnv("w" + a + "_" + b) & (TEACHER_M - 1)] += 1
    return out


def sgd(rows, grams, m, epochs=12, lr=0.35):
    w = [0.0] * m
    b = 0.0
    rnd = random.Random(SEED)
    order = list(range(len(rows)))
    for ep in range(epochs):
        rnd.shuffle(order)
        rate = lr / (1 + ep * 0.4)
        for i in order:
            text, y, weight = rows[i]
            g = grams(text)
            if not g:
                continue
            n = sum(g.values())
            z = b + sum(w[k] * c for k, c in g.items()) / math.sqrt(n)
            p = 1 / (1 + math.exp(-max(-30, min(30, z))))
            err = (p - y) * weight
            for k, c in g.items():
                w[k] -= rate * err * c / math.sqrt(n)
            b -= rate * err
    return w, b


def predict(w, b, text, grams):
    g = grams(text)
    if not g:
        return 0.0
    n = sum(g.values())
    z = b + sum(w[k] * c for k, c in g.items()) / math.sqrt(n)
    return 1 / (1 + math.exp(-max(-30, min(30, z))))


def metrics_on(rows, fn, thr=0.5):
    tp = fp = tn = fn_ = 0
    for text, y in rows:
        p = fn(text)
        if p >= thr:
            tp += 1 if y == 1 else 0
            fp += 0 if y == 1 else 1
        else:
            tn += 1 if y == 0 else 0
            fn_ += 0 if y == 0 else 1
    n = max(tp + fp + tn + fn_, 1)
    return dict(acc=(tp + tn) / n, spam_r=tp / max(tp + fp, 1),
                important_kept=tn / max(tn + fn_, 1), wrongly_filtered=fn_, n=n)


def main():
    user = []
    for line in (DATA / "user_labels.jsonl").read_text(encoding="utf-8").splitlines():
        if line.strip():
            d = json.loads(line)
            user.append((d["text"], 1 if d["label"] == "SPAM" else 0))
    rnd = random.Random(SEED)
    spam = [r for r in user if r[1] == 1]
    ham = [r for r in user if r[1] == 0]
    rnd.shuffle(spam)
    rnd.shuffle(ham)
    hold = spam[: max(1, int(len(spam) * 0.2))] + ham[: max(1, int(len(ham) * 0.2))]
    keep = spam[max(1, int(len(spam) * 0.2)):] + ham[max(1, int(len(ham) * 0.2)):]
    print(f"his labels: {len(user)} (spam {len(spam)}, ham {len(ham)}) "
          f"-> train {len(keep)}, frozen holdout {len(hold)}")

    # labelled sources; his train split weighted 3x
    labelled = T.load("both")                       # template labels + corpora + user_labels (3x)
    mine_texts = {t for t, _ in user}
    labelled = [r for r in labelled if r[0] not in {t for t, _ in hold}]
    labelled += [(t, y, "mine") for t, y in keep for _ in range(2)]   # extra weight on his verdicts

    raw = []
    for line in (DATA / "sms.jsonl").read_text(encoding="utf-8").splitlines():
        if line.strip():
            d = json.loads(line)
            body = d.get("body") or d.get("text") or ""
            if body.strip():
                raw.append(body)
    print(f"labelled corpus: {len(labelled)}   raw inbox for distillation: {len(raw)}")

    print("training teacher (char2-6 + words, 1M buckets)...")
    tw, tb = sgd([(r[0], r[1], 3 if r[2] == "mine" else 1) for r in labelled], teacher_grams, TEACHER_M)

    print("labelling the raw inbox with the teacher...")
    soft = [(text, predict(tw, tb, text, teacher_grams), 1) for text in raw]

    print("training student (app feature space, char2-5)...")
    student_rows = [(r[0], r[1], 3 if r[2] == "mine" else 1) for r in labelled]
    student_rows += [(text, p, 0.5) for text, p, _ in soft]      # soft labels, half weight
    sw, sb = T.train(student_rows)

    print("training student WITHOUT distillation (control)...")
    cw, cb = T.train([(r[0], r[1], 3 if r[2] == "mine" else 1) for r in labelled])

    res = {
        "current shipped model": metrics_on(hold, lambda t: (mirror.classify(t) or {}).get("p", 0.0)),
        "teacher": metrics_on(hold, lambda t: predict(tw, tb, t, teacher_grams)),
        "student (distilled)": metrics_on(hold, lambda t: T.scores(sw, sb, [(t, 0, "")])[0][0]),
        "student (no distillation)": metrics_on(hold, lambda t: T.scores(cw, cb, [(t, 0, "")])[0][0]),
    }
    print("\nfrozen holdout of his own labels:")
    for name, m in res.items():
        print(f"  {name:28} acc={m['acc']:.3f} spamR={m['spam_r']:.3f} "
              f"important_kept={m['important_kept']:.3f} (wrongly filtered {m['wrongly_filtered']}/{m['n']})")

    best = res["student (distilled)"]
    baseline = res["current shipped model"]
    if best["spam_r"] >= baseline["spam_r"] - 0.01 and best["important_kept"] >= baseline["important_kept"] - 0.01:
        mx = max(abs(x) for x in sw) or 1.0
        scale = mx / 32767
        q = struct.pack("<%dh" % T.M, *(max(-32767, min(32767, round(x / scale))) for x in sw))
        OUT.write_text(json.dumps({
            "m": T.M, "ngram_min": T.NGRAM_MIN, "ngram_max": T.NGRAM_MAX,
            "scale": scale, "bias": sb, "dtype": "int16le",
            "weights_b64": base64.b64encode(q).decode(),
        }), encoding="utf-8")
        print(f"\nstudent is not worse than the shipped model on his labels -> wrote {OUT}")
    else:
        print("\nstudent did not beat the shipped model on his labels; nothing written")


if __name__ == "__main__":
    main()
