"""Train and score the CRF baseline; write reports/report.md and reports/metrics.json.

    python -m smsml.evaluate --data data --reports reports --models models

Three experiments, all scored only on gold *real* messages:

1. Held-out banks (the headline): 5 folds over banks. A bank's real and synthetic messages
   are all on one side, so the test banks are formats the model never saw.
2. Random split by template: 80/20 over template signatures, so a template is never on
   both sides. Shows how well it does on banks it knows.
3. Rule baseline: the keyword heuristics alone (smsml.label.heuristic_spans) on the same
   held-out messages, without the source's expected values.

Span scores are exact matches of (start, end, label). "Anchored" scores count only spans
placed from values the source itself asserted, which are the only spans not produced by
this project's own heuristics; they are the trustworthy number.
"""

from __future__ import annotations

import argparse
import json
import pickle
import random
import time
from collections import Counter, defaultdict
from pathlib import Path

from sklearn.metrics import accuracy_score, f1_score
from sklearn.model_selection import GroupKFold

from smsml import label as L
from smsml.crf import SpanTagger, type_classifier, type_text


def load(path: Path) -> list[dict]:
    return [json.loads(l) for l in path.open(encoding="utf-8")]


def key(s: dict) -> tuple:
    return (s["start"], s["end"], s["label"])


class Tally:
    def __init__(self):
        self.tp, self.fp, self.fn = Counter(), Counter(), Counter()
        self.a_hit, self.a_all = Counter(), Counter()

    def add(self, gold: list[dict], pred: list[dict]) -> None:
        g = {key(s) for s in gold}
        p = {key(s) for s in pred}
        for k in p & g:
            self.tp[k[2]] += 1
        for k in p - g:
            self.fp[k[2]] += 1
        for k in g - p:
            self.fn[k[2]] += 1
        for s in gold:
            if s.get("by") == "expect":
                self.a_all[s["label"]] += 1
                if key(s) in p:
                    self.a_hit[s["label"]] += 1

    def table(self) -> dict:
        out = {}
        for lab in L.LABELS:
            tp, fp, fn = self.tp[lab], self.fp[lab], self.fn[lab]
            p = tp / (tp + fp) if tp + fp else None
            r = tp / (tp + fn) if tp + fn else None
            f = 2 * p * r / (p + r) if p and r else (0.0 if p is not None and r is not None else None)
            ar = self.a_hit[lab] / self.a_all[lab] if self.a_all[lab] else None
            out[lab] = {"precision": p, "recall": r, "f1": f, "support": tp + fn,
                        "anchored_recall": ar, "anchored_support": self.a_all[lab]}
        tp, fp, fn = sum(self.tp.values()), sum(self.fp.values()), sum(self.fn.values())
        p, r = tp / max(tp + fp, 1), tp / max(tp + fn, 1)
        out["ALL"] = {"precision": p, "recall": r, "f1": 2 * p * r / max(p + r, 1e-9), "support": tp + fn,
                      "anchored_recall": sum(self.a_hit.values()) / max(sum(self.a_all.values()), 1),
                      "anchored_support": sum(self.a_all.values())}
        return out


def is_txn(t: str) -> bool:
    return t in L.TRANSACTION_TYPES


def type_scores(gold: list[str], pred: list[str]) -> dict:
    g_txn = [is_txn(t) for t in gold]
    p_txn = [is_txn(t) for t in pred]
    false_txn = sum(1 for g, p in zip(g_txn, p_txn) if p and not g)
    missed_txn = sum(1 for g, p in zip(g_txn, p_txn) if g and not p)
    return {
        "accuracy": accuracy_score(gold, pred),
        "macro_f1": f1_score(gold, pred, average="macro", zero_division=0),
        "txn_accuracy": accuracy_score(g_txn, p_txn),
        "non_txn_called_txn": false_txn,
        "txn_called_non_txn": missed_txn,
        "n": len(gold),
    }


def train(train_recs: list[dict]) -> tuple[SpanTagger, object]:
    tagger = SpanTagger().fit(train_recs)
    clf = type_classifier().fit([type_text(r) for r in train_recs], [r["type"] for r in train_recs])
    return tagger, clf


def run_fold(train_recs, test_recs):
    tagger, clf = train(train_recs)
    spans = tagger.predict([r["text"] for r in test_recs])
    types = list(clf.predict([type_text(r) for r in test_recs]))
    return spans, types


def rule_baseline(r: dict) -> list[dict]:
    sp: list[dict] = []
    L.heuristic_spans(r["text"], sp)
    return sp


def save_final(records: list[dict], models: Path) -> None:
    """Trains on everything and pickles both models. sklearn-crfsuite pickles the model bytes."""
    tagger, clf = train(records)
    with (models / "span_crf.pkl").open("wb") as fh:
        pickle.dump(tagger, fh)
    with (models / "type_clf.pkl").open("wb") as fh:
        pickle.dump(clf, fh)


def fmt(x, pct=True):
    if x is None:
        return "–"
    return f"{100 * x:.1f}" if pct else str(x)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, default=Path("data"))
    ap.add_argument("--reports", type=Path, default=Path("reports"))
    ap.add_argument("--models", type=Path, default=Path("models"))
    ap.add_argument("--folds", type=int, default=5)
    ap.add_argument("--final-only", action="store_true", help="skip evaluation; just train and save the model")
    a = ap.parse_args()
    a.reports.mkdir(exist_ok=True)
    a.models.mkdir(exist_ok=True)
    if a.final_only:
        save_final(load(a.data / "real.jsonl") + load(a.data / "synthetic.jsonl"), a.models)
        return

    real = load(a.data / "real.jsonl")
    synth = load(a.data / "synthetic.jsonl")
    by_parent = defaultdict(list)
    for s in synth:
        by_parent[s["parent"]].append(s)
    real_by_id = {r["id"]: r for r in real}
    sig_of_synth = {s["id"]: real_by_id[s["parent"]]["signature"] for s in synth}

    metrics: dict = {"dataset": {
        "real": len(real), "gold_real": sum(r["gold"] for r in real), "synthetic": len(synth),
        "banks": len({r["bank"] for r in real}),
        "types_real": Counter(r["type"] for r in real).most_common(),
        "banks_real": Counter(r["bank"] for r in real).most_common(),
    }}
    failures: list[dict] = []

    # ---------------- 1. held-out banks ----------------
    t0 = time.time()
    banks = sorted({r["bank"] for r in real})
    rng = random.Random(7)
    rng.shuffle(banks)
    # Spread the big banks across folds: assign greedily by size.
    size = Counter(r["bank"] for r in real)
    folds = [[] for _ in range(a.folds)]
    load_ = [0] * a.folds
    for b in sorted(banks, key=lambda b: -size[b]):
        i = load_.index(min(load_))
        folds[i].append(b)
        load_[i] += size[b]

    ho_tally, rule_tally = Tally(), Tally()
    ho_gold_types, ho_pred_types = [], []
    per_bank = defaultdict(lambda: {"n": 0, "amount_hit": 0, "amount_all": 0, "acct_hit": 0,
                                    "acct_all": 0, "type_ok": 0})
    for k, test_banks in enumerate(folds):
        tb = set(test_banks)
        tr = [r for r in real if r["bank"] not in tb] + [s for s in synth if s["bank"] not in tb]
        te = [r for r in real if r["bank"] in tb and r["gold"]]
        if not te:
            continue
        spans, types = run_fold(tr, te)
        for r, sp, ty in zip(te, spans, types):
            ho_tally.add(r["spans"], sp)
            rule_tally.add(r["spans"], rule_baseline(r))
            ho_gold_types.append(r["type"])
            ho_pred_types.append(ty)
            pb = per_bank[r["bank"]]
            pb["n"] += 1
            pb["type_ok"] += int(ty == r["type"])
            pset = {key(s) for s in sp}
            for s in r["spans"]:
                if s.get("by") != "expect":
                    continue
                if s["label"] == "AMOUNT":
                    pb["amount_all"] += 1
                    pb["amount_hit"] += int(key(s) in pset)
                if s["label"] == "ACCOUNT":
                    pb["acct_all"] += 1
                    pb["acct_hit"] += int(key(s) in pset)
            missed = [s for s in r["spans"] if s.get("by") == "expect" and key(s) not in pset]
            wrong_txn = is_txn(ty) != is_txn(r["type"])
            if missed or wrong_txn:
                failures.append({"bank": r["bank"], "text": r["text"], "gold_type": r["type"],
                                 "pred_type": ty, "missed": [(r["text"][s["start"]:s["end"]], s["label"]) for s in missed],
                                 "pred": [(r["text"][s["start"]:s["end"]], s["label"]) for s in sp]})
        print(f"fold {k + 1}/{a.folds}: {len(te)} test msgs, banks={sorted(tb)} ({time.time() - t0:.0f}s)", flush=True)

    metrics["held_out_banks"] = {"spans": ho_tally.table(), "types": type_scores(ho_gold_types, ho_pred_types),
                                 "folds": folds}
    metrics["rule_baseline"] = {"spans": rule_tally.table()}
    metrics["per_bank"] = dict(per_bank)

    # ---------------- 2. random split by template ----------------
    sigs = sorted({r["signature"] for r in real})
    random.Random(11).shuffle(sigs)
    test_sigs = set(sigs[: len(sigs) // 5])
    tr = [r for r in real if r["signature"] not in test_sigs] + \
         [s for s in synth if sig_of_synth[s["id"]] not in test_sigs]
    te = [r for r in real if r["signature"] in test_sigs and r["gold"]]
    spans, types = run_fold(tr, te)
    rs_tally = Tally()
    for r, sp in zip(te, spans):
        rs_tally.add(r["spans"], sp)
    metrics["random_split"] = {"spans": rs_tally.table(), "types": type_scores([r["type"] for r in te], types)}
    print(f"random split: {len(te)} test msgs ({time.time() - t0:.0f}s)", flush=True)

    # ---------------- final model on everything ----------------
    save_final(real + synth, a.models)
    print(f"final model trained ({time.time() - t0:.0f}s)", flush=True)

    (a.reports / "metrics.json").write_text(json.dumps(metrics, indent=2, default=str))
    failures.sort(key=lambda f: (-len(f["missed"]) - 2 * (f["gold_type"] != f["pred_type"]), f["bank"]))
    (a.reports / "failures.json").write_text(json.dumps(failures, indent=2, ensure_ascii=False))
    write_report(a.reports / "report.md", metrics, failures)
    print("wrote", a.reports / "report.md")


def write_report(path: Path, m: dict, failures: list[dict]) -> None:
    d = m["dataset"]
    lines = [
        "# CRF baseline — Indian bank SMS",
        "",
        "Generated by `python -m smsml.evaluate`. All scores are on gold **real** messages only;",
        "synthetic messages are training data and never tested on.",
        "",
        "## Dataset",
        "",
        f"| | count |\n|---|---|\n| real messages | {d['real']} |\n| gold real (testable) | {d['gold_real']} |\n"
        f"| synthetic | {d['synthetic']} |\n| bank groups | {d['banks']} |",
        "",
        "Real messages by type: " + ", ".join(f"{t} {n}" for t, n in d["types_real"]),
        "",
        "## Spans",
        "",
        "Exact-match precision / recall / F1 (%). *Anchored recall* counts only spans placed on",
        "values the source itself asserted — the independent evidence.",
        "",
        "| label | held-out P | held-out R | held-out F1 | held-out anchored R (n) | rules-only anchored R | random-split F1 | random-split anchored R |",
        "|---|---|---|---|---|---|---|---|",
    ]
    ho, rb, rs = m["held_out_banks"]["spans"], m["rule_baseline"]["spans"], m["random_split"]["spans"]
    for lab in L.LABELS + ["ALL"]:
        h, b, r = ho[lab], rb[lab], rs[lab]
        lines.append(f"| {lab} | {fmt(h['precision'])} | {fmt(h['recall'])} | {fmt(h['f1'])} | "
                     f"{fmt(h['anchored_recall'])} ({h['anchored_support']}) | {fmt(b['anchored_recall'])} | "
                     f"{fmt(r['f1'])} | {fmt(r['anchored_recall'])} |")
    ht, rt = m["held_out_banks"]["types"], m["random_split"]["types"]
    lines += [
        "",
        "## Message type",
        "",
        "| | held-out banks | random split |",
        "|---|---|---|",
        f"| accuracy | {fmt(ht['accuracy'])} | {fmt(rt['accuracy'])} |",
        f"| macro F1 | {fmt(ht['macro_f1'])} | {fmt(rt['macro_f1'])} |",
        f"| transaction vs not, accuracy | {fmt(ht['txn_accuracy'])} | {fmt(rt['txn_accuracy'])} |",
        f"| non-transactions called transactions | {ht['non_txn_called_txn']} / {ht['n']} | {rt['non_txn_called_txn']} / {rt['n']} |",
        f"| transactions called non-transactions | {ht['txn_called_non_txn']} / {ht['n']} | {rt['txn_called_non_txn']} / {rt['n']} |",
        "",
        "## Per bank (held-out)",
        "",
        "| bank | test msgs | AMOUNT anchored R | ACCOUNT anchored R | type acc |",
        "|---|---|---|---|---|",
    ]
    for bank, pb in sorted(m["per_bank"].items(), key=lambda kv: -kv[1]["n"]):
        ar = pb["amount_hit"] / pb["amount_all"] if pb["amount_all"] else None
        cr = pb["acct_hit"] / pb["acct_all"] if pb["acct_all"] else None
        lines.append(f"| {bank} | {pb['n']} | {fmt(ar)} | {fmt(cr)} | {fmt(pb['type_ok'] / pb['n'])} |")
    lines += ["", "## Worst failures (held-out)", ""]
    for f in failures[:25]:
        lines.append(f"- **{f['bank']}** gold `{f['gold_type']}` → predicted `{f['pred_type']}`; "
                     f"missed {f['missed']}")
        lines.append(f"  > {f['text'][:240].replace(chr(10), ' ')}")
    path.write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
