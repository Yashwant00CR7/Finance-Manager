# HANDOFF — Finance App (Finance Manager)

## Goal and current direction
Android/Kotlin app that books bank SMS alerts into an offline ledger. Current direction
(2026-11-24): (1) stop losing SMS while the phone is in a power-saving mode, (2) build a
labelled Indian bank SMS dataset and a CRF baseline, as groundwork for an ML fallback tier
behind the regex parser.

## Status now
- **Power-saving catch-up: built, unit-tested, APK built (v2.8.0). Not yet verified on a
  device in saver mode** — owner to test.
- **ML dataset + CRF (`ml/`): done for round one.** The set has 1,183 real scrubbed
  messages, 476 of them testable gold, plus 21,090 synthetic, over 57 bank groups. The
  CRF is trained, and the report is at `ml/reports/report.md`. On held-out banks it gets
  AMOUNT 98% and PAYEE 71% anchored recall, with rules at 24% for PAYEE.
  Transaction-vs-not is 91% accurate, and 28 of 476 non-transactions were called
  transactions. No app integration.

## Decisions that still hold
- Catch-up reads the inbox with READ_SMS (reverses the old "RECEIVE_SMS only" choice).
  Reason: OEM power-saving modes drop SMS_RECEIVED broadcasts; the inbox is the only place
  the message survives. No INTERNET, so data still cannot leave the phone.
- Dedupe via a `seen_messages` table (schema v9): SHA-256 of sender+body, plus the
  network sent-time in seconds. Both live receiver and scan claim through `MessageGate`.
  Rejected: timestamp watermark only (out-of-order messages slip or double).
- Scan triggers: app comes to foreground, power saver switched off (runtime receiver),
  and right after READ_SMS is granted. Rejected for now: boot, periodic WorkManager.
- First scan: from newest processed SMS txn/tray item minus 1 day, capped at 30 days.
  Later scans: from the last scan minus 10 minutes. Messages already on the ledger or in
  the tray by exact text are skipped (covers pre-v9 history).
- Recovered messages: one summary notification, not one per payment.
- ML: CRF first (sklearn-crfsuite), real public samples + ~30 synthetic variants per
  template, PII scrubbed to fakes, labels AMOUNT/ACCOUNT/PAYEE/DATE/TIME/BALANCE/
  AVAIL_LIMIT/REF_NO + message type, scored on held-out banks using real messages only.
  Coverage: ~30 banks, wallets/UPI apps, negatives (OTP, promo, loan offers, mandates).
  Card issuers (SBI Card, Amex as separate issuers) were not selected.
  The model will never book unattended; it would propose, the user confirms.

## Change log (newest first)
- 2026-11-24 · Widget now reads figures via `widget/WidgetFigures.kt` (same periods,
  rows, totals and budget lookup as the app, incl. future-dated rows); every widget redraw
  also requests an inbox catch-up. Scan treats same sender+text within a day as already
  seen (guards against phones whose date_sent differs from the radio timestamp). README
  privacy section updated for READ_SMS. Tests: `WidgetFiguresTest`, new CatchUpTest case.
- 2026-11-24 · `ml/` dataset, CRF baseline and report · `ml/smsml/*.py`, `ml/data/real.jsonl`,
  `ml/reports/`. Synthetic data and models are regenerated and gitignored.
- 2026-11-24 · Power-saving catch-up, schema v9, v2.8.0 · `domain/CatchUp.kt`,
  `domain/CatchUpAlert.kt`, `sms/InboxReader.kt`, `data/SchemaV9.kt`, `FinanceApplication.kt`,
  `MainActivity.kt`, `sms/SmsReceiver.kt`, `ui/Permissions.kt`, tests `CatchUpTest`,
  `MigrationV9Test`.
- Earlier history: see git log (v2.7.1 and before).

## Key files and docs
- Parser: `app/src/main/java/com/yk/finance/parser/` (RuleBasedParser, BankPatterns)
- Researched SMS evidence: `docs/bank-sms-formats.md`, test fixtures `BankFixtures.kt`
- Build: `gradle :app:testDebugUnitTest`, `gradle :app:assembleDebug` (no wrapper; system gradle)

## Open questions for the owner
- What exactly was wrong with the widget? Interpreted as: stale in power-saving mode, and
  figures that could differ from the app. If it was something else (looks, taps, layout),
  that is not yet addressed.
- Which phone/mode showed the problem (Samsung / Xiaomi / other)? Not answered; the fix is
  OEM-independent but on-device verification is needed.

## Next steps
1. Owner: install FinanceManager-v2.8.0-debug.apk, grant SMS (incl. read), enable saver
   mode, receive a bank SMS, disable saver / open app, confirm one summary notification
   and one ledger row.
2. ML options for the owner to pick: fix label noise and add "will be debited" negatives,
   gather more banks (KVB, Ujjivan, Airtel/Paytm PB), try DistilBERT/MobileBERT on the
   same data, or wire the CRF as a propose-only fourth parser tier.
