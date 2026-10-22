# Phase Context: Hard Stress Test Suite for Parser and Learning Lifecycle

## Problem & User Intent
The user requested a new set of hard tests on the parser and its learning capability to rigorously stress-test and prove all changes made in Phase 1 across edge cases, complex payees, multi-number density, negative/anti-fraud boundaries, and end-to-end ingestion learning.

## Mental Model & Core Abstractions
- **Adversarial SMS Shapes**: Real Indian banking SMS can contain lakhs delimiters (`1,25,000.50`), complex payee names with initials, slashes, or ampersands (`MR. A.B. SHARMA & CO.`, `GOOGLE*SERVICES/123`), dense numeric clusters (multiple phone numbers, ref IDs, balances, account masks), and inconsistent whitespace.
- **Defensive Invariants**: Declined transactions, mandate requests, and phishing/unrelated alerts must never match financial rules or learned templates.
- **Full Learning Loop**: When an unrecognized alert arrives, it must move cleanly from Ingestor -> Review -> TemplateLearner -> SQLite DAO -> future automated ingestion of both Money In and Money Out.

## Locked Architectural Decisions
- Create a dedicated stress test suite: `app/src/test/java/com/yk/finance/ParserAndLearningStressTest.kt`.
- Test real Room DAO in-memory database or mock DAO for the end-to-end learning lifecycle.
- Keep version strictly as `v2.7.0` (versionCode 14, versionName "2.7.0").
- Use strict TDD: identify any edge cases that fail, fix them cleanly, and verify 100% test pass.

## Verification & Backpressure Seams
- Unit Test Suite: `gradle testDebugUnitTest --tests "com.yk.finance.ParserAndLearningStressTest"`
- Full Test Suite: `gradle testDebugUnitTest`
- Debug Build: `gradle assembleDebug`
