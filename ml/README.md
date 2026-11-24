# smsml — Indian bank SMS dataset and CRF baseline

Groundwork for an ML fallback tier behind the app's regex parser. The model is meant to
*propose* a parse for messages no rule matched; the person confirms it, and the app's
TemplateLearner turns that confirmation into a regex. It is never meant to book
unattended.

Results: [`reports/report.md`](reports/report.md). Worst cases: `reports/failures.json`.

## Results so far (2026-11-24)

These are scores on banks held out of training, on real messages only.

- **Amount and balance extraction is already strong.** AMOUNT reaches 98% anchored recall
  and BALANCE 91%. The keyword rules score about the same on these, so the CRF adds little
  there.
- **Payee is where the model earns its place.** It finds 71% of payees on unseen banks,
  against 24% for the keyword rules.
- **Reference numbers are weakest, at 78%.** Bank-specific ID shapes don't transfer well.
- **Transaction or not is right 91% of the time.** 28 of 476 non-transactions were called
  transactions. Most were "will be debited" notices, beneficiary confirmations and info
  messages. A few were label noise. This is the main reason the model must only propose,
  never book.
- **Fine-grained type is weak, at 50% macro F1.** Rare types have too few real examples:
  6 ATM withdrawals, 5 bill-due notices, 4 balance-only messages.

## What is here

| path | what |
|---|---|
| `data/real.jsonl` | Real messages from public sources, PII scrubbed to fakes, labelled. Committed. |
| `data/synthetic.jsonl` | ~30 variants per real template with every slot refilled. Regenerated, not committed. |
| `smsml/extract.py` | Pulls raw cases from the sources below. Output has real PII, so it goes to `work/` (ignored). |
| `smsml/label.py` | Spans, bank and message type for each raw case. |
| `smsml/dataset.py` | Dedupe, label, scrub, write `data/real.jsonl`. |
| `smsml/synth.py` | Writes `data/synthetic.jsonl`. |
| `smsml/crf.py` | Tokenizer, CRF features, span tagger, message-type classifier. |
| `smsml/evaluate.py` | Held-out-bank CV, random split, rules baseline; writes `reports/`. |
| `smsml/predict.py` | Try the trained model on any message. |

## Record format

```json
{"id": "real-00042", "origin": "real", "source": "pennywise", "bank": "FEDERAL",
 "sender": "AD-FEDBNK-T", "type": "DEBIT", "gold": true,
 "text": "Debited Rs 6000 from a/c XX8146 on 24JUN2026 21:35 via NEFT to Jerry.Ref ...",
 "spans": [{"start": 11, "end": 15, "label": "AMOUNT", "by": "expect"}, ...],
 "signature": "debited rs <amount> from a/c <account> on <date> <time> via neft to ..."}
```

- **Labels:** `AMOUNT ACCOUNT PAYEE DATE TIME BALANCE AVAIL_LIMIT REF_NO`.
- **Types:** `DEBIT CREDIT CARD_SPEND CARD_PAYMENT ATM_WITHDRAWAL REFUND` are transactions.
  `FAILED MANDATE_FUTURE BILL_DUE OTP PROMO BALANCE_INFO INFO` are not.
- **`by`:** `expect` means the span sits on a value the source's own test asserted.
  `rule` means a keyword heuristic in `label.py` placed it. Only `expect` spans are
  independent evidence, and the report scores them separately.
- **`gold`:** the source asserted what the message means. Only gold messages are tested on.
- **`signature`:** the template with spans and digits masked. Splits never put one
  template on both sides.

## Sources

| source | what | labels |
|---|---|---|
| `sarim2000/pennywiseai-tracker` `parser-core/src/test/kotlin/` | per-bank test cases, Indian files only | expected amount, account, merchant, reference, balance, limit |
| `yassk29/TxnVault` `test/unit/sms_parsers_test.dart` | per-bank test cases | expected amount, account, counterparty, reference |
| `shivarya/expense-tracker-scraper` `data/sms-export.json` | a real inbox, personal-number senders dropped | none: silver, training only |
| `app/src/test/.../BankFixtures.kt` | the app's own cited fixtures and negatives | expected amount, account, payee |
| `docs/bank-sms-formats.md` | verbatim researched samples | none, except "must not parse" titles |

## Rebuild everything

The sources are downloaded as tarballs, not cloned. This machine's git config rewrites
HTTPS GitHub URLs to the work account's SSH host. Put each download in its own empty
folder.

```bash
cd ml
uv sync                                    # Python 3.12, sklearn-crfsuite
D=/path/to/downloads                       # holds pennywise/ txnvault/ shivarya/ extracted
uv run python -m smsml.extract --downloads $D --repo .. --out work/raw.jsonl
uv run python -m smsml.dataset --raw work/raw.jsonl --repo .. --out data/real.jsonl
uv run python -m smsml.synth   --real data/real.jsonl --out data/synthetic.jsonl
uv run python -m smsml.evaluate --data data --reports reports --models models
uv run python -m smsml.predict "Rs.500.00 debited from A/c XX1234 on 20-Oct-25 to swiggy@ybl"
```

## Known limits

- **Few gold messages per bank.** Many banks have 1 to 10 testable messages, so their
  per-bank scores are noisy.
- **Rule-placed spans.** DATE and TIME, and any span the source did not assert, come from
  `label.py`'s heuristics. Scores on them measure agreement with those heuristics, not
  truth.
- **Inbox dump bias.** The dump is one person's inbox. ICICI credit card alerts dominate
  it, and it supplies most of the OTP, promo and non-bank messages.
- **Residual personal data.** Scrubbing is pattern-based. Payees, UPI IDs, account and
  reference digits, names after "Dear", mobile numbers, digit-heavy IDs and link tokens
  are replaced. Free-text place names from the public dump, such as a hotel or a bus stop,
  can survive.
- **Not yet gathered.** Card issuers as separate senders, such as SBI Card or Amex, were
  not selected in scope. Some banks are also thin or missing: KVB, Ujjivan, ESAF, Airtel
  Payments Bank and Paytm Payments Bank.
