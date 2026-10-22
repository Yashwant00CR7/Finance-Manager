# Requirements: SMS Parser Fix, Generic Extraction, and Template Learning

- [x] **REQ-01**: Parse Union Bank transaction SMS accurately (including the reported real alert with `Debited Rs:1755.00` without `for`, `Union Bank of India A/c *...`, and `Fvg: AONE TRA` as payee).
- [x] **REQ-02**: Support both Money Out (`Debited`) and Money In (`Credited`) in Union Bank rules with accurate balance, reference, date, and time extraction.
- [x] **REQ-03**: Generic payee extraction across all banks supporting `Fvg:` / `Favoring:` as well as standard payee patterns.
- [x] **REQ-04**: Smart conservative pre-filling in `InboxScreen`:
  - Pre-fill amount when a clear monetary figure exists.
  - Auto-select `Money in` (Credit) vs `Money out` (Debit) based on explicit keywords in the message text.
  - Pre-fill payee when identifiable with high confidence (`Fvg:`, etc.), leaving blank if uncertain.
- [x] **REQ-05**: Enhance `TemplateLearner` so learned patterns:
  - Capture time (`\d{1,2}:\d{2}(?::\d{2})?`) as a variable slot so time changes do not break pattern matching.
  - Support dual-direction slots (`(?<dir>...)`) so learned alert formats can parse both Money In and Money Out.
  - Relax trailing disclaimers/helpline strings so future alerts with disclaimer variations match cleanly.
- [x] **REQ-06**: Verify all changes with comprehensive JVM unit tests and build the updated debug APK (`gradle assembleDebug`).
