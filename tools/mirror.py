#!/usr/bin/env python3
"""Python mirror of the Android classifier. Same rules.json, same algorithm.

Why: tuning rules on the phone costs a rebuild + emulator boot (~40 s). Here it's <100 ms.
Run:  python3 tools/mirror.py [golden.jsonl]
Exit code 1 if any IMPORTANT message got classified SPAM (the only unacceptable error).
"""
import base64
import json
import math
import re
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RULES = json.loads((ROOT / "rules" / "rules.json").read_text(encoding="utf-8"))
MODEL_PATH = ROOT / "app" / "src" / "main" / "assets" / "model.json"

# ---------- normalization (must match Normalizer.kt) ----------
CHAR_MAP = str.maketrans({
    "ي": "ی", "ى": "ی", "ك": "ک", "ۀ": "ه", "أ": "ا", "إ": "ا", "آ": "ا", "ؤ": "و", "ة": "ه", "ئ": "ی",
    "\u200c": " ", "\u200d": " ", "\u200e": "", "\u200f": "", "\u0640": "",
})
DIGITS = str.maketrans("۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩", "01234567890123456789")
DIACRITICS = re.compile("[\u064B-\u0652\u0670\u0653-\u0655]")
SPACE = re.compile(r"\s+")
REPEAT_PUNCT = re.compile(r"([!?؟.,،*\-_=])\1{2,}")
EMOJI = re.compile("[\U0001F300-\U0001FAFF\u2600-\u27BF\u2B00-\u2BFF\uFE0F]")
URL = re.compile(r"(?:https?://|www\.)[^\s]+|(?:[a-z0-9-]+\.)+(?:ir|com|net|org|me|io|co|info|shop|site|online|xyz)\b(?:/[^\s]*)?")


def normalize(text: str) -> str:
    t = text.translate(CHAR_MAP).translate(DIGITS)
    t = DIACRITICS.sub("", t)
    t = t.lower()
    t = REPEAT_PUNCT.sub(r"\1", t)
    return SPACE.sub(" ", t).strip()


# ---------- model (same weights the app ships) ----------
def _fnv1a(b: bytes) -> int:
    h = 2166136261
    for ch in b:
        h = ((h ^ ch) * 16777619) & 0xFFFFFFFF
    return h


class Model:
    def __init__(self, path: Path = MODEL_PATH):
        j = json.loads(path.read_text(encoding="utf-8"))
        self.m = j["m"]; self.nmin = j["ngram_min"]; self.nmax = j["ngram_max"]
        self.scale = j["scale"]; self.bias = j["bias"]
        raw = base64.b64decode(j["weights_b64"])
        if j.get("dtype") == "int16le":
            self.w = list(struct.unpack("<%dh" % j["m"], raw))
        else:  # legacy int8 export
            self.w = [x - 128 for x in raw]

    def prob(self, text: str) -> float:
        s = normalize(text).replace(" ", "")
        if not s:
            return 0.0
        z, n = 0.0, 0
        for size in range(self.nmin, self.nmax + 1):
            for i in range(len(s) - size + 1):
                z += self.w[_fnv1a(s[i:i + size].encode("utf-8")) & (self.m - 1)] * self.scale
                n += 1
        if not n:
            return 0.0
        z = z / math.sqrt(n) + self.bias
        return 1 / (1 + math.exp(-max(-30, min(30, z))))


MODEL = Model() if MODEL_PATH.exists() else None


# ---------- classification ----------
def classify(text: str, sender: str = "", contacts: set[str] | None = None) -> dict:
    contacts = contacts or set()
    norm = normalize(text)
    squash = norm.replace(" ", "")
    why: list[str] = []

    # stage 1: sender rules
    s_low = normalize(sender)
    for b in RULES["senders"]["block_exact"]:
        if normalize(b) == s_low:
            return {"label": "SPAM", "score": 99, "why": [f"block:{sender}"]}
    for a in RULES["senders"]["allow_exact"]:
        if normalize(a) == s_low:
            return {"label": "IMPORTANT", "score": 0, "why": [f"allow:{sender}"]}
    for a in RULES["senders"]["allow_contains"]:
        if normalize(a) in s_low:
            return {"label": "IMPORTANT", "score": 0, "why": [f"allow~:{a}"]}
    def _suffix(num: str):
        d = "".join(ch for ch in normalize(num) if ch.isdigit())
        return d[-9:] if len(d) >= 9 else None

    s_suf = _suffix(sender)
    if s_suf and s_suf in {_suffix(c) for c in contacts}:
        return {"label": "IMPORTANT", "score": 0, "why": ["contact"], "norm": norm}

    # stage 2: content score (each normalized term counted once, per group capped)
    score = 0
    for group, spec in RULES["spam"].items():
        if group.startswith("_"):
            continue
        seen, hits = set(), 0
        for term in spec["terms"]:
            tn = normalize(term).replace(" ", "")
            if tn and tn in squash and tn not in seen and hits < 2:
                seen.add(tn)
                hits += 1
                score += spec["weight"]
                why.append(term)

    st = RULES["structure"]
    for m in set(URL.findall(norm)):
        score += st["shortlink_weight"] if any(h in m for h in st["shortlink_hosts"]) else st["url_weight"]
        why.append(f"url:{m[:30]}")

    n_emoji = len(EMOJI.findall(text))
    if n_emoji >= 5:
        score += st["emoji_ge5_weight"]
        why.append(f"emoji:{n_emoji}")
    elif n_emoji >= 2:
        score += 1
        why.append(f"emoji:{n_emoji}")

    lone = len(re.findall(r"(?<!\S)[آ-یa-z](?!\S)", norm))
    if lone >= 3:
        score += st["spaced_letters_weight"]
        why.append(f"spaced_letters:{lone}")

    score += st["unknown_sender_weight"]

    # stage 3: guard. hard groups always protect; soft groups protect only when the model is
    # not confident (p < soft_guard_conf) - measured: unconditional soft guard leaked 27pp of ads.
    has_amount = re.search(RULES["thresholds"]["amount_regex"], norm) is not None
    hit = None
    hard_groups = RULES.get("guard_hard", [])
    ordered = sorted(RULES["guard"].items(), key=lambda kv: kv[0] in hard_groups, reverse=True)
    for group, terms in ordered:
        if group.startswith("_"):
            continue
        if group == "transaction" and not has_amount:
            continue
        for term in terms:
            tn = normalize(term).replace(" ", "")
            if tn and tn in squash:
                hit = f"guard:{group}:{term}"
                break
        if hit:
            break
    hard = hard_groups
    soft_conf = RULES["model"]["soft_guard_conf"]
    p_early = MODEL.prob(text) if MODEL is not None else 1.0
    hard_hit = hit is not None and hit.split(":")[1] in hard
    has_url = bool(URL.search(norm))
    # emoji-stuffed promo needs a second signal and must never touch a guarded message
    if not hard_hit and hit is None and n_emoji >= 2 and (has_url or score >= RULES["thresholds"]["unsure"]):
        return {"label": "SPAM", "score": 70, "p": 1.0, "why": [f"emoji:{n_emoji}"] + why, "norm": norm}
    if hit and (hard_hit or p_early < soft_conf):
        return {"label": "IMPORTANT", "score": score, "p": p_early, "why": [hit] + why, "norm": norm}

    # "this message exists because the user did something": order/delivery/payment/booking notices.
    # Same shops send both, so the words alone are not enough - an ad carries a link, the CRA "لغو"
    # opt-out line or several emoji; a real notice does not.
    ad_shaped = has_url or n_emoji >= 2 or any(w.startswith("لغو") for w in why)
    if not ad_shaped:
        for term in RULES.get("transactional", {}).get("terms", []):
            tn = normalize(term).replace(" ", "")
            if tn and tn in squash:
                return {"label": "IMPORTANT", "score": score, "p": p_early,
                        "why": [f"txn:{term}"] + why, "norm": norm}

    mp = RULES["model"]
    if MODEL is not None:
        p = MODEL.prob(text)
        label = "SPAM" if p >= mp["spam_p"] else ("UNSURE" if p >= mp["unsure_p"] else "IMPORTANT")
        why.append(f"p={p:.3f}")
    else:
        th = RULES["thresholds"]
        p = 0.0
        label = "SPAM" if score >= th["spam"] else ("UNSURE" if score >= th["unsure"] else "IMPORTANT")
    return {"label": label, "score": score, "p": p, "why": why, "norm": norm}


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    path = Path(args[0]) if args else ROOT / "tests" / "golden.jsonl"
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    tp = fp = tn = fn = 0
    bad: list[dict] = []
    for r in rows:
        got = classify(r["text"], r.get("sender", ""), set(r.get("contacts", [])))
        exp = r["label"]
        g = got["label"]
        spam_got = g == "SPAM"
        spam_exp = exp == "SPAM"
        if spam_exp and spam_got:
            tp += 1
        elif not spam_exp and spam_got:
            fn += 1  # important message swallowed = the fatal error
            bad.append({**r, "got": got})
        elif spam_exp:
            fp += 1
        else:
            tn += 1
        if "-v" in sys.argv:
            print(f"{exp:9s} -> {g:9s} {got['score']:2d}  {r['text'][:70]}")
            print(f"          why={got['why']}")
    n = len(rows) or 1
    print(f"\nn={len(rows)}  acc={(tp + tn) / n:.3f}  spamP={tp / max(tp + fp, 1):.3f} "
          f"spamR={tp / max(tp + fn, 1):.3f}  important_kept={(tn + fp) / max(tn + fp + fn, 1):.3f}")
    if bad:
        print(f"\nFATAL: {len(bad)} important message(s) classified SPAM:")
        for b in bad:
            print(f"  [{b['label']}] {b['text'][:90]}  why={b['got']['why']}")
        return 1
    print("no important message was filtered")
    return 0


if __name__ == "__main__":
    sys.exit(main())
