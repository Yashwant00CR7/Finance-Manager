"""Generate synthetic variants of each real template by refilling its spans.

    python -m smsml.synth --real data/real.jsonl --out data/synthetic.jsonl

The fixed wording of a real message is kept exactly; only the labelled slots change, and
each new value is written in the same style as the original (Indian or western digit
grouping, decimals, date order, month spelling, case). That teaches the model the slot
shapes without inventing sentences no bank sends.

One unique template (see dataset.signature) yields VARIANTS variants, scaled per bank so
that small banks are not drowned out by ICICI and the non-bank inbox noise:
    banks with < 5 templates      -> 2x
    banks with > 100 templates    -> 0.5x
"""

from __future__ import annotations

import argparse
import calendar
import math
import json
import random
import re
from collections import Counter, defaultdict
from pathlib import Path

from smsml.dataset import FAKE_HANDLES, FAKE_MERCHANTS, FAKE_PEOPLE, _style, fake_digits

VARIANTS = 30


def _group_indian(n: int) -> str:
    s = str(n)
    if len(s) <= 3:
        return s
    head, tail = s[:-3], s[-3:]
    parts = []
    while len(head) > 2:
        parts.insert(0, head[-2:])
        head = head[:-2]
    if head:
        parts.insert(0, head)
    return ",".join(parts + [tail])


def fake_money(src: str, rng: random.Random, label: str) -> str:
    lo, hi = (1, 2e5) if label == "AMOUNT" else (10, 2e6)
    # Log-uniform: a 40 rupee chai is as common in SMS as a 40,000 rupee rent payment.
    value = round(10 ** rng.uniform(math.log10(lo), math.log10(hi)), 2)
    if src.startswith("."):
        return f".{rng.randint(1, 99):02d}"[: len(src)]
    dec = len(src.split(".")[1]) if "." in src else 0
    whole = int(value)
    if "," in src:
        # The two groupings only differ above 99,999. Western is used only when the source
        # visibly groups in threes past the first comma ("276,623.34"); otherwise Indian.
        western = bool(re.search(r"\d{3},\d{3}", src))
        grouped = f"{whole:,}" if western else _group_indian(whole)
    else:
        grouped = str(whole)
    if dec:
        frac = f"{int(round((value - whole) * 10 ** dec)) % 10 ** dec:0{dec}d}"
        return f"{grouped}.{frac}"
    return grouped


MONTHS = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"]


def fake_date(src: str, rng: random.Random) -> str:
    year = rng.randint(2023, 2027)
    month = rng.randint(1, 12)
    day = rng.randint(1, calendar.monthrange(year, month)[1])
    parts = re.split(r"(\d+|[A-Za-z]+)", src)
    groups = [p for p in parts if p and (p.isdigit() or p.isalpha())]
    digit_groups = [g for g in groups if g.isdigit()]
    year_first = bool(digit_groups) and len(digit_groups[0]) == 4
    has_alpha_month = any(g.isalpha() for g in groups)
    seen_digits = 0
    out = []
    for p in parts:
        if not p:
            continue
        if p.isalpha():
            name = calendar.month_name[month] if len(p) > 4 and p.lower() != "sept" else MONTHS[month - 1]
            out.append(_style(p, name) if not p.isupper() else name.upper())
        elif p.isdigit():
            if len(p) == 4:
                out.append(str(year))
            elif year_first:
                v = month if seen_digits == 1 else day
                out.append(f"{v:0{len(p)}d}")
            else:
                if seen_digits == 0:
                    out.append(f"{day:0{len(p)}d}" if len(p) == 2 else str(day))
                elif seen_digits == 1 and not has_alpha_month:
                    out.append(f"{month:0{len(p)}d}" if len(p) == 2 else str(month))
                else:
                    out.append(f"{year % 100:02d}")
            seen_digits += 1
        else:
            out.append(p)
    return "".join(out)


def fake_time(src: str, rng: random.Random) -> str:
    ampm = re.search(r"[AaPp]\.?[Mm]\.?", src)
    nums = re.findall(r"\d+", src)
    h = rng.randint(1, 12) if ampm else rng.randint(0, 23)
    vals = [h, rng.randint(0, 59), rng.randint(0, 59)]
    it = iter(f"{v:0{len(n)}d}" for v, n in zip(vals, nums))
    out = re.sub(r"\d+", lambda m: next(it), src)
    if ampm:
        new = rng.choice(["AM", "PM"])
        new = new if ampm.group().isupper() else new.lower()
        out = out[:ampm.start()] + new + out[ampm.end():] if len(ampm.group()) == 2 else out
    return out


def fake_payee_value(src: str, rng: random.Random) -> str:
    m = re.fullmatch(r"([\w.\-]+)@([\w.]+)", src.strip())
    if m:
        return f"{rng.choice(FAKE_HANDLES)}{rng.randint(1, 999)}@{m.group(2)}"
    pool = FAKE_PEOPLE + FAKE_MERCHANTS
    return _style(src, rng.choice(pool))


def refill(rec: dict, rng: random.Random) -> dict:
    text, spans = rec["text"], sorted(rec["spans"], key=lambda s: s["start"])
    out, new_spans, i = [], [], 0
    pos = 0
    for s in spans:
        seg = text[s["start"]:s["end"]]
        lab = s["label"]
        if lab in ("AMOUNT", "BALANCE", "AVAIL_LIMIT"):
            rep = fake_money(seg, rng, lab)
        elif lab == "DATE":
            rep = fake_date(seg, rng)
        elif lab == "TIME":
            rep = fake_time(seg, rng)
        elif lab in ("ACCOUNT", "REF_NO"):
            rep = fake_digits(seg, rng)
        elif lab == "PAYEE":
            rep = fake_payee_value(seg, rng)
        else:
            rep = seg
        out.append(text[i:s["start"]])
        pos += s["start"] - i
        new_spans.append({**s, "start": pos, "end": pos + len(rep)})
        out.append(rep)
        pos += len(rep)
        i = s["end"]
    out.append(text[i:])
    # Unlabelled digit runs (helplines, card blocks) vary too, so the model cannot key on them.
    new_text = "".join(out)
    return {**rec, "text": new_text, "spans": new_spans}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--real", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--variants", type=int, default=VARIANTS)
    ap.add_argument("--seed", type=int, default=13)
    a = ap.parse_args()

    real = [json.loads(l) for l in a.real.open(encoding="utf-8")]
    by_sig: dict[str, dict] = {}
    for r in real:
        by_sig.setdefault(r["signature"], r)
    per_bank = Counter(r["bank"] for r in by_sig.values())

    rng = random.Random(a.seed)
    n_out = 0
    counts: dict[str, int] = defaultdict(int)
    with a.out.open("w", encoding="utf-8") as fh:
        for sig, r in by_sig.items():
            k = a.variants
            if per_bank[r["bank"]] < 5:
                k *= 2
            elif per_bank[r["bank"]] > 100:
                k //= 2
            for v in range(k):
                s = refill(r, rng)
                s.update({"id": f"syn-{n_out:06d}", "origin": "synthetic", "parent": r["id"], "gold": False})
                fh.write(json.dumps(s, ensure_ascii=False) + "\n")
                counts[r["bank"]] += 1
                n_out += 1
    print(f"{len(by_sig)} templates -> {n_out} synthetic messages over {len(counts)} banks")


if __name__ == "__main__":
    main()
