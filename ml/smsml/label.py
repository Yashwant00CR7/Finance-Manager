"""Turn raw records into labelled examples: character spans plus one message type.

Every span carries `by`: "expect" when it was anchored on a value the source's own test
asserted, "rule" when a keyword heuristic placed it. Evaluation reports the two
separately, because only the first is independent evidence.

Labels:
    AMOUNT  ACCOUNT  PAYEE  DATE  TIME  BALANCE  AVAIL_LIMIT  REF_NO

Message types:
    DEBIT  CREDIT  CARD_SPEND  CARD_PAYMENT  ATM_WITHDRAWAL  REFUND  FAILED
    MANDATE_FUTURE  BILL_DUE  OTP  PROMO  BALANCE_INFO  INFO
"""

from __future__ import annotations

import re
from pathlib import Path

LABELS = ["AMOUNT", "ACCOUNT", "PAYEE", "DATE", "TIME", "BALANCE", "AVAIL_LIMIT", "REF_NO"]
TYPES = ["DEBIT", "CREDIT", "CARD_SPEND", "CARD_PAYMENT", "ATM_WITHDRAWAL", "REFUND", "FAILED",
         "MANDATE_FUTURE", "BILL_DUE", "OTP", "PROMO", "BALANCE_INFO", "INFO"]
TRANSACTION_TYPES = {"DEBIT", "CREDIT", "CARD_SPEND", "CARD_PAYMENT", "ATM_WITHDRAWAL", "REFUND"}

# --------------------------------------------------------------------------------------
# Banks
# --------------------------------------------------------------------------------------

def load_header_registry(repo: Path) -> dict[str, str]:
    """HEADER -> BANK from the app's own SenderRegistry.kt, so both sides agree on names."""
    src = (repo / "app/src/main/java/com/yk/finance/parser/SenderRegistry.kt").read_text()
    out = {}
    for m in re.finditer(r'\bbank\(\s*"([A-Z_]+)"\s*,(.*?)\)', src, re.S):
        for h in re.findall(r'"([A-Za-z0-9]+)"', m.group(2)):
            out[h.upper()] = m.group(1)
    return out


# Names as they appear in message text or in a pennywise test file name.
BANK_MENTIONS: list[tuple[str, str]] = [
    ("SBICARD", r"SBI\s*Card|SBI Credit Card|SBICRD"),
    ("SBI", r"\bSBI\b|State Bank|ATMSBI|SBIINB|SBIUPI|CBSSBI"),
    ("HDFC", r"HDFC"),
    ("ICICI", r"ICICI"),
    ("AXIS", r"\bAxis\b|AXISBK"),
    ("KOTAK", r"Kotak|KOTAKB"),
    ("PNB", r"\bPNB\b|Punjab National"),
    ("PSB", r"Punjab\s*(&|and)?\s*Sind|PSBANK"),
    ("BOB", r"Bank of Baroda|\bBOB\b|BOBTXN|BankOfBaroda"),
    ("CANARA", r"Canara|CANBNK"),
    ("UNION", r"Union Bank|UNIONB"),
    ("BOI", r"Bank of India|\bBOI\b|BOIIND"),
    ("BOM", r"Bank of Maharashtra|MAHABK"),
    ("CENTRALBANK", r"Central Bank|CBoI|CENTBK"),
    ("INDIANBANK", r"Indian Bank|INDBNK"),
    ("IOB", r"Indian Overseas|\bIOB\b"),
    ("UCO", r"\bUCO\b"),
    ("IDBI", r"IDBI"),
    ("IDFC", r"IDFC"),
    ("INDUSIND", r"IndusInd|INDUSB"),
    ("YES", r"\bYES BANK\b|Yes Bank|YESBNK"),
    ("AU", r"\bAU Small|\bAU Bank|AUBANK"),
    ("FEDERAL", r"Federal Bank|FEDBNK|FEDNET"),
    ("SIB", r"South Indian Bank|SIBSMS"),
    ("KARNATAKA", r"Karnataka Bank|KBLBNK"),
    ("KVB", r"Karur Vysya|KVBANK"),
    ("CUB", r"City Union|CUBANK"),
    ("TMB", r"Tamilnad Mercantile|TMBANK"),
    ("RBL", r"\bRBL\b|RBLBNK|RBLCRD"),
    ("BANDHAN", r"Bandhan"),
    ("EQUITAS", r"Equitas"),
    ("SURYODAY", r"Suryoday"),
    ("JANA", r"Jana Small"),
    ("UJJIVAN", r"Ujjivan"),
    ("DHANLAXMI", r"Dhanlaxmi|Dhanalakshmi"),
    ("SARASWAT", r"Saraswat"),
    ("SVC", r"\bSVC\b"),
    ("KERALAGRAMIN", r"Kerala Gramin"),
    ("KERALABANK", r"Kerala Bank"),
    ("GREATER", r"Greater Bank"),
    ("HSBC", r"HSBC"),
    ("STANCHART", r"Standard Chartered|\bSC Bank|SCBANK|StanChart"),
    ("CITI", r"\bCiti"),
    ("AMEX", r"American Express|\bAmex\b|AMEX"),
    ("IPPB", r"India Post Payments|IPPB"),
    ("DOP", r"Department of Posts|\bDOP\b|India Post\b"),
    ("NSDL", r"NSDL Payments"),
    ("JUPITER", r"Jupiter"),
    ("PHONEPE", r"PhonePe|PHONPE"),
    ("PAYTM", r"Paytm"),
    ("AMAZONPAY", r"Amazon Pay"),
    ("JIOPAY", r"JioPay|Jio Payments"),
    ("CRED", r"\bCRED\b"),
    ("SLICE", r"\bslice\b|SLICE"),
    ("PLUXEE", r"Pluxee"),
    ("JUSPAY", r"Juspay"),
    ("CASHFREE", r"Cashfree"),
    ("AIRTELPB", r"Airtel Payments"),
]

# pennywise test files whose cases are Indian. Name fragment -> bank.
PENNYWISE_INDIAN = {
    "SBI": "SBI", "HDFCBank": "HDFC", "HDFCMandate": "HDFC", "ICICI": "ICICI", "Axis": "AXIS",
    "Kotak": "KOTAK", "PNB": "PNB", "BankOfBaroda": "BOB", "Canara": "CANARA", "Union": "UNION",
    "BankOfIndia": "BOI", "CentralBankOfIndia": "CENTRALBANK", "IndianBank": "INDIANBANK",
    "UCO": "UCO", "IDBI": "IDBI", "IDFCFirst": "IDFC", "IndusInd": "INDUSIND", "YesBank": "YES",
    "AUBank": "AU", "FederalBank": "FEDERAL", "SouthIndian": "SIB", "Karnataka": "KARNATAKA",
    "CityUnion": "CUB", "TamilnadMercantile": "TMB", "Bandhan": "BANDHAN", "Equitas": "EQUITAS",
    "Suryoday": "SURYODAY", "JanaSmallFinance": "JANA", "Dhanlaxmi": "DHANLAXMI",
    "Saraswat": "SARASWAT", "SVCBank": "SVC", "KeralaGramin": "KERALAGRAMIN",
    "KeralaBank": "KERALABANK", "GreaterBank": "GREATER", "HSBC": "HSBC",
    "StandardCharteredBank": "STANCHART", "DOPBank": "DOP", "NSDLPayments": "NSDL",
    "Jupiter": "JUPITER", "PunjabSind": "PSB", "PhonePeWallet": "PHONEPE", "JioPay": "JIOPAY",
    "Cred": "CRED", "Slice": "SLICE", "Juspay": "JUSPAY", "Cashfree": "CASHFREE",
    "Pluxee": "PLUXEE", "NaviMutualFund": "NAVIMF", "HDFCMutualFund": "HDFCMF",
}
# Files that mix countries: only INR cases (or Indian-looking senders) are kept.
PENNYWISE_MIXED = {"SmsReportRegressionTest.kt", "GenericTransactionExtractorTest.kt",
                   "TestAllNewParsers.kt"}
WALLETS = {"PHONEPE", "PAYTM", "AMAZONPAY", "JIOPAY", "CRED", "SLICE", "PLUXEE", "JUSPAY",
           "CASHFREE", "AIRTELPB"}

DLT = re.compile(r"^[A-Z]{2}-([A-Za-z0-9]{6})(?:-[A-Z])?$")


def header_core(sender: str) -> str:
    s = sender.strip().upper()
    m = DLT.match(s)
    if m:
        return m.group(1).upper()
    return re.sub(r"-[SPTG]$", "", s)


def pennywise_bank(source: str) -> str | None:
    name = source.split(":", 1)[1]
    for frag, bank in sorted(PENNYWISE_INDIAN.items(), key=lambda kv: -len(kv[0])):
        if frag in name:
            return bank
    return None


def bank_of(rec: dict, registry: dict[str, str]) -> str | None:
    core = header_core(rec["sender"])
    if core in registry:
        return registry[core]
    if rec["source"].startswith("pennywise:"):
        b = pennywise_bank(rec["source"])
        if b:
            return b
    for bank, pat in BANK_MENTIONS:
        if re.search(pat, rec["sender"] + " " + rec["text"]):
            return bank
    return None


def is_indian(rec: dict) -> bool:
    """Drops pennywise's foreign banks; every other source is Indian already."""
    if not rec["source"].startswith("pennywise:"):
        return True
    fname = rec["source"].split(":", 1)[1]
    if fname in PENNYWISE_MIXED:
        if rec.get("currency"):
            return rec["currency"] == "INR"
        return bool(DLT.match(rec["sender"].strip().upper()))
    if pennywise_bank(rec["source"]) is None:
        return False
    return rec.get("currency") in (None, "INR")


# --------------------------------------------------------------------------------------
# Span finding
# --------------------------------------------------------------------------------------

NUM = r"\d{1,3}(?:,\d{2,3})+(?:\.\d{1,3})?|\d+(?:\.\d{1,3})?|(?<=[\s.])\.\d{1,2}(?![\d.,])"
CUR = r"(?:Rs\.?|INR|₹|Rupees|RS\.?|rs\.?)"
# Not glued to a preceding digit or "digit." - but "Rs.500" and "Rs..6" are fine.
NUM_RE = re.compile(rf"(?<!\d)(?<!\d\.)(?:{NUM})(?![\d])")


def _val(s: str) -> float | None:
    try:
        return float(s.replace(",", ""))
    except ValueError:
        return None


def _overlaps(a: tuple[int, int], spans: list[dict]) -> bool:
    return any(a[0] < s["end"] and s["start"] < a[1] for s in spans)


def _add(spans: list[dict], start: int, end: int, label: str, by: str) -> bool:
    if start >= end or _overlaps((start, end), spans):
        return False
    spans.append({"start": start, "end": end, "label": label, "by": by})
    return True


BAL_CUE = re.compile(r"(?:bal(?:ance)?|BAL)\b[^0-9]{0,22}$", re.I)
LIMIT_CUE = re.compile(r"(?:lim(?:it)?|lmt)\b[^0-9]{0,22}$", re.I)
CUR_BEFORE = re.compile(rf"{CUR}\s*[:.\-]?\s*$")


def _context(text: str, start: int, width: int = 30) -> str:
    return text[max(0, start - width):start]


def _value_spans(text: str, value: str | None) -> list[tuple[int, int]]:
    v = _val(value) if value else None
    if v is None:
        return []
    return [(m.start(), m.end()) for m in NUM_RE.finditer(text) if _val(m.group()) == v]


def _pick_amount(text: str, cands: list[tuple[int, int]]) -> tuple[int, int] | None:
    def score(c):
        ctx = _context(text, c[0])
        s = 0
        if CUR_BEFORE.search(ctx) or re.match(rf"\s*{CUR}", text[c[1]:c[1] + 6]):
            s -= 2
        if BAL_CUE.search(ctx) or LIMIT_CUE.search(ctx):
            s += 3
        return (s, c[0])
    return min(cands, key=score) if cands else None


def _pick_cued(text: str, cands: list[tuple[int, int]], cue: re.Pattern) -> tuple[int, int] | None:
    cued = [c for c in cands if cue.search(_context(text, c[0]))]
    return (cued or cands or [None])[0]


ACCT_CUE = re.compile(r"(?:a/?c|acct|account|card|ending|a/c no|ac|xx)\W{0,12}(?:no\.?|number|with)?\W{0,4}$", re.I)


MASK_CHARS = set("0123456789Xx*#•.-")


def _account_spans(text: str, last: str) -> list[tuple[int, int]]:
    """Every place the account's trailing digits appear, grown left over its mask.

    Banks write the same account as XX1234, *1234, ...1234, 20XXXXX1234, 123xxxx1234 or
    074-260***-006, so the span is found from its end: the trailing digits, then leftward
    over digits and mask characters until anything else.
    """
    out = []
    for tail in (last, last[-3:]) if len(last) > 3 else (last,):
        for m in re.finditer(rf"(?<!\d){re.escape(tail)}(?!\d)|(?<=\d){re.escape(tail)}(?!\d)", text):
            st = m.start()
            while st > 0 and text[st - 1] in MASK_CHARS:
                st -= 1
            while st < m.start() and text[st] in ".-":
                st += 1
            out.append((st, m.end()))
        if out:
            break
    out = sorted(set(out), key=lambda c: (
        0 if re.match(r"[Xx*#•.]", text[c[0]]) or ACCT_CUE.search(_context(text, c[0])) else 1, c[0]))
    return out


def _find_ci(text: str, needle: str | None) -> tuple[int, int] | None:
    if not needle or len(needle.strip()) < 2:
        return None
    i = text.lower().find(needle.lower().strip())
    return (i, i + len(needle.strip())) if i >= 0 else None


MONTHS = r"(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*"
DATE_RES = [
    re.compile(rf"\b\d{{1,2}}[-/ ]?{MONTHS}[-/ ,]?\s?(?:\d{{4}}|\d{{2}})(?!\d)", re.I),
    re.compile(rf"\b{MONTHS}\s\d{{1,2}},?\s\d{{4}}\b", re.I),
    re.compile(r"\b\d{4}[-/.]\d{1,2}[-/.]\d{1,2}\b"),
    re.compile(r"\b\d{1,2}[-/.]\d{1,2}[-/.](?:\d{4}|\d{2})(?!\d)"),
    re.compile(rf"\b\d{{1,2}}[-/ ]?{MONTHS}\b(?![-/ ]?\d)", re.I),
]
TIME_RE = re.compile(r"\b\d{1,2}:\d{2}(?::\d{2})?(?:\s?[AaPp]\.?[Mm]\.?)?(?![\d:])")
UPI_DASH_RE = re.compile(r"\bUPI[-/](?P<ref>\d{9,14})[-/](?P<p>[A-Za-z0-9][A-Za-z0-9 .&*'@\-]{0,40}?)(?=\.\s|\.$|\s+on\b|$)")
REF_RE = re.compile(
    r"(?:UPI\s*:|UPI\s*Ref(?:\.|erence)?\s*(?:No\.?|number|#)?|Ref(?:erence)?\.?\s*(?:No\.?|number|#|ID)?|"
    r"RRN|UTR(?:\s*No\.?)?|Txn\s*(?:ID|No\.?|#)|Transaction\s*(?:ID|No\.?|number)|"
    r"IMPS\s*Ref\s*no|TXN\s*Ref\s*No)\s*[:\-.#]?\s*-?\s*(?P<ref>[A-Za-z0-9]*\d[A-Za-z0-9]{5,})", re.I)
VPA_RE = re.compile(r"\b[\w.\-]{2,}@[A-Za-z][\w.]{1,}\b")
ACCT_RE = re.compile(
    r"(?:a/?c|acct|account|card|ending(?: with)?|a/c no\.?)\s*(?:no\.?|number)?\s*[:\-]?\s*"
    r"(?P<acct>\d{0,6}[Xx*#•]+\s?\d{2,8}|\d{4}(?!\d)|[Xx*#•]*\d{3,6}(?!\d))", re.I)
MASK_RE = re.compile(r"(?<![\w])[Xx*]{2,}\d{3,8}(?!\d)")
PAYEE_RES = [
    re.compile(r"\bVPA\s+(?P<p>[\w.\-]+@[\w.]+?)(?=\.?\s|\.Ref|\s*\(|$)"),
    re.compile(r";\s*(?P<p>[A-Z][A-Za-z0-9 .&'\-]{1,40}?)\s+credited\b"),
    re.compile(r"(?<![\w.])(?P<p>[A-Z][A-Z0-9&'\-]+(?: [A-Z0-9&'\-]+){0,5})\s+credited\b"),
    re.compile(r"\b(?:at|@)\s*(?P<p>[A-Z0-9][A-Za-z0-9 .&*'\-/]{1,40}?)\s+on\b"),
    re.compile(r"\bto\s+(?P<p>[A-Za-z0-9][\w.\-]*@[\w.]+)"),
    re.compile(r"\b(?:to|towards|trf to|Info:?)\s+(?P<p>[A-Z][A-Z0-9 .&'\-]{2,40}?)(?=\s+(?:on|Ref|UPI|via|\()|[.,]\s|\.?$)"),
]


def heuristic_spans(text: str, spans: list[dict]) -> None:
    """Adds rule-placed spans wherever no anchored span already sits."""
    for rx in DATE_RES:
        for m in rx.finditer(text):
            _add(spans, m.start(), m.end(), "DATE", "rule")
    for m in TIME_RE.finditer(text):
        _add(spans, m.start(), m.end(), "TIME", "rule")
    for m in UPI_DASH_RE.finditer(text):
        _add(spans, m.start("ref"), m.end("ref"), "REF_NO", "rule")
        _add(spans, m.start("p"), m.end("p"), "PAYEE", "rule")
    for m in REF_RE.finditer(text):
        _add(spans, m.start("ref"), m.end("ref"), "REF_NO", "rule")
    for m in ACCT_RE.finditer(text):
        _add(spans, m.start("acct"), m.end("acct"), "ACCOUNT", "rule")
    for m in re.finditer(r"\bcard\s*\((?P<acct>\d{4})\)", text, re.I):
        _add(spans, m.start("acct"), m.end("acct"), "ACCOUNT", "rule")
    for m in MASK_RE.finditer(text):
        _add(spans, m.start(), m.end(), "ACCOUNT", "rule")

    have = {s["label"] for s in spans}
    money = []
    for m in re.finditer(rf"(?:{CUR}|USD|EUR|GBP|AED|SGD|\$)\s*[:.\-]?\s*(?P<n>{NUM})", text):
        money.append((m.start("n"), m.end("n")))
    for m in re.finditer(rf"(?P<n>{NUM})\s*(?:/-\s*)?(?:Rs\b|INR\b|rupees)", text, re.I):
        money.append((m.start("n"), m.end("n")))
    if not money:
        for m in re.finditer(rf"(?:debited|credited|withdrawn|spent|paid|deducted)\s+(?:by|with|for|of)?\s*(?P<n>{NUM})\b", text, re.I):
            money.append((m.start("n"), m.end("n")))
    money.sort()
    for st, en in money:
        ctx = _context(text, st)
        if LIMIT_CUE.search(ctx):
            label = "AVAIL_LIMIT"
        elif BAL_CUE.search(ctx):
            label = "BALANCE"
        else:
            label = "AMOUNT"
        if label in have and label == "AMOUNT":
            continue
        if _add(spans, st, en, label, "rule"):
            have.add(label)

    if "PAYEE" not in have:
        for rx in PAYEE_RES:
            m = rx.search(text)
            if m:
                p = m.group("p").rstrip(" .")
                st = m.start("p")
                if _add(spans, st, st + len(p), "PAYEE", "rule"):
                    break


def label_spans(rec: dict) -> tuple[list[dict], list[str]]:
    """Spans for one record, plus the expected values that could not be placed."""
    text, exp = rec["text"], rec.get("expect") or {}
    spans: list[dict] = []
    misses: list[str] = []

    if exp.get("amount"):
        c = _pick_amount(text, _value_spans(text, exp["amount"]))
        if c:
            _add(spans, *c, "AMOUNT", "expect")
        else:
            misses.append("AMOUNT")
    if exp.get("balance"):
        cands = [c for c in _value_spans(text, exp["balance"]) if not _overlaps(c, spans)]
        c = _pick_cued(text, cands, BAL_CUE)
        if c:
            _add(spans, *c, "BALANCE", "expect")
        else:
            misses.append("BALANCE")
    if exp.get("limit"):
        cands = [c for c in _value_spans(text, exp["limit"]) if not _overlaps(c, spans)]
        c = _pick_cued(text, cands, LIMIT_CUE)
        if c:
            _add(spans, *c, "AVAIL_LIMIT", "expect")
        else:
            misses.append("AVAIL_LIMIT")
    if exp.get("either_balance_or_limit"):
        cands = [c for c in _value_spans(text, exp["either_balance_or_limit"]) if not _overlaps(c, spans)]
        if cands:
            c = cands[0]
            label = "AVAIL_LIMIT" if LIMIT_CUE.search(_context(text, c[0])) else "BALANCE"
            _add(spans, *c, label, "expect")
    if exp.get("account"):
        cands = [c for c in _account_spans(text, exp["account"]) if not _overlaps(c, spans)]
        if cands:
            _add(spans, *cands[0], "ACCOUNT", "expect")
        else:
            misses.append("ACCOUNT")
    if exp.get("ref"):
        c = _find_ci(text, exp["ref"])
        if c and _add(spans, *c, "REF_NO", "expect"):
            pass
        else:
            misses.append("REF_NO")
    if exp.get("payee"):
        c = _find_ci(text, exp["payee"])
        if c:
            vpa = re.match(r"@[A-Za-z][\w.]*[A-Za-z]", text[c[1]:])
            if vpa:
                c = (c[0], c[1] + vpa.end())
        if c and _add(spans, *c, "PAYEE", "expect"):
            pass
        else:
            misses.append("PAYEE")

    heuristic_spans(text, spans)
    spans.sort(key=lambda s: s["start"])
    return spans, misses


# --------------------------------------------------------------------------------------
# Message type
# --------------------------------------------------------------------------------------

def _has(text: str, pat: str) -> bool:
    return re.search(pat, text, re.I) is not None


OTP = r"\bOTP\b|one[- ]time password|verification code|\bcode is\b|do not share.*code"
FAILED = r"declined|failed|unsuccessful|insufficient (?:funds|balance)|could not be processed|not been processed|rejected"
MANDATE = (r"mandate|e-?mandate|autopay|auto-?debit|standing instruction|will be debited|"
           r"to be debited|scheduled|upcoming|is due|\bdue on\b|ASBA|is blocked|blocked in your|"
           r"\bSI\b.*(?:set|created|registered)|pre-?debit|presentation")
EXECUTED = r"successfully executed|has been debited|debited (?:from|for|by|with)|executed on|is debited"
CARD_PAY = (r"payment (?:of|for)?.{0,30}(?:received|credited|has been)|thank you for (?:your )?payment|"
            r"received (?:your )?payment|payment.{0,40}towards your.{0,30}card")
REFUND = r"refund|revers|reversed|chargeback|cashback.{0,20}credited"
ATM = r"\bATM\b|cash withdraw|withdrawn|withdrawal"
CARD = r"credit card|\bcard\b.{0,25}(?:spent|used|transaction|purchase)|spent on|spent@|\bspent\b"
DEBIT = r"debited|debit|\bsent\b|paid|deducted|transferred|purchase|spent|\bdr\b|withdrawn"
CREDIT = r"credited|credit(?!\s*card)|received|deposited|\bcr\b"
BILL = r"statement|total (?:amount )?due|minimum (?:amount )?due|min(?:imum)?\.? due|bill (?:is )?generated|payment due"
PROMO = (r"offer|pre-?approved|eligible|apply now|click|https?://|upto|up to \d|loan|"
         r"cashback|reward|win\b|congrat|limited period|exclusive|instant|download|avail|interest rate")
BALANCE = r"available balance|avl bal|a/c bal|balance (?:in|of|is)|balance enquiry"


def message_type(rec: dict, spans: list[dict]) -> str:
    text = rec["text"]
    sp = rec.get("should_parse")
    exp = rec.get("expect") or {}
    has_amount = any(s["label"] == "AMOUNT" for s in spans)

    if _has(text, OTP) and not (has_amount and _has(text, r"debited|credited|spent|withdrawn")):
        return "OTP"
    if sp is False or (sp is None and not has_amount):
        if _has(text, FAILED):
            return "FAILED"
        if _has(text, MANDATE) and not _has(text, r"successfully executed"):
            return "MANDATE_FUTURE"
        if _has(text, BILL):
            return "BILL_DUE"
        if _has(text, PROMO):
            return "PROMO"
        if _has(text, BALANCE):
            return "BALANCE_INFO"
        if sp is False:
            return "INFO"

    if sp is None:
        # Unlabelled real message: decide entirely from wording.
        if _has(text, FAILED):
            return "FAILED"
        if _has(text, MANDATE) and not _has(text, EXECUTED):
            return "MANDATE_FUTURE"
        if _has(text, BILL) and not _has(text, DEBIT + "|" + CREDIT):
            return "BILL_DUE"
        if _has(text, PROMO) and not _has(text, r"debited|credited|spent|withdrawn|received"):
            return "PROMO"

    if _has(text, CARD_PAY) and _has(text, r"card"):
        return "CARD_PAYMENT"
    if _has(text, REFUND):
        return "REFUND"
    if _has(text, ATM) and not _has(text, r"credited to"):
        return "ATM_WITHDRAWAL"
    kind = exp.get("kind")
    is_card = exp.get("is_card") or kind == "CREDIT"
    if is_card or (_has(text, CARD) and not _has(text, r"debit card.*(?:credited)")):
        if not _has(text, r"\bcredited\b") or _has(text, r"spent"):
            return "CARD_SPEND"
    if kind == "INCOME":
        return "CREDIT"
    if kind in ("EXPENSE", "INVESTMENT"):
        return "DEBIT"
    if kind == "TRANSFER":
        return "DEBIT" if _has(text, DEBIT) else "CREDIT"
    d = re.search(DEBIT, text, re.I)
    c = re.search(CREDIT, text, re.I)
    if d and (not c or d.start() <= c.start()):
        return "DEBIT"
    if c:
        return "CREDIT"
    if _has(text, BALANCE):
        return "BALANCE_INFO"
    return "INFO"
