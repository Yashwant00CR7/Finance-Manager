"""Build the real (scrubbed) dataset from extracted raw records.

    python -m smsml.dataset --raw work/raw.jsonl --repo .. --out data/real.jsonl

Each output line:

    {"id", "origin": "real", "source", "bank", "sender", "text", "type",
     "gold": bool, "spans": [{"start", "end", "label", "by"}], "signature"}

`gold` is true when the source asserted what the message means: expected values for a
transaction, or "must not parse" for a negative. Only gold messages are ever tested on.

Scrubbing replaces personal data with generated fakes and recomputes every span offset:
account digits, reference digits, payees, UPI IDs and e-mail addresses anywhere, names
after "Dear", 10-digit mobile-shaped numbers, any other token carrying five or more
digits (ticket, passport, application numbers), and the path part of links. Amounts,
balances, dates and times are kept: they carry the format the model must learn and do
not identify anyone on their own.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import re
from pathlib import Path

from smsml import label as L

FAKE_PEOPLE = [
    "RAHUL VERMA", "PRIYA NAIR", "ANKIT SHARMA", "SNEHA IYER", "VIKRAM SINGH", "MEERA PATEL",
    "ARJUN REDDY", "KAVYA MENON", "ROHAN DAS", "POOJA GUPTA", "SURESH KUMAR", "DIVYA RAO",
    "MANOJ JOSHI", "NEHA KAPOOR", "KARTHIK S", "AISHA KHAN", "SANJAY MEHTA", "LATA PILLAI",
    "DEEPAK YADAV", "FARHAN ALI", "GEETA BHAT", "HARISH N", "ISHA MALHOTRA", "JOSEPH THOMAS",
]
FAKE_MERCHANTS = [
    "SWIGGY", "ZOMATO", "AMAZON PAY", "FLIPKART", "BIGBASKET", "UBER INDIA", "OLA CABS",
    "RELIANCE RETAIL", "DMART", "APOLLO PHARMACY", "BOOKMYSHOW", "IRCTC", "MAKEMYTRIP",
    "MYNTRA", "NETFLIX", "AIRTEL", "JIO PREPAID", "TATA 1MG", "BLINKIT", "ZEPTO",
    "CAFE COFFEE DAY", "STARBUCKS", "SHELL PETROL", "HP PETROL PUMP", "MORE SUPERMARKET",
    "LENSKART", "DECATHLON", "NYKAA", "CROMA", "PVR CINEMAS", "DOMINOS PIZZA", "KFC",
]
FAKE_HANDLES = ["rahul.v", "priya92", "ankit.sharma", "sneha.iyer", "vikram007", "meera.p",
                "arjunr", "kavya.m", "rohan.das", "pooja.g", "shop.kirana", "paytmqr2810",
                "q123456789", "bharatpe.90", "gpay-112233", "merchant.store", "mystore01"]


def _stable_rng(*parts: str) -> random.Random:
    return random.Random(int(hashlib.sha256("|".join(parts).encode()).hexdigest()[:16], 16))


def _style(src: str, fake: str) -> str:
    if src.isupper():
        return fake.upper()
    if src.islower():
        return fake.lower()
    if src.istitle():
        return fake.title()
    return fake.title()


def fake_digits(s: str, rng: random.Random) -> str:
    return "".join(str(rng.randint(0, 9)) if c.isdigit() else c for c in s)


def fake_payee(src: str, rng: random.Random) -> str:
    vpa = re.fullmatch(r"([\w.\-]+)@([\w.]+)", src.strip())
    if vpa:
        return f"{rng.choice(FAKE_HANDLES)}@{vpa.group(2)}"
    words = src.split()
    pool = FAKE_PEOPLE if len(words) >= 2 and not re.search(r"\d|LTD|PVT|STORE|MART|PAY|BANK|INDIA|LIMITED", src, re.I) else FAKE_MERCHANTS
    return _style(src, rng.choice(pool))


def fake_ref(src: str, rng: random.Random) -> str:
    return fake_digits(src, rng)


DEAR_NAME = re.compile(r"\b(?:Dear|Hi|Hello)\s+(?!Customer|Cardholder|Card ?member|Sir|Madam|User|Member|Valued|Patron|Investor|Client|Applicant)(?![A-Z]{2,4}\b)(?P<n>[A-Z][A-Za-z.]+(?:\s(?!user|customer)[A-Z][A-Za-z.]+){0,2})")
EMAIL_OR_VPA = re.compile(r"\b[\w.\-]{2,}@[A-Za-z][\w.\-]*[A-Za-z]\b")
MOBILE = re.compile(r"(?<!\d)(?:\+?91[\s-]?)?[6-9]\d{9}(?!\d)")
DIGIT_HEAVY = re.compile(r"\b[A-Za-z0-9]*\d[A-Za-z0-9]*\b")
URL = re.compile(r"(https?://[^\s/]+/)(\S+)")


def scrub(rec: dict, rng: random.Random) -> dict:
    """Returns a copy with personal data replaced and span offsets moved to match."""
    text, spans = rec["text"], sorted(rec["spans"], key=lambda s: s["start"])
    edits: list[tuple[int, int, str]] = []  # (start, end, replacement)

    def free(a: int, b: int) -> bool:
        return not any(a < s["end"] and s["start"] < b for s in spans) and \
            not any(a < e[1] and e[0] < b for e in edits)

    for s in spans:
        seg = text[s["start"]:s["end"]]
        if s["label"] == "ACCOUNT":
            edits.append((s["start"], s["end"], fake_digits(seg, rng)))
        elif s["label"] == "REF_NO":
            edits.append((s["start"], s["end"], fake_ref(seg, rng)))
        elif s["label"] == "PAYEE":
            edits.append((s["start"], s["end"], fake_payee(seg, rng)))

    for rx, make in (
        (URL, None),
        (EMAIL_OR_VPA, lambda m: fake_payee(m.group(), rng)),
        (DEAR_NAME, None),
        (MOBILE, lambda m: fake_digits(m.group(), rng)),
    ):
        for m in rx.finditer(text):
            if rx is URL:
                a, b = m.start(2), m.end(2)
                rep = "".join(rng.choice("abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789") if c.isalnum() else c
                              for c in m.group(2))
            elif rx is DEAR_NAME:
                a, b = m.start("n"), m.end("n")
                rep = _style(m.group("n"), rng.choice(FAKE_PEOPLE).split()[0])
            else:
                a, b = m.start(), m.end()
                rep = make(m)
            if free(a, b):
                edits.append((a, b, rep))

    keep = {"AMOUNT", "BALANCE", "AVAIL_LIMIT", "DATE", "TIME"}
    for m in DIGIT_HEAVY.finditer(text):
        if sum(c.isdigit() for c in m.group()) < 5:
            continue
        if any(m.start() < s["end"] and s["start"] < m.end() and s["label"] in keep for s in spans):
            continue
        if free(m.start(), m.end()):
            edits.append((m.start(), m.end(), fake_digits(m.group(), rng)))

    edits.sort()
    new_spans: list[dict] = []
    # Rebuild the text, mapping every original offset to where it lands afterwards.
    pieces, mapping, cur, new_len = [], {}, 0, 0
    for a, b, rep in edits:
        for k in range(cur, a + 1):
            mapping[k] = new_len + (k - cur)
        pieces.append(text[cur:a])
        new_len += a - cur
        mapping[a] = new_len
        pieces.append(rep)
        new_len += len(rep)
        mapping[b] = new_len
        cur = b
    for k in range(cur, len(text) + 1):
        mapping[k] = new_len + (k - cur)
    pieces.append(text[cur:])
    new_text = "".join(pieces)
    for s in spans:
        new_spans.append({**s, "start": mapping[s["start"]], "end": mapping[s["end"]]})
    return {**rec, "text": new_text, "spans": new_spans}


def signature(text: str, spans: list[dict]) -> str:
    """The template a message was filled from: spans and digits masked out."""
    out, i = [], 0
    for s in sorted(spans, key=lambda s: s["start"]):
        out.append(text[i:s["start"]])
        out.append(f"<{s['label']}>")
        i = s["end"]
    out.append(text[i:])
    t = re.sub(r"\d+", "0", "".join(out))
    return re.sub(r"\s+", " ", t).strip().lower()


def build(raw: list[dict], registry: dict[str, str]) -> list[dict]:
    seen, out = set(), []
    for r in raw:
        if not L.is_indian(r) or not r["text"].strip():
            continue
        key = " ".join(r["text"].split())
        if key in seen:
            continue
        seen.add(key)
        spans, misses = L.label_spans(r)
        bank = L.bank_of(r, registry) or "NONBANK"
        rec = {
            "source": r["source"].split(":")[0],
            "bank": bank,
            "is_wallet": bank in L.WALLETS,
            "sender": r["sender"],
            "text": r["text"],
            "type": L.message_type(r, spans),
            "gold": r.get("should_parse") is False or (r.get("expect") is not None and not
                                                      ({"AMOUNT", "ACCOUNT"} & set(misses))),
            "spans": spans,
            "unplaced": misses,
        }
        out.append(rec)
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", type=Path, required=True)
    ap.add_argument("--repo", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()

    registry = L.load_header_registry(a.repo)
    raw = [json.loads(l) for l in a.raw.open(encoding="utf-8")]
    recs = build(raw, registry)
    with a.out.open("w", encoding="utf-8") as fh:
        for n, r in enumerate(recs):
            rng = _stable_rng(r["text"])
            s = scrub(r, rng)
            # Sender headers are public DLT registrations; only the core is kept anyway.
            s["sender"] = s["sender"].strip()
            s["id"] = f"real-{n:05d}"
            s["origin"] = "real"
            s["signature"] = signature(s["text"], s["spans"])
            fh.write(json.dumps(s, ensure_ascii=False) + "\n")
    from collections import Counter
    print(len(recs), "real messages;", sum(r["gold"] for r in recs), "gold;",
          len({r["bank"] for r in recs}), "banks")
    print(Counter(r["type"] for r in recs).most_common())


if __name__ == "__main__":
    main()
