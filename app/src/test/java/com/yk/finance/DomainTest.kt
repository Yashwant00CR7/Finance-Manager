package com.yk.finance

import com.yk.finance.data.CycleState
import com.yk.finance.data.Txn
import com.yk.finance.data.TxnSource
import com.yk.finance.domain.CycleCalculator
import com.yk.finance.domain.Relation
import com.yk.finance.domain.TransferResolver
import com.yk.finance.parser.Channel
import com.yk.finance.parser.Direction
import com.yk.finance.parser.ParseResult
import com.yk.finance.parser.ParsedSms
import com.yk.finance.parser.RuleBasedParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CycleCalculatorTest {

    @Test
    fun `last working day skips weekends`() {
        // Verified against the calendar the user was shown during planning.
        assertEquals(LocalDate.of(2026, 9, 30), CycleCalculator.lastWorkingDayOf(2026, 9))  // Wed
        assertEquals(LocalDate.of(2026, 10, 30), CycleCalculator.lastWorkingDayOf(2026, 10)) // 31st is Sat
        assertEquals(LocalDate.of(2026, 11, 30), CycleCalculator.lastWorkingDayOf(2026, 11)) // Mon
        assertEquals(LocalDate.of(2026, 12, 31), CycleCalculator.lastWorkingDayOf(2026, 12)) // Thu
        assertEquals(LocalDate.of(2027, 1, 29), CycleCalculator.lastWorkingDayOf(2027, 1))   // 31st is Sun
        assertEquals(LocalDate.of(2027, 2, 26), CycleCalculator.lastWorkingDayOf(2027, 2))   // 28th is Sun
    }

    private fun credit(amountPaise: Long, payee: String, bank: String = "ICICI") = ParsedSms(
        bank = bank,
        accountToken = "742",
        direction = Direction.CREDIT,
        amountPaise = amountPaise,
        occurredAt = CycleCalculator.startOfDayMillis(LocalDate.of(2026, 10, 30)),
        payee = payee,
        reference = "999888777666",
        availableBalancePaise = null,
        channel = Channel.UPI,
        raw = "",
    )

    private fun stateLastRolledOn(date: LocalDate, salaryPaise: Long?, payee: String?) = CycleState(
        cycleStartMillis = CycleCalculator.startOfDayMillis(date),
        salaryBank = "ICICI",
        salaryPayee = payee,
        salaryAmountPaise = salaryPaise,
        lastRollMillis = CycleCalculator.startOfDayMillis(date),
    )

    private val now = CycleCalculator.startOfDayMillis(LocalDate.of(2026, 10, 30))

    @Test
    fun `salary credit rolls the cycle`() {
        val state = stateLastRolledOn(LocalDate.of(2026, 9, 30), 5_000_000L, "ACME PAYROLL")
        assertTrue(CycleCalculator.isSalaryCredit(credit(5_000_000L, "ACME PAYROLL"), state, now))
    }

    @Test
    fun `salary within tolerance still rolls`() {
        val state = stateLastRolledOn(LocalDate.of(2026, 9, 30), 5_000_000L, "ACME PAYROLL")
        // 10% less - deductions, still payday.
        assertTrue(CycleCalculator.isSalaryCredit(credit(4_500_000L, "ACME PAYROLL"), state, now))
    }

    @Test
    fun `large unrelated credit does not roll the cycle`() {
        val state = stateLastRolledOn(LocalDate.of(2026, 9, 30), 5_000_000L, "ACME PAYROLL")
        // A real case from the samples: 3,000 from R SRINIVASAN. Wrong payee, wrong size.
        assertFalse(CycleCalculator.isSalaryCredit(credit(300_000L, "R SRINIVASAN"), state, now))
    }

    @Test
    fun `a second salary-sized credit cannot double-roll within the minimum gap`() {
        val state = stateLastRolledOn(LocalDate.of(2026, 10, 29), 5_000_000L, "ACME PAYROLL")
        assertFalse(
            "only 1 day since last roll",
            CycleCalculator.isSalaryCredit(credit(5_000_000L, "ACME PAYROLL"), state, now),
        )
    }

    @Test
    fun `fallback rolls when the computed boundary passes with no salary seen`() {
        val state = stateLastRolledOn(LocalDate.of(2026, 9, 30), null, null)
        assertTrue(CycleCalculator.shouldRollByDate(state, now))
    }

    @Test
    fun `fallback does not roll mid-cycle`() {
        val state = stateLastRolledOn(LocalDate.of(2026, 9, 30), null, null)
        val midCycle = CycleCalculator.startOfDayMillis(LocalDate.of(2026, 10, 15))
        assertFalse(CycleCalculator.shouldRollByDate(state, midCycle))
    }
}

class TransferResolverTest {

    private val parser = RuleBasedParser()
    private val now = 1_790_000_000_000L

    private fun parse(sender: String, body: String) =
        (parser.parse(sender, body, now) as ParseResult.Parsed).sms

    /**
     * The real pair from the user's phone. Both carry reference 634455667788.
     * A naive same-reference merge would delete one leg.
     */
    private val ICICI_LEG =
        "ICICI Bank Acct XX742 debited for Rs 3000.00 on 16-Sep-26; A K SHARMA credited. UPI:634455667788."
    private val UNION_LEG =
        "A/c *8317 Credited for Rs:3000.00 on 16-09-2026 11:23:34 by Mob Bk ref no 634455667788 " +
            "Avl Bal Rs:2841.37.Never Share OTP/PIN/CVV-Union Bank of India"

    private fun existing(accountId: Long, direction: Direction, amountPaise: Long, ref: String) = Txn(
        id = 1,
        accountId = accountId,
        direction = direction,
        amountPaise = amountPaise,
        occurredAt = now,
        payee = null,
        reference = ref,
        countsAsSpending = direction == Direction.DEBIT,
        source = TxnSource.SMS,
    )

    @Test
    fun `same reference across two accounts in opposite directions is a transfer`() {
        val incoming = parse("AD-UNIONB", UNION_LEG)
        val alreadyHave = existing(
            accountId = 1L, direction = Direction.DEBIT,
            amountPaise = 300000L, ref = "634455667788",
        )
        val relation = TransferResolver.classify(incoming, accountId = 2L, candidates = listOf(alreadyHave))
        assertTrue(
            "the real 3,000 ICICI->Union move must be a transfer, not a duplicate",
            relation is Relation.TransferLeg,
        )
    }

    @Test
    fun `same reference same account same direction is a duplicate`() {
        val incoming = parse("VM-ICICIB", ICICI_LEG)
        val alreadyHave = existing(
            accountId = 1L, direction = Direction.DEBIT,
            amountPaise = 300000L, ref = "634455667788",
        )
        val relation = TransferResolver.classify(incoming, accountId = 1L, candidates = listOf(alreadyHave))
        assertTrue(relation is Relation.Duplicate)
    }

    @Test
    fun `same reference but a different amount is left independent`() {
        // Guards against collapsing two distinct movements that happen to share a ref.
        val incoming = parse("VM-ICICIB", ICICI_LEG)
        val differentAmount = existing(
            accountId = 1L, direction = Direction.DEBIT,
            amountPaise = 999_00L, ref = "634455667788",
        )
        val relation = TransferResolver.classify(incoming, accountId = 1L, candidates = listOf(differentAmount))
        assertTrue(relation is Relation.Independent)
    }

    @Test
    fun `two genuine payments with no shared reference are never compared`() {
        // The DAO pre-filters candidates by reference, so an unrelated payment simply
        // never reaches classify(). With no candidates, it stands on its own.
        val incoming = parse("VM-ICICIB", ICICI_LEG)
        val relation = TransferResolver.classify(incoming, accountId = 1L, candidates = emptyList())
        assertTrue(relation is Relation.Independent)
    }

    @Test
    fun `ATM withdrawal is classified as a cash movement`() {
        val sms = parse(
            "VM-ICICIB",
            // A real ICICI ATM withdrawal. This used to be a guessed format; the guess was
            // wrong in two ways at once - ICICI writes "Acc", not "Acct", and puts no "is"
            // before "debited". See docs/bank-sms-formats.md.
            "ICICI Bank Acc XX921 debited Rs. 10,000.00 on 20-Jan-26 NFSCASH WDL. " +
                "Avb Bal Rs. 3,943.84. To dispute Call 18002662 or SMS BLOCK 921 to 9215676766 .",
        )
        assertTrue(TransferResolver.isCashWithdrawal(sms))
    }

    @Test
    fun `an ordinary UPI debit is not a cash movement`() {
        assertFalse(TransferResolver.isCashWithdrawal(parse("VM-ICICIB", ICICI_LEG)))
    }
}
