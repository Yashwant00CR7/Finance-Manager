"""Pull raw SMS cases out of the public sources and the app's own fixtures.

Output records (one JSON object per line) look like:

    {"source": ..., "sender": ..., "text": ..., "should_parse": true|false|null,
     "expect": {"amount": "500.00", "account": "1234", "payee": ..., "ref": ...,
                "balance": ..., "limit": ..., "kind": "EXPENSE"|...}, "note": ...}

`expect` holds the values the source's own test asserted. Those values are what make a
message "gold": the labeller anchors spans on them instead of guessing. Records with no
`expect` are "silver" and only ever used for training.

The raw output still contains whatever personal data the sources had, so it is written
outside the repo (see the README) and only the scrubbed dataset is kept.

Usage:
    python -m smsml.extract --downloads DIR --repo REPO_ROOT --out raw.jsonl
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

# --------------------------------------------------------------------------------------
# Small literal parsers
# --------------------------------------------------------------------------------------

_KOTLIN_ESCAPES = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "'": "'", "\\": "\\", "$": "$"}


def _balanced(src: str, open_at: int) -> int:
    """Index just past the parenthesis that closes the one at `open_at`, skipping strings."""
    depth, i, n = 0, open_at, len(src)
    while i < n:
        c = src[i]
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            i = (j + 3) if j >= 0 else n
            continue
        if c in "\"'":
            q = c
            i += 1
            while i < n and src[i] != q:
                i += 2 if src[i] == "\\" else 1
            i += 1
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return n


def _kotlin_string_expr(expr: str) -> str | None:
    """Evaluate a Kotlin string expression made of literals joined by `+`. None if it has code."""
    out, i, n = [], 0, len(expr)
    while i < n:
        c = expr[i]
        if c.isspace() or c == "+":
            i += 1
            continue
        if expr.startswith('"""', i):
            j = expr.find('"""', i + 3)
            if j < 0:
                return None
            raw = expr[i + 3:j]
            i = j + 3
            if expr.startswith(".trimIndent()", i):
                lines = raw.split("\n")
                body = [l for l in lines if l.strip()]
                indent = min((len(l) - len(l.lstrip()) for l in body), default=0)
                raw = "\n".join(l[indent:] for l in lines).strip("\n")
                i += len(".trimIndent()")
            out.append(raw)
            continue
        if c == '"':
            i += 1
            buf = []
            while i < n and expr[i] != '"':
                if expr[i] == "\\" and i + 1 < n:
                    nxt = expr[i + 1]
                    if nxt == "u" and i + 5 < n:
                        buf.append(chr(int(expr[i + 2:i + 6], 16)))
                        i += 6
                        continue
                    buf.append(_KOTLIN_ESCAPES.get(nxt, nxt))
                    i += 2
                    continue
                if expr[i] == "$" and i + 1 < n and (expr[i + 1] == "{" or expr[i + 1].isalpha()):
                    return None  # string template: not a literal
                buf.append(expr[i])
                i += 1
            i += 1
            out.append("".join(buf))
            continue
        return None
    return "".join(out) if out else None


def _split_args(body: str) -> list[str]:
    """Split the inside of a call's parentheses on top-level commas."""
    parts, depth, cur, i, n = [], 0, [], 0, len(body)
    while i < n:
        c = body[i]
        if body.startswith('"""', i):
            j = body.find('"""', i + 3)
            j = (j + 3) if j >= 0 else n
            cur.append(body[i:j])
            i = j
            continue
        if c in "\"'":
            j = i + 1
            while j < n and body[j] != c:
                j += 2 if body[j] == "\\" else 1
            cur.append(body[i:j + 1])
            i = j + 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        if c == "," and depth == 0:
            parts.append("".join(cur).strip())
            cur = []
        else:
            cur.append(c)
        i += 1
    if "".join(cur).strip():
        parts.append("".join(cur).strip())
    return parts


def _named(args: list[str]) -> tuple[dict[str, str], list[str]]:
    named, positional = {}, []
    for a in args:
        m = re.match(r"^([A-Za-z_]\w*)\s*=\s*(.*)$", a, re.S)
        if m and not a.lstrip().startswith('"'):
            named[m.group(1)] = m.group(2).strip()
        else:
            positional.append(a)
    return named, positional


def _number(expr: str | None) -> str | None:
    if not expr:
        return None
    m = re.search(r'BigDecimal\(\s*"([^"]+)"\s*\)', expr) or re.search(r"^\s*([0-9][0-9_.]*)", expr)
    return m.group(1).replace("_", "") if m else None


# --------------------------------------------------------------------------------------
# Sources
# --------------------------------------------------------------------------------------

def from_pennywise(root: Path) -> list[dict]:
    out = []
    for f in sorted(root.rglob("*.kt")):
        if "/test/" not in str(f):
            continue
        src = f.read_text(encoding="utf-8", errors="replace")
        for m in re.finditer(r"\b(ParserTestCase|SimpleTestCase)\s*\(", src):
            end = _balanced(src, m.end() - 1)
            named, pos = _named(_split_args(src[m.end():end - 1]))
            text = _kotlin_string_expr(named.get("message", ""))
            sender = _kotlin_string_expr(named.get("sender", "")) or ""
            if not text:
                continue
            currency = _kotlin_string_expr(named.get("currency", "")) or ""
            exp_src = named.get("expected", "")
            expect = None
            if exp_src.startswith("ExpectedTransaction"):
                e_named, _ = _named(_split_args(exp_src[exp_src.find("(") + 1:exp_src.rfind(")")]))
                currency = currency or (_kotlin_string_expr(e_named.get("currency", "")) or "")
                kind = re.search(r"TransactionType\.(\w+)", e_named.get("type", ""))
                expect = {
                    "amount": _number(e_named.get("amount")),
                    "account": _kotlin_string_expr(e_named.get("accountLast4", "")),
                    "payee": _kotlin_string_expr(e_named.get("merchant", "")),
                    "ref": _kotlin_string_expr(e_named.get("reference", "")),
                    "balance": _number(e_named.get("balance")),
                    "limit": _number(e_named.get("creditLimit")),
                    "kind": kind.group(1) if kind else None,
                    "is_card": "true" in e_named.get("isFromCard", ""),
                }
            should = named.get("shouldParse", "true").strip() != "false"
            out.append({
                "source": f"pennywise:{f.name}",
                "sender": sender,
                "text": text,
                "currency": currency or None,
                "should_parse": should,
                "expect": expect if should else None,
                "note": _kotlin_string_expr(named.get("name", "")) or "",
            })
    return out


def _dart_strings(expr: str) -> str | None:
    """Adjacent Dart single-quoted literals, implicitly concatenated."""
    parts = re.findall(r"'((?:[^'\\]|\\.)*)'", expr)
    if not parts:
        return None
    return "".join(p.encode().decode("unicode_escape") if "\\" in p else p for p in parts)


def from_txnvault(path: Path) -> list[dict]:
    src = path.read_text(encoding="utf-8", errors="replace")
    out = []
    for m in re.finditer(r"\btest\(\s*'((?:[^'\\]|\\.)*)'\s*,", src):
        end = _balanced(src, m.end() - len(m.group(0)) + m.group(0).find("("))
        block = src[m.start():end]
        call = re.search(r"registry\.parse\(", block)
        if not call:
            continue
        cend = _balanced(block, call.end() - 1)
        args = _split_args(block[call.end():cend - 1])
        if len(args) < 2:
            continue
        sender, text = _dart_strings(args[0]), _dart_strings(args[1])
        if not text:
            continue

        def grab(field: str) -> str | None:
            g = re.search(rf"expect\(txn\.{field},\s*([^)]+)\)", block)
            return g.group(1).strip() if g else None

        status = re.search(r"SmsParseStatus\.(\w+)", block)
        parsed = status is None or status.group(1) == "parsed"
        direction = grab("direction") or ""
        method = grab("paymentMethodType") or ""
        expect = None
        if parsed:
            expect = {
                "amount": grab("amount"),
                "account": _dart_strings(grab("cardOrAccountLastFour") or ""),
                "payee": _dart_strings(grab("counterparty") or ""),
                "ref": _dart_strings(grab("referenceId") or ""),
                "balance": None,
                "limit": None,
                "either_balance_or_limit": grab("availableBalanceOrLimit"),
                "kind": "INCOME" if "credit" in direction and "Card" not in direction else "EXPENSE",
                "is_card": "Card" in method or "creditCard" in method,
            }
        out.append({
            "source": "txnvault:sms_parsers_test.dart",
            "sender": sender or "",
            "text": text,
            "currency": None,
            "should_parse": parsed,
            "expect": expect,
            "note": m.group(1),
        })
    return out


def from_inbox_dump(path: Path) -> list[dict]:
    """A real inbox. Personal numbers are dropped entirely: those are people, not banks."""
    out = []
    for m in json.loads(path.read_text(encoding="utf-8")):
        sender = (m.get("address") or "").strip()
        if not re.search(r"[A-Za-z]", sender):
            continue
        out.append({
            "source": "shivarya:sms-export.json",
            "sender": sender,
            "text": m.get("body") or "",
            "currency": None,
            "should_parse": None,
            "expect": None,
            "note": "",
        })
    return out


def from_app_fixtures(path: Path) -> list[dict]:
    src = path.read_text(encoding="utf-8", errors="replace")
    out = []
    for m in re.finditer(r"\b(Fixture|NegativeFixture)\s*\(", src):
        end = _balanced(src, m.end() - 1)
        named, pos = _named(_split_args(src[m.end():end - 1]))
        if m.group(1) == "Fixture":
            if len(pos) < 7:
                continue
            sender, text = _kotlin_string_expr(pos[1]), _kotlin_string_expr(pos[2])
            direction, paise, account = pos[4], pos[5].replace("_", ""), _kotlin_string_expr(pos[6])
            try:
                amount = f"{int(paise) / 100:.2f}"
            except ValueError:
                amount = None
            is_card = "true" in named.get("isCard", "")
            out.append({
                "source": "app:BankFixtures.kt",
                "sender": sender or "",
                "text": text or "",
                "currency": "INR",
                "should_parse": True,
                "expect": {
                    "amount": amount,
                    "account": account,
                    "payee": _kotlin_string_expr(named.get("payee", "")),
                    "ref": None, "balance": None, "limit": None,
                    "kind": ("CREDIT" if is_card and "DEBIT" in direction
                             else "EXPENSE" if "DEBIT" in direction else "INCOME"),
                    "is_card": is_card,
                },
                "note": _kotlin_string_expr(pos[0]) or "",
            })
        else:
            if len(pos) < 2:
                continue
            out.append({
                "source": "app:BankFixtures.kt",
                "sender": _kotlin_string_expr(pos[0]) or "",
                "text": _kotlin_string_expr(pos[1]) or "",
                "currency": "INR",
                "should_parse": False,
                "expect": None,
                "note": _kotlin_string_expr(pos[2]) if len(pos) > 2 else "",
            })
    return [r for r in out if r["text"]]


def from_formats_doc(path: Path) -> list[dict]:
    """Verbatim samples in docs/bank-sms-formats.md: a bold title, a sender, a fenced body."""
    src = path.read_text(encoding="utf-8")
    out = []
    pat = re.compile(r"\*\*(?P<title>[^*]+)\*\*\s*—\s*`(?P<sender>[^`]+)`[^\n]*\n```\n(?P<body>.*?)\n```", re.S)
    for m in pat.finditer(src):
        title = m.group("title")
        negative = bool(re.search(r"NOT|must NOT|not a|duplicate", title))
        out.append({
            "source": "app:bank-sms-formats.md",
            "sender": m.group("sender").strip(),
            "text": m.group("body").strip(),
            "currency": "INR",
            "should_parse": False if negative else None,
            "expect": None,
            "note": title.strip(),
        })
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--downloads", type=Path, required=True)
    ap.add_argument("--repo", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()

    records: list[dict] = []
    pw = next(a.downloads.glob("pennywise/*"))
    records += from_pennywise(pw)
    records += from_txnvault(next(a.downloads.glob("txnvault/*")) / "test/unit/sms_parsers_test.dart")
    records += from_inbox_dump(next(a.downloads.glob("shivarya/*")) / "data/sms-export.json")
    records += from_app_fixtures(a.repo / "app/src/test/java/com/yk/finance/BankFixtures.kt")
    records += from_formats_doc(a.repo / "docs/bank-sms-formats.md")

    a.out.parent.mkdir(parents=True, exist_ok=True)
    with a.out.open("w", encoding="utf-8") as fh:
        for r in records:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")

    from collections import Counter
    by = Counter(r["source"].split(":")[0] for r in records)
    print(f"{len(records)} records", dict(by))


if __name__ == "__main__":
    main()
