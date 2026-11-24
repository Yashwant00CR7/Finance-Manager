"""CRF span tagger and message-type classifier.

Tokens are the smallest pieces a bank SMS is built from: a number (with its own commas
and decimal point), a run of letters, or one punctuation character. "Rs.1,499" is three
tokens, "XX1234" two, "24JUN2026" three. Spans map onto tokens as BIO tags.

The message type is a separate, plain text classifier (logistic regression over word and
character n-grams). A CRF tags positions in a sequence; one label per message is not a
sequence problem.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

import sklearn_crfsuite
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import FeatureUnion, Pipeline

TOKEN_RE = re.compile(r"\d+(?:,\d{2,3})*(?:\.\d+)?|[A-Za-z]+|[^\sA-Za-z\d]")
MONTHS = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
          "january", "february", "march", "april", "june", "july", "august", "september",
          "october", "november", "december"}
CURRENCY = {"rs", "inr", "₹", "rupees", "usd", "eur", "gbp", "aed", "$"}
CUES = {
    "bal": "BAL", "balance": "BAL", "avl": "AVL", "avbl": "AVL", "available": "AVL", "avail": "AVL",
    "limit": "LIM", "lmt": "LIM", "ref": "REF", "rrn": "REF", "utr": "REF", "upi": "UPI",
    "imps": "REF", "neft": "REF", "txn": "REF", "a": "AC", "c": "AC", "ac": "AC", "acct": "AC",
    "account": "AC", "card": "CARD", "ending": "CARD", "to": "TO", "at": "AT", "from": "FROM",
    "by": "BY", "on": "ON", "vpa": "VPA", "info": "INFO", "towards": "TO", "debited": "DR",
    "credited": "CR", "spent": "DR", "withdrawn": "DR", "sent": "DR", "received": "CR",
}


@dataclass
class Tok:
    text: str
    start: int
    end: int


def tokenize(text: str) -> list[Tok]:
    return [Tok(m.group(), m.start(), m.end()) for m in TOKEN_RE.finditer(text)]


def shape(t: str) -> str:
    s = re.sub(r"[A-Z]", "X", t)
    s = re.sub(r"[a-z]", "x", s)
    s = re.sub(r"\d", "d", s)
    return re.sub(r"(.)\1{2,}", r"\1\1", s)


def _tok_feats(tok: str, prefix: str) -> dict:
    low = tok.lower()
    return {
        f"{prefix}low": low if len(low) < 15 else low[:3] + "~",
        f"{prefix}shape": shape(tok),
        f"{prefix}cue": CUES.get(low, ""),
        f"{prefix}cur": low in CURRENCY,
        f"{prefix}month": low in MONTHS,
    }


def token_features(toks: list[Tok], text: str) -> list[dict]:
    feats = []
    n = len(toks)
    last_cur = -99
    for i, t in enumerate(toks):
        w = t.text
        low = w.lower()
        f = {
            "bias": 1.0,
            "low": low if len(low) < 20 else "LONG",
            "shape": shape(w),
            "len": min(len(w), 12),
            "isdigit": w.isdigit(),
            "isnum": bool(re.fullmatch(r"\d[\d,]*(?:\.\d+)?", w)),
            "hascomma": "," in w,
            "hasdot": "." in w and w[0].isdigit(),
            "ndigits": min(sum(c.isdigit() for c in w), 13),
            "isupper": w.isupper(),
            "istitle": w.istitle(),
            "mask": bool(re.fullmatch(r"[Xx*]+", w)),
            "month": low in MONTHS,
            "cur": low in CURRENCY,
            "cue": CUES.get(low, ""),
            "pre3": low[:3],
            "suf3": low[-3:],
            "space_before": t.start > 0 and text[t.start - 1].isspace(),
            "space_after": t.end < len(text) and text[t.end].isspace(),
            "pos_bucket": min(i * 10 // max(n, 1), 9),
        }
        if low in CURRENCY:
            last_cur = i
        f["since_cur"] = min(i - last_cur, 6) if last_cur >= 0 else 7
        for off in (-3, -2, -1, 1, 2, 3):
            j = i + off
            if 0 <= j < n:
                f.update(_tok_feats(toks[j].text, f"{off}:"))
            else:
                f[f"{off}:edge"] = True
        if i > 0:
            f["-1|0"] = toks[i - 1].text.lower() + "|" + shape(w)
        if i > 1:
            f["-2|-1"] = toks[i - 2].text.lower() + "|" + toks[i - 1].text.lower()
        # The nearest cue word to the left within six tokens: "Avl Bal Rs." is three away.
        for k in range(1, 7):
            j = i - k
            if j < 0:
                break
            c = CUES.get(toks[j].text.lower())
            if c:
                f["left_cue"] = c
                f["left_cue_dist"] = k
                break
        feats.append(f)
    return feats


def to_bio(toks: list[Tok], spans: list[dict]) -> list[str]:
    tags = ["O"] * len(toks)
    for s in spans:
        inside = [i for i, t in enumerate(toks) if t.start < s["end"] and s["start"] < t.end]
        for k, i in enumerate(inside):
            tags[i] = ("B-" if k == 0 else "I-") + s["label"]
    return tags


def from_bio(toks: list[Tok], tags: list[str]) -> list[dict]:
    spans, cur = [], None
    for t, tag in zip(toks, tags):
        if tag.startswith("B-") or (tag.startswith("I-") and (cur is None or cur["label"] != tag[2:])):
            if cur:
                spans.append(cur)
            cur = {"start": t.start, "end": t.end, "label": tag[2:]}
        elif tag.startswith("I-"):
            cur["end"] = t.end
        else:
            if cur:
                spans.append(cur)
            cur = None
    if cur:
        spans.append(cur)
    return spans


class SpanTagger:
    def __init__(self, c1: float = 0.05, c2: float = 0.05, max_iter: int = 150):
        self.crf = sklearn_crfsuite.CRF(algorithm="lbfgs", c1=c1, c2=c2, max_iterations=max_iter,
                                        all_possible_transitions=True)

    def fit(self, records: list[dict]) -> "SpanTagger":
        X, y = [], []
        for r in records:
            toks = tokenize(r["text"])
            if not toks:
                continue
            X.append(token_features(toks, r["text"]))
            y.append(to_bio(toks, r["spans"]))
        self.crf.fit(X, y)
        return self

    def predict(self, texts: list[str]) -> list[list[dict]]:
        out = []
        for text in texts:
            toks = tokenize(text)
            if not toks:
                out.append([])
                continue
            tags = self.crf.predict_single(token_features(toks, text))
            out.append(from_bio(toks, tags))
        return out


def type_classifier() -> Pipeline:
    feats = FeatureUnion([
        ("words", TfidfVectorizer(ngram_range=(1, 2), min_df=2, sublinear_tf=True,
                                  token_pattern=r"[A-Za-z]{2,}")),
        ("chars", TfidfVectorizer(analyzer="char_wb", ngram_range=(3, 5), min_df=3, sublinear_tf=True,
                                  max_features=60000)),
    ])
    return Pipeline([("f", feats), ("lr", LogisticRegression(max_iter=2000, C=8.0, class_weight="balanced"))])


def type_text(r: dict) -> str:
    # Digits carry no meaning for the type; masking them stops the model memorising amounts.
    return re.sub(r"\d", "0", r["text"])
