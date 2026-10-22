# PRD: Codebase Diagnosis, Performance & Edge Case Fixes

## Problem & User Intent
The user requested an exhaustive audit across the codebase for bugs similar to the salary cycle / period bug, performance issues, unexpected behavior, and edge cases using `/diagnose-bugs` discipline and executing fixes via the Ralph Loop.

## Diagnosed Issues & Root Causes

### 1. `BudgetResolver.rowToWrite` override mismatch
- **Root Cause**: `BudgetResolver.rowToWrite` searched only `it.periodStart == periodStart`. However, `BudgetMigration` remaps cycle-pinned overrides to `Periods.labelledMonthStart(periodStart)`. When a user edits a budget for the current cycle, `rowToWrite` fails to find the existing row and creates a duplicate override instead of updating it.
- **Fix**: Check `(it.periodStart == periodStart || it.periodStart == labelled)`.

### 2. `BudgetEvaluator.evaluateAndNotify` calendar month mismatch
- **Root Cause**: When an SMS arrives, `BudgetEvaluator.evaluateAndNotify` computes spending starting at `Periods.monthStartOf(now)` (1st of month). But the app and widgets measure the active salary cycle (from `cycleStartMillis`). As a result, 80%/100% budget alerts disagree with the in-app Budgets screen and SpendingWidget.
- **Fix**: Use `dao.cycleState()?.cycleStartMillis ?: CycleCalculator.seed(now).cycleStartMillis` as the period start for evaluations and alert recording.

### 3. Transfer leg amount mutation desync
- **Root Cause**: In `EntryScreen`, `amountUnlocked` was `!fromSms`. For manual transfers, `amountUnlocked` evaluated to `true`, allowing the user to change the amount on one leg of a transfer while the counterpart leg stayed unchanged, breaking balance integrity. Furthermore, `Repository.updateTransaction` lacked a guard against mutating `amountPaise` on `transferGroupId != null` transactions.
- **Fix**: Set `amountUnlocked = !fromSms && !isTransferLeg`, and reject `amountPaise != existing.amountPaise` for transfer groups in `Repository.updateTransaction`.

### 4. Budgets header and empty state when only Overall budget is set
- **Root Cause**: In `FinanceApp.kt`, the header totals were calculated by filtering only `categoryId != null`. If only an Overall budget (`categoryId == null`) was set, `TOTAL BUDGET` and `TOTAL SPENT` displayed `₹0.00`. Additionally, `BudgetsScreen` showed "No budgets for this cycle yet" even when an Overall budget was active.
- **Fix**: Fall back to the Overall budget limit/spend in the header when no per-category budgets exist, and clarify the empty state in `BudgetsScreen`.

### 5. Incomplete salary cycle history in Settings
- **Root Cause**: `FinanceViewModel.salaryCycles` mapped `Periods.from(it.boundaries, ...)`. Since `it.boundaries` in SQLite often only holds 1 or 2 boundaries, Settings only displayed 1 or 2 cycles instead of the full derived history available in `periods`.
- **Fix**: Connect `salaryCycles` to `periods`.

### 6. Flow recomputation & performance optimization
- **Root Cause**: `periods` and other derived StateFlows recompute on every `state` emission (even for unrelated updates like `reviews` or `rules`), triggering redundant downstream emissions and Compose recompositions.
- **Fix**: Add `.distinctUntilChanged()` to `periods` and relevant StateFlows.

### 7. Reversed date span edge case in `monthSubtitle`
- **Root Cause**: If system clock drifts or a cycle start date is ahead of current date (`today.isBefore(start)`), `monthSubtitle` generated a reversed string like `"30 Sep - 25 Sep so far"`.
- **Fix**: Display `"from $startFormatted"` when `today.isBefore(start)`.

---

## Verification & Backpressure
- Test Suite: `gradle testDebugUnitTest`
- Build: `gradle assembleDebug`

---

## Discrete Tasks

### Task 1: Fix `BudgetResolver.rowToWrite` and add unit test
- **Objective**: Match `rowToWrite` with `effective` so cycle-pinned overrides can be found by either `periodStart` or `labelledMonthStart`.
- **Target Files**: `app/src/main/java/com/yk/finance/domain/BudgetResolver.kt`, `app/src/test/java/com/yk/finance/BudgetOverrideTest.kt`

### Task 2: Align `BudgetEvaluator` with active salary cycle
- **Objective**: Evaluate SMS budget alerts against the active salary cycle window instead of calendar month.
- **Target Files**: `app/src/main/java/com/yk/finance/domain/BudgetEvaluator.kt`, `app/src/test/java/com/yk/finance/SalaryCycleAndTransferTest.kt`

### Task 3: Enforce transfer leg amount lock in UI & Repository
- **Objective**: Prevent editing amount on transfer legs to prevent account balance desynchronization.
- **Target Files**: `app/src/main/java/com/yk/finance/ui/EntryScreen.kt`, `app/src/main/java/com/yk/finance/domain/Repository.kt`, `app/src/test/java/com/yk/finance/SalaryCycleAndTransferTest.kt`

### Task 4: Fix Budgets header & empty state for Overall budget
- **Objective**: Display Overall budget in the header figures and prevent conflicting "No budgets" message when an overall budget is present.
- **Target Files**: `app/src/main/java/com/yk/finance/ui/FinanceApp.kt`, `app/src/main/java/com/yk/finance/ui/BudgetsScreen.kt`

### Task 5: Performance optimization with `distinctUntilChanged` & Settings `salaryCycles`
- **Objective**: Alias `salaryCycles` to `periods` and add `.distinctUntilChanged()` to prevent redundant Compose recompositions.
- **Target Files**: `app/src/main/java/com/yk/finance/ui/FinanceViewModel.kt`

### Task 6: Handle reversed dates in `monthSubtitle`
- **Objective**: Gracefully handle future or clock-drifted cycle starts.
- **Target Files**: `app/src/main/java/com/yk/finance/ui/Money.kt`, `app/src/test/java/com/yk/finance/SalaryCycleAndTransferTest.kt`

### Task 7: Full suite verification & APK build
- **Objective**: Ensure all tests pass and compile updated debug APK.
- **Target Files**: Root `./FinanceManager-v2.7.0-debug.apk`
