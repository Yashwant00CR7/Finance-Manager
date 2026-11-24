package com.yk.finance

import com.yk.finance.data.Account
import com.yk.finance.data.AccountKind
import com.yk.finance.data.Txn
import com.yk.finance.domain.CycleCalculator
import com.yk.finance.domain.Ledger
import com.yk.finance.parser.Direction
import com.yk.finance.ui.FinanceViewModel
import com.yk.finance.widget.WidgetFigures
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The widget shows what the app shows: same period, same rows, same totals.
 */
class WidgetFiguresTest {

    private val dao = InMemoryDao()
    private val now = 1_792_000_000_000L
    private val day = 24 * 60 * 60_000L

    private suspend fun account(balance: Long, token: String = "2209"): Long = dao.insertAccount(
        Account(displayName = "Union", bank = "UNION", accountToken = token, kind = AccountKind.BANK,
            openingBalancePaise = balance, currentBalancePaise = balance),
    )

    private suspend fun spend(accountId: Long, paise: Long, at: Long) = dao.insertTxn(
        Txn(accountId = accountId, direction = Direction.DEBIT, amountPaise = paise, occurredAt = at,
            payee = "SHOP", reference = null, countsAsSpending = true),
    )

    /** What the app's Budgets header would say for the period it opens onto. */
    private suspend fun appSpent(): Long {
        val periods = FinanceViewModel.derivePeriods(
            dao.allTransactions(), dao.allBoundaries(), dao.cycleState(), dao.allAccounts(), now,
        )
        val open = periods.first { it.isOpen }
        return Ledger.totals(dao.allTransactions().filter { open.contains(it.occurredAt) }).expensePaise
    }

    @Test
    fun `a row dated later this period counts on the widget as it does in the app`() = runTest {
        dao.cycle = CycleCalculator.seed(now)
        val acct = account(50_000_00)
        spend(acct, 1_000_00, now - day)
        spend(acct, 250_00, now + 60_000) // dated a minute ahead, as a parsed SMS date can be

        val widget = WidgetFigures.read(dao, now)

        assertEquals(appSpent(), widget.spentPaise)
        assertEquals(1_250_00, widget.spentPaise)
    }

    @Test
    fun `the balance is every account's balance, as on the Accounts screen`() = runTest {
        dao.cycle = CycleCalculator.seed(now)
        account(10_000_00)
        account(2_500_00, token = "742")

        assertEquals(12_500_00, WidgetFigures.read(dao, now).totalBalancePaise)
    }
}
