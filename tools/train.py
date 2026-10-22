#!/usr/bin/env python3
"""Train the char n-gram model and export it for the app.

Why a model: hand-written keyword rules scored spam recall 0.043 on 5,707 real Persian
messages (tools/eval_real.py). Rules cannot generalize; char n-grams can. Published
baselines on these same corpora: 0.90-0.98 acc. So the model does the filtering and the
rules demote to a guard that protects important messages.

No deps: pure-python logistic regression (SGD) over hashed char 2-5 grams.
Feature extraction here is byte-for-byte the same as Model.kt (FNV-1a 32bit over UTF-8).

  python3 tools/train.py            # train both corpora, write assets/model.json, print metrics
"""
import json
import math
import random
import sqlite3
import sys
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from mirror import normalize  # noqa: E402

DATA = Path("~/research/persian_sms")
OUT = HERE.parent / "app" / "src" / "main" / "assets" / "model.json"

M = 1 << 17        # hash buckets
NGRAM_MIN, NGRAM_MAX = 2, 5
SEED = 7


def fnv1a(b: bytes) -> int:
    h = 2166136261
    for ch in b:
        h = ((h ^ ch) * 16777619) & 0xFFFFFFFF
    return h


def grams(text: str):
    """char n-grams over whitespace-free normalized text (kills space-injection evasion)."""
    s = normalize(text).replace(" ", "")
    out = Counter()
    for n in range(NGRAM_MIN, NGRAM_MAX + 1):
        for i in range(len(s) - n + 1):
            out[fnv1a(s[i:i + n].encode("utf-8")) & (M - 1)] += 1
    return out


_GRAM_CACHE: dict[str, Counter] = {}


def grams_cached(text: str) -> Counter:
    g = _GRAM_CACHE.get(text)
    if g is None:
        g = _GRAM_CACHE[text] = grams(text)
    return g


def load(corpus: str):
    rows = []
    if corpus in ("gheychi", "both"):
        for r in json.loads((DATA / "gheychi.json").read_text(encoding="utf-8")):
            rows.append((r["Text"], 1 if r["Label"] == 1 else 0, "gheychi"))
    mine = HERE.parent / "data" / "labeled.jsonl"
    if corpus in ("mine", "both") and mine.exists():
        for line in mine.read_text(encoding="utf-8").splitlines():
            if line.strip():
                r = json.loads(line)
                rows.append((r["text"], 1 if r["label"] == "SPAM" else 0, "mine"))
    if corpus in ("pssc", "both"):
        c = sqlite3.connect(DATA / "pssc.sqlite3")
        for text, label in c.execute("SELECT text, label FROM SMS"):
            rows.append((text, 1 if label == "spam" else 0, "pssc"))
    return rows


def train(rows, epochs=12, lr=0.35, l2=1e-6):
    w = [0.0] * M
    b = 0.0
    rnd = random.Random(SEED)
    order = list(range(len(rows)))
    for ep in range(epochs):
        rnd.shuffle(order)
        rate = lr / (1 + ep * 0.4)
        for i in order:
            text, y, _ = rows[i]
            g = grams_cached(text)
            if not g:
                continue
            z = b + sum(w[k] * c for k, c in g.items()) / math.sqrt(sum(g.values()))
            p = 1 / (1 + math.exp(-max(-30, min(30, z))))
            err = p - y
            scale = math.sqrt(sum(g.values()))
            for k, c in g.items():
                w[k] -= rate * (err * c / scale + l2 * w[k])
            b -= rate * err
    return w, b


def scores(w, b, rows):
    out = []
    for text, y, _ in rows:
        g = grams_cached(text)
        z = b + (sum(w[k] * c for k, c in g.items()) / math.sqrt(sum(g.values())) if g else 0)
        out.append((1 / (1 + math.exp(-max(-30, min(30, z)))), y))
    return out


def metrics(pairs, thr=0.5):
    tp = sum(1 for p, y in pairs if p >= thr and y == 1)
    fp = sum(1 for p, y in pairs if p >= thr and y == 0)
    tn = sum(1 for p, y in pairs if p < thr and y == 0)
    fn = sum(1 for p, y in pairs if p < thr and y == 1)
    n = len(pairs)
    return dict(acc=(tp + tn) / n, spam_r=tp / max(tp + fp, 1), important_kept=tn / max(tn + fn, 1),
                tp=tp, fp=fp, tn=tn, fn=fn)


def main():
    rows = load("both")
    print(f"corpus: {len(rows)} messages, {sum(r[1] for r in rows)} spam")

    # 5-fold CV, split inside each corpus so domains are both represented
    rnd = random.Random(SEED)
    by_corpus = {}
    for r in rows:
        by_corpus.setdefault(r[2], []).append(r)
    folds = [[], [], [], [], []]
    for corpus_rows in by_corpus.values():
        rnd.shuffle(corpus_rows)
        for i, r in enumerate(corpus_rows):
            folds[i % 5].append(r)
    print("\n5-fold CV:")
    agg = Counter()
    for i in range(5):
        test = folds[i]
        tr = [r for j, f in enumerate(folds) if j != i for r in f]
        w, b = train(tr)
        m = metrics(scores(w, b, test))
        print(f"  fold{i} acc={m['acc']:.3f} spamR={m['spam_r']:.3f} important_kept={m['important_kept']:.3f}"
              f"  (wrongly filtered {m['fn']})")
        agg.update({k: m[k] for k in ("tp", "fp", "tn", "fn")})
    tot = agg["tp"] + agg["fp"] + agg["tn"] + agg["fn"]
    # his own inbox, out-of-fold
    mine_pairs = []
    for i in range(5):
        test_mine = [r for r in folds[i] if r[2] == "mine"]
        if not test_mine:
            continue
        tr = [r for j, f in enumerate(folds) if j != i for r in f]
        w2, b2 = train(tr)
        mine_pairs += scores(w2, b2, test_mine)
    if mine_pairs:
        m = metrics(mine_pairs)
        print(f"\n  his inbox (out-of-fold): acc={m['acc']:.3f} spamR={m['spam_r']:.3f} "
              f"important_kept={m['important_kept']:.3f} (wrongly filtered {m['fn']})")
    print(f"  pooled acc={(agg['tp'] + agg['tn']) / tot:.3f} spamR={agg['tp'] / max(agg['tp'] + agg['fp'], 1):.3f} "
          f"important_kept={agg['tn'] / max(agg['tn'] + agg['fn'], 1):.3f}")

    # threshold sweep: the app's asymmetric cost means we prefer fewer FPs on ham
    w, b = train(rows)
    sc = scores(w, b, rows)
    print("\nthreshold sweep (whole corpus, in-sample):")
    for thr in (0.5, 0.7, 0.8, 0.9, 0.95):
        m = metrics(sc, thr)
        print(f"  p>={thr:.2f}  spamR={m['spam_r']:.3f} important_kept={m['important_kept']:.3f} acc={m['acc']:.3f}")

    # export: int8 weights + scale + bias, plus the exact config the Kotlin side needs
    mx = max(abs(x) for x in w) or 1.0
    scale = mx / 32767
    import base64
    import struct
    q = struct.pack("<%dh" % M, *(max(-32767, min(32767, round(x / scale))) for x in w))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({
        "m": M, "ngram_min": NGRAM_MIN, "ngram_max": NGRAM_MAX,
        "scale": scale, "bias": b, "dtype": "int16le", "weights_b64": base64.b64encode(q).decode(),
    }), encoding="utf-8")
    print(f"\nwrote {OUT} ({OUT.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
