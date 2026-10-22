---
phase: 01-parser-and-learning
plan: 01
type: execute
tracer_first: true
---

# Plan: SMS Parser Fix, Generic Extraction, and Template Learning

## Objective
Fix the Union Bank parser failure reported by the user, generalize payee and disclaimer extraction across banks, provide safe pre-filling in the Inbox review tray (including Money In / Money Out auto-selection), and expand `TemplateLearner` with time, direction, and disclaimer slots so user-recorded transactions can automatically parse future SMS alerts without failure.

## Requirements Addressed
- REQ-01, REQ-02, REQ-03, REQ-04, REQ-05, REQ-06

## Execution Waves

### Wave 1: Tracer Slice (End-to-End Vertical Seam)
#### Task 1.1: Fix Union Bank Parser Rule and Generic Payee Extraction
- **Type:** tdd
- **Objective:** Fix `UnionRule` in `BankRules.kt` to handle optional `for`, `Union Bank of India A/c *...`, and extract `Fvg: <payee>` as payee. Also add generic `Fvg:` payee extraction in parser primitives so all banks benefit.
- **Target Files:**
  - `app/src/main/java/com/yk/finance/parser/BankRules.kt`
  - `app/src/main/java/com/yk/finance/parser/Primitives.kt`
  - `app/src/main/java/com/yk/finance/parser/BankPatterns.kt`
  - `app/src/test/java/com/yk/finance/ParserTest.kt`
- **Acceptance Criteria:**
  - The exact message from the user's screenshot (`Union Bank of India A/c *2209 Debited Rs:1755.00 on 22-10-2026 10:40:05 by Mob Bk ref no 629576250805, Fvg: AONE TRA Avl Bal Rs:5328.67. Not you?Call 18002333/SMS BLOCK 2209 to 8879365472`) parses successfully.
  - Direction is `Direction.DEBIT`, amount is `175500L`, account is `2209`, balance is `532867L`, ref is `629576250805`, and payee is `AONE TRA`.
  - Both credit and debit Union messages continue to parse.
- **Verification Command:** `gradle testDebugUnitTest --tests "com.yk.finance.ParserTest"`
- **Reversibility:** two-way

### Wave 2: Template Learning Expansion & Generalization
#### Task 2.1: Robust Template Learning with Time, Direction, and Disclaimer Slots
- **Type:** tdd
- **Objective:** Upgrade `TemplateLearner.kt` so that when a message is learned:
  1. Time strings (`\d{1,2}:\d{2}(?::\d{2})?`) are converted to `(?<time>...)` variable slots instead of being frozen as literal text.
  2. Debit/Credit words are converted to `(?<dir>...)` slots or dual-direction support so the learned pattern can parse future transactions regardless of time and direction.
  3. Trailing disclaimer/fraud text (e.g. `Not you? Call...`) is handled flexibly so changes in helpline numbers do not prevent matching.
- **Target Files:**
  - `app/src/main/java/com/yk/finance/domain/TemplateLearner.kt`
  - `app/src/test/java/com/yk/finance/TemplateLearnerTest.kt`
- **Acceptance Criteria:**
  - `TemplateLearner.learn` successfully produces a pattern for the Union Bank message.
  - A subsequent message with a different time (e.g. `14:30:15`), different amount, and different date automatically parses using the learned pattern.
  - A subsequent message with Money In (`Credited`) using the same format parses as `Direction.CREDIT`.
- **Verification Command:** `gradle testDebugUnitTest --tests "com.yk.finance.TemplateLearnerTest"`
- **Reversibility:** two-way

### Wave 3: Integration, Inbox UI Polish & Hardening
#### Task 3.1: Conservative Pre-filling in Inbox Review Card
- **Type:** auto
- **Objective:** In `InboxScreen.kt`, safely initialize:
  - `isDebit` based on explicit debit/credit keywords in the raw message (`credit`, `credited`, `received`, `refund`, `deposited` -> `false` / Money in; `debit`, `debited`, `spent`, `paid` -> `true` / Money out).
  - `amount` pre-filled if a single unambiguous currency amount is detected.
  - `payee` pre-filled if high-confidence `Fvg:` or `to` payee is detected.
- **Target Files:**
  - `app/src/main/java/com/yk/finance/ui/InboxScreen.kt`
  - `app/src/main/java/com/yk/finance/parser/Detectors.kt` (or extraction helper)
  - `app/src/test/java/com/yk/finance/InboxPreFillTest.kt`
- **Acceptance Criteria:**
  - Unparsed credit messages pre-select `Money in`; unparsed debit messages pre-select `Money out`.
  - Amount and payee are populated for high-confidence patterns without aggressive false positives.
- **Verification Command:** `gradle testDebugUnitTest`
- **Reversibility:** two-way

#### Task 3.2: Full Suite Verification & APK Compilation
- **Type:** auto
- **Objective:** Run the complete test suite across the repository and assemble the updated debug APK.
- **Target Files:**
  - Root `./FinanceManager-v2.7.0-debug.apk`
- **Acceptance Criteria:**
  - All unit tests pass cleanly.
  - Debug APK compiles without error.
- **Verification Command:** `gradle testDebugUnitTest && gradle assembleDebug`
- **Reversibility:** two-way
