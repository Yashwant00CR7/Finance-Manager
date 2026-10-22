---
phase: 02-hard-stress-tests
plan: 01
type: execute
tracer_first: true
---

# Plan: Hard Stress Test Suite for Parser and Learning Lifecycle

## Objective
Author a rigorous, comprehensive stress test battery in `ParserAndLearningStressTest.kt` verifying edge case handling for the SMS parser (lakhs formatting, complex payees, multi-number density, irregular whitespace), defensive filters (declined/failed transactions, mandate offers, phishing resistance), and the full end-to-end template learning lifecycle (Ingest -> Review -> Learn & Persist -> Auto-ingest future debits & credits).

## Requirements Addressed
- REQ-STRESS-01, REQ-STRESS-02, REQ-STRESS-03, REQ-STRESS-04

## Execution Waves

### Wave 1: Parser & Defensive Hard Battery
#### Task 1.1: Author Parser Stress Tests and Negative Filter Guards
- **Type:** tdd
- **Objective:** Add tests covering:
  - Union Bank and generic parser with Indian lakhs formatting (`Rs:1,50,000.00`, `INR 25,000`, `₹10.50`).
  - Complex payees containing dots, slashes, initials, and ampersands (`MR. A.B. SHARMA & CO.`, `GOOGLE*SERVICES/123`, `IRCTC/APP`).
  - High density of numeric tokens (account masks, amounts, timestamps, ref IDs, balances, helpline numbers).
  - Defensive rejection of declined/failed transactions, mandate notifications, and reward phishing.
- **Target Files:**
  - `app/src/test/java/com/yk/finance/ParserAndLearningStressTest.kt`
  - (Any parser files if edge case fixes are needed)
- **Acceptance Criteria:**
  - All parser stress cases parse with correct amount, account, direction, ref, bal, and payee.
  - All negative cases are rejected (never Parsed).
- **Verification Command:** `gradle testDebugUnitTest --tests "com.yk.finance.ParserAndLearningStressTest"`

### Wave 2: End-to-End Learning Lifecycle Battery
#### Task 2.1: Author Learning Lifecycle Integration Tests
- **Type:** tdd
- **Objective:** Add integration tests for the full learning cycle:
  - Unrecognized message lands in `PendingReview`.
  - User records review via `Repository.recordFromReview` -> `TemplateLearner.learn` stores pattern in `LearnedPattern` database table.
  - Subsequent alert with different amount, date, time, ref, and payee auto-books into `Txn` ledger without human intervention.
  - Direction switching: A subsequent Money In (`Credited`) alert matches and records as `Direction.CREDIT` with positive balance change.
  - False-positive resistance: Unrelated alerts do not match the learned pattern.
- **Target Files:**
  - `app/src/test/java/com/yk/finance/ParserAndLearningStressTest.kt`
  - (Any domain files if lifecycle fixes are needed)
- **Acceptance Criteria:**
  - Complete lifecycle runs and passes with in-memory Room database.
- **Verification Command:** `gradle testDebugUnitTest --tests "com.yk.finance.ParserAndLearningStressTest"`

### Wave 3: Full Suite Verification & APK Compilation
#### Task 3.1: Full Test Suite Verification and APK Build
- **Type:** auto
- **Objective:** Run the complete test suite across the repository and build `./FinanceManager-v2.7.0-debug.apk` without bumping version.
- **Target Files:**
  - Root `./FinanceManager-v2.7.0-debug.apk`
- **Acceptance Criteria:**
  - 100% of all unit tests pass.
  - APK builds cleanly without errors.
- **Verification Command:** `gradle testDebugUnitTest && gradle assembleDebug`
