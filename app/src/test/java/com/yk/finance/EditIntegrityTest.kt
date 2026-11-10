package com.yk.finance

import com.yk.finance.data.Account
import com.yk.finance.data.AccountKind
import com.yk.finance.data.Category
import com.yk.finance.data.CycleBoundary
import com.yk.finance.data.CycleState
import com.yk.finance.data.Sharing
import com.yk.finance.data.Txn
import com.yk.finance.data.TxnSource
import com.yk.finance.domain.CycleBackfill
import com.yk.finance.domain.CycleCalculator
import com.yk.finance.domain.SplitPart
import com.yk.finance.parser.Direction
import com.yk.finance.ui.FinanceViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Edits that used to corrupt what they touched: a transfer leg flipped by its own edit
 * screen, a debt turned back into spending by a note, a rule that could never be
 * corrected, a second Cash wallet, and a payday recorded twice.
 */
class EditIntegrityTest {

    private fun millis(year: Int, month: Int, day: Int): Long =
        CycleCalculator.startOfDayMillis(LocalDate.of(year, month, day))

    private suspend fun InMemoryDao.bank(name: String, balance: Long = 10_000_00): Account {
        val id = insertAccount(
            Account(displayName = name, bank = "UNBOUND", accountToken = "PENDING-$name", currentBalancePaise = balance),
        )
        return accountById(id)!!
    }

    @Test
    fun `editing the note on a transfer's credit leg keeps its direction and both balances`() = runBlocking {
        val dao = InMemoryDao()
        val repo = dao.repository()
        val from = dao.bank("Savings")
        val to = dao.bank("Current")
        repo.addTransfer(from.id, to.id, 3_000_00, note = null, occurredAt = millis(2026, 10, 2))
        val creditLeg = dao.txns.values.single { it.direction == Direction.CREDIT }

        // What the edit screen used to send for any non-income row: DEBIT.
        val written = repo.updateTransaction(
            id = creditLeg.id,
            accountId = creditLeg.accountId,
            direction = Direction.DEBIT,
            amountPaise = creditLeg.amountPaise,
            occurredAt = creditLeg.occurredAt,
            payee = creditLeg.payee,
            note = "rent money",
            categoryId = null,
        )

        assertTrue(written)
        val after = dao.txns.getValue(creditLeg.id)
        assertEquals(Direction.CREDIT, after.direction)
        assertEquals("rent money", after.note)
        assertFalse(after.countsAsSpending)
        assertEquals(7_000_00L, dao.accountById(from.id)!!.currentBalancePaise)
        assertEquals(13_000_00L, dao.accountById(to.id)!!.currentBalancePaise)
    }

    @Test
    fun `a transfer leg cannot be moved to another account by an edit`() = runBlocking {
        val dao = InMemoryDao()
        val repo = dao.repository()
        val from = dao.bank("Savings")
        val to = dao.bank("Current")
        val other = dao.bank("Other")
        repo.addTransfer(from.id, to.id, 1_000_00, note = null, occurredAt = millis(2026, 10, 2))
        val debitLeg = dao.txns.values.single { it.direction == Direction.DEBIT }

        repo.updateTransaction(
            id = debitLeg.id,
            accountId = other.id,
            direction = Direction.DEBIT,
            amountPaise = debitLeg.amountPaise,
            occurredAt = debitLeg.occurredAt,
            payee = debitLeg.payee,
            note = null,
            categoryId = null,
        )

        assertEquals(from.id, dao.txns.getValue(debitLeg.id).accountId)
        assertEquals(9_000_00L, dao.accountById(from.id)!!.currentBalancePaise)
        assertEquals(10_000_00L, dao.accountById(other.id)!!.currentBalancePaise)
    }

    @Test
    fun `fixing the note on a lent split part does not turn it back into spending`() = runBlocking {
        val dao = InMemoryDao()
        val repo = dao.repository()
        val food = dao.insertCategoryReturningId(Category(name = "Food"))
        val lent = dao.insertCategoryReturningId(Category(name = "For Friend Return Later", sharing = Sharing.LENT))
        val account = dao.bank("Savings")
        val id = repo.addSpend(account.id, 320_00, "JUICE BAR", null, food, millis(2026, 10, 3))
        repo.applySplit(
            dao.txnById(id)!!,
            listOf(SplitPart(food, 200_00), SplitPart(lent, 120_00, owedBy = "Arun")),
        )
        val part = dao.txns.values.single { it.categoryId == lent }
        assertFalse(part.countsAsSpending)

        repo.updateTransaction(
            id = part.id,
            accountId = part.accountId,
            direction = Direction.DEBIT,
            amountPaise = part.amountPaise,
            occurredAt = part.occurredAt,
            payee = part.payee,
            note = "lunch for Arun",
            categoryId = lent,
        )

        val after = dao.txns.getValue(part.id)
        assertFalse("a debt someone owes you is not your spending", after.countsAsSpending)
        assertEquals("lunch for Arun", after.note)
        // Moving the part to an ordinary category makes it spending again.
        repo.categorise(after, food)
        assertTrue(dao.txns.getValue(part.id).countsAsSpending)
    }

    @Test
    fun `correcting a payee a second time rewrites its rule`() = runBlocking {
        val dao = InMemoryDao()
        val repo = dao.repository()
        val food = dao.insertCategoryReturningId(Category(name = "Food"))
        val shopping = dao.insertCategoryReturningId(Category(name = "Shopping"))
        val account = dao.bank("Savings")
        val id = repo.addSpend(account.id, 500_00, "DMART", null, null, millis(2026, 10, 3))

        repo.categorise(dao.txnById(id)!!, food)
        assertEquals(food, dao.ruleFor("DMART")!!.categoryId)
        val firstRuleId = dao.ruleFor("DMART")!!.id

        repo.categorise(dao.txnById(id)!!, shopping)
        val rule = dao.ruleFor("DMART")!!
        assertEquals("the second decision replaces the first", shopping, rule.categoryId)
        assertEquals("the same row, rewritten, not a duplicate", firstRuleId, rule.id)
        assertEquals(1, dao.rules.size)
    }

    @Test
    fun `adding cash twice leaves one wallet and does not fail`() = runBlocking {
        val dao = InMemoryDao()
        val repo = dao.repository()
        repo.createDeclaredAccount("Cash", 500_00, isCash = true)
        repo.createDeclaredAccount("Wallet", 900_00, isCash = true)

        val wallets = dao.accounts.values.filter { it.kind == AccountKind.CASH }
        assertEquals(1, wallets.size)
        assertEquals(500_00L, wallets.single().currentBalancePaise)
    }

    @Test
    fun `recording a salary boundary retires the estimated one beside it`() = runBlocking {
        val dao = InMemoryDao()
        val fallback = millis(2026, 9, 30)
        val salary = millis(2026, 10, 1)
        val earlierRealSalary = millis(2026, 9, 1)
        CycleBackfill.record(dao, fallback, fromSalary = false)
        CycleBackfill.record(dao, earlierRealSalary, fromSalary = true)

        CycleBackfill.record(dao, salary, fromSalary = true, txnId = 42)

        val starts = dao.allBoundaries().map { it.startMillis }
        assertEquals(listOf(earlierRealSalary, salary), starts)
        assertTrue(dao.allBoundaries().none { it.inferred })
    }

    @Test
    fun `derivePeriods keeps the observed salary boundary over an estimate recorded near it`() {
        val fallback = millis(2026, 9, 30)
        val salary = millis(2026, 10, 1)
        val recorded = listOf(
            CycleBoundary(startMillis = millis(2026, 8, 31), source = "SALARY", inferred = false),
            // The estimate landed in the table first, as it does on a live phone.
            CycleBoundary(startMillis = fallback, source = "FALLBACK", inferred = true),
            CycleBoundary(startMillis = salary, source = "SALARY", inferred = false, txnId = 7),
        )
        val state = CycleState(cycleStartMillis = salary, salaryAmountPaise = 50_000_00, lastRollMillis = salary)
        val salaryCredit = Txn(
            id = 7, accountId = 1, direction = Direction.CREDIT, amountPaise = 50_000_00,
            occurredAt = salary, payee = "ACME PAYROLL", reference = null, countsAsSpending = false,
        )

        val periods = FinanceViewModel.derivePeriods(
            transactions = listOf(salaryCredit),
            recordedBoundaries = recorded,
            cycleState = state,
            accounts = emptyList(),
            now = millis(2026, 10, 10),
        )

        assertEquals(1, periods.count { it.label == "October, 2026" })
        val october = periods.single { it.label == "October, 2026" }
        assertEquals(salary, october.startMillis)
        assertFalse(october.inferred)
        assertTrue(periods.none { it.startMillis == fallback })
    }

    @Test
    fun `a rejected edit reports so and writes nothing`() = runBlocking {
        val dao = InMemoryDao()
        val repo = dao.repository()
        val food = dao.insertCategoryReturningId(Category(name = "Food"))
        val account = dao.bank("Savings")
        val id = repo.addSpend(account.id, 320_00, "JUICE BAR", null, food, millis(2026, 10, 3))
        repo.applySplit(dao.txnById(id)!!, listOf(SplitPart(food, 200_00), SplitPart(food, 120_00)))
        val part = dao.txns.values.first { it.splitGroupId != null }

        val written = repo.updateTransaction(
            id = part.id,
            accountId = part.accountId,
            direction = Direction.DEBIT,
            amountPaise = part.amountPaise + 50_00,
            occurredAt = part.occurredAt,
            payee = part.payee,
            note = "changed",
            categoryId = food,
        )

        assertFalse(written)
        assertEquals(part, dao.txns.getValue(part.id))
        assertEquals(10_000_00L - 320_00, dao.accountById(account.id)!!.currentBalancePaise)
        assertEquals(TxnSource.MANUAL, part.source)
    }
}
