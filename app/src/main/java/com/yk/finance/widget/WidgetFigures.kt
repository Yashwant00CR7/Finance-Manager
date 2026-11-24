package com.yk.finance.widget

import com.yk.finance.data.FinanceDao
import com.yk.finance.domain.BudgetResolver
import com.yk.finance.domain.CycleCalculator
import com.yk.finance.domain.Ledger
import com.yk.finance.ui.FinanceViewModel

/**
 * The widget's figures, read exactly the way the app's own screens read them.
 *
 * The widget used to compute its own window - from the stored cycle start up to now -
 * while the app derives its periods from the salary credits in the ledger and leaves the
 * open period unbounded at the end. Those disagree whenever a salary was detected that
 * the stored cycle does not know about yet, and whenever a row is dated ahead of today:
 * the app counts it this month, the widget did not. A widget that disagrees with the app
 * is worse than no widget, so this goes through the same [FinanceViewModel.derivePeriods],
 * the same [Ledger.totals] and the same [BudgetResolver] lookup the Budgets screen uses.
 */
object WidgetFigures {

    suspend fun read(dao: FinanceDao, now: Long): WidgetState.Snapshot {
        val transactions = dao.allTransactions()
        val accounts = dao.allAccounts()
        val periods = FinanceViewModel.derivePeriods(
            transactions = transactions,
            recordedBoundaries = dao.allBoundaries(),
            cycleState = dao.cycleState(),
            accounts = accounts,
            now = now,
        )
        // The period the app opens onto: the open one, never a future month.
        val period = periods.firstOrNull { it.isOpen } ?: periods.lastOrNull()
        val start = period?.startMillis ?: CycleCalculator.seed(now).cycleStartMillis
        val rows = if (period == null) transactions else transactions.filter { period.contains(it.occurredAt) }
        val totals = Ledger.totals(rows)

        val budget = BudgetResolver
            .effective(dao.allBudgets(), start)
            .firstOrNull { it.budget.categoryId == null }
            ?.budget
            ?.limitPaise

        return WidgetState.of(
            cycleStartMillis = start,
            nowMillis = now,
            spentPaise = totals.expensePaise,
            budgetPaise = budget,
            incomePaise = totals.incomePaise,
            totalBalancePaise = accounts.sumOf { it.currentBalancePaise },
        )
    }
}
