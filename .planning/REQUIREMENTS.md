# Requirements: Hard Stress Test Suite for Parser and Learning Lifecycle

- [x] **REQ-STRESS-01**: Parser Hard Battery — Test complex Union Bank and generic transactions:
  - Indian lakhs amount formatting (`1,50,000.00`, `25,000`, `10.50`).
  - Payees with initials, dots, slashes, ampersands (`MR. A.B. SHARMA & CO.`, `GOOGLE*SERVICES/123`, `IRCTC/APP`, `payment.bills@okhdfcbank`).
  - Dense multi-number messages with account numbers, amounts, timestamps, ref IDs, balances, and helpline numbers.
  - Irregular whitespace (newlines, tabs, double spaces, missing space after commas).
- [x] **REQ-STRESS-02**: Negative & Anti-Phishing Filter Battery — Verify:
  - Declined and failed transactions are never booked as completed transfers.
  - Mandate setup requests and balance inquiries do not produce false positive transactions.
  - Completely unrelated or phishing SMS do not falsely match learned templates from another bank.
- [x] **REQ-STRESS-03**: End-to-End Learning Lifecycle Battery:
  - Simulate incoming unparsed SMS -> queued in `PendingReview`.
  - Simulate manual record via `Repository.recordFromReview` -> template synthesized and stored in DAO.
  - Ingest subsequent alerts with different timestamps, amounts, dates, references, and payees -> automatically ingested into ledger.
  - Test direction switching: subsequent alert with Money In (`Credited`) or Money Out (`Debited`) correctly matches and books with the right `direction`.
- [x] **REQ-STRESS-04**: Full Verification & Build:
  - All stress tests and regression tests pass (100% test pass).
  - Compile `./FinanceManager-v2.7.0-debug.apk` without version bump.
