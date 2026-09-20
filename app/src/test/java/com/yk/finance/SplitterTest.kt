package com.yk.finance

import com.yk.finance.data.Sharing
import com.yk.finance.data.Txn
import com.yk.finance.data.TxnSource
import com.yk.finance.domain.Ledger
import com.yk.finance.domain.SplitPart
import com.yk.finance.domain.Splitter
import com.yk.finance.parser.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic that must never be wrong.
 *
 * Every case here is a way the ledger could quietly stop agreeing with the bank
 * account it describes - and the thing about a split that does not add up is that each
 * part still looks like a perfectly ordinary transaction, so nothing on any screen
 * would ever show the discrepancy.
 */
class SplitterTest {

    private val FOOD = 1L
    private val LATER = 2L
    private val GIVEN = 3L

    private val sharing: (Long?) -> Sharing = { id ->
        when (id) {
            LATER -> Sharing.LENT
            GIVEN -> Sharing.GIVEN
            else -> Sharing.NONE
        }
    }

    private fun bill(
        id: Long = 91,
        amount: Long = 32_000,
        category: Long? = null,
        source: TxnSource = TxnSource.SMS,
    ) = Txn(
        id = id,
        accountId = 7,
        direction = Direction.DEBIT,
        amountPaise = amount,
        occurredAt = 1_757_000_000_000,
        payee = "KA 05 JUICE BAR",
        reference = "REF1",
        categoryId = category,
        countsAsSpending = true,
        source = source,
        rawMessage = "Rs 320.00 debited",
        fingerprint = "fp1",
    )

    // ----- the remainder, which is the whole safety argument -----

    @Test
    fun `a split that does not add up is not balanced`() {
        val parts = listOf(SplitPart(FOOD, 20_000), SplitPart(LATER, 10_000))
        assertEquals(2_000, Splitter.remainder(32_000, parts))
        assertFalse(Splitter.isBalanced(32_000, parts))
    }

    @Test
    fun `a split that adds up exactly is balanced`() {
        val parts = listOf(SplitPart(FOOD, 20_000), SplitPart(LATER, 12_000))
        assertEquals(0, Splitter.remainder(32_000, parts))
        assertTrue(Splitter.isBalanced(32_000, parts))
    }

    @Test
    fun `overshooting is refused just as firmly as undershooting`() {
        val parts = listOf(SplitPart(FOOD, 20_000), SplitPart(LATER, 20_000))
        assertEquals(-8_000, Splitter.remainder(32_000, parts))
        assertFalse(Splitter.isBalanced(32_000, parts))
    }

    @Test
    fun `a zero part is refused even when the total happens to work`() {
        // Sums to 320 but records a line that means nothing.
        val parts = listOf(SplitPart(FOOD, 32_000), SplitPart(LATER, 0))
        assertEquals(0, Splitter.remainder(32_000, parts))
        assertFalse(Splitter.isBalanced(32_000, parts))
    }

    @Test
    fun `no parts at all is not a balanced split of nothing`() {
        assertFalse(Splitter.isBalanced(0, emptyList()))
    }

    // ----- what gets written -----

    @Test
    fun `the original row is reused and keeps the bank's own evidence`() {
        val plan = Splitter.plan(
            listOf(bill()),
            listOf(SplitPart(FOOD, 20_000), SplitPart(LATER, 12_000, "Arun")),
            "sg-1",
            sharing,
        )
        val kept = plan.updates.single()
        assertEquals(91, kept.id)
        assertEquals(20_000, kept.amountPaise)
        assertEquals(FOOD, kept.categoryId)
        // The message the bank actually sent is evidence and survives the split.
        assertEquals("Rs 320.00 debited", kept.rawMessage)
        assertEquals("REF1", kept.reference)
        assertEquals("fp1", kept.fingerprint)
    }

    @Test
    fun `the second part is a new row claiming neither reference nor fingerprint`() {
        val plan = Splitter.plan(
            listOf(bill()),
            listOf(SplitPart(FOOD, 20_000), SplitPart(LATER, 12_000, "Arun")),
            "sg-1",
            sharing,
        )
        val added = plan.inserts.single()
        assertEquals(0, added.id)
        assertEquals(12_000, added.amountPaise)
        // Two rows answering to one identity is how an import silently duplicates.
        assertNull(added.reference)
        assertNull(added.fingerprint)
        assertNull(added.rawMessage)
    }

    @Test
    fun `the parts always sum to what the bank took`() {
        val plan = Splitter.plan(
            listOf(bill()),
            listOf(SplitPart(FOOD, 20_000), SplitPart(LATER, 12_000, "Arun")),
            "sg-1",
            sharing,
        )
        assertEquals(32_000, (plan.updates + plan.inserts).sumOf { it.amountPaise })
    }

    @Test
    fun `a lent part stops counting as spending and a food part does not`() {
        val plan = Splitter.plan(
            listOf(bill()),
            listOf(SplitPart(FOOD, 20_000), SplitPart(LATER, 12_000, "Arun")),
            "sg-1",
            sharing,
        )
        assertTrue(plan.updates.single().countsAsSpending)
        assertFalse(plan.inserts.single().countsAsSpending)
    }

    @Test
    fun `money given away still counts as spending`() {
        val plan = Splitter.plan(
            listOf(bill()),
            listOf(SplitPart(FOOD, 20_000), SplitPart(GIVEN, 12_000)),
            "sg-1",
            sharing,
        )
        assertTrue(plan.inserts.single().countsAsSpending)
    }

    @Test
    fun `the whole bill on one lent part is a legitimate split of one`() {
        val plan = Splitter.plan(
            listOf(bill()),
            listOf(SplitPart(LATER, 32_000, "Arun")),
            "sg-1",
            sharing,
        )
        assertTrue(plan.inserts.isEmpty())
        val row = plan.updates.single()
        assertEquals(32_000, row.amountPaise)
        assertEquals("Arun", row.owedBy)
        assertFalse(row.countsAsSpending)
    }

    @Test
    fun `a name is not kept on a part nobody owes`() {
        val plan = Splitter.plan(
            listOf(bill()),
            listOf(SplitPart(FOOD, 32_000, "Arun")),
            "sg-1",
            sharing,
        )
        assertNull(plan.updates.single().owedBy)
    }

    @Test
    fun `re-splitting into fewer parts deletes the rows it no longer needs`() {
        val group = listOf(
            bill(id = 91, amount = 20_000, category = FOOD).copy(splitGroupId = "sg-1"),
            bill(id = 92, amount = 12_000, category = LATER).copy(splitGroupId = "sg-1"),
        )
        val plan = Splitter.plan(group, listOf(SplitPart(FOOD, 32_000)), "sg-1", sharing)
        assertEquals(listOf(92L), plan.deleteIds)
        assertEquals(32_000, plan.updates.single().amountPaise)
    }

    @Test
    fun `collapsing a split back to one ordinary part drops the group entirely`() {
        // Otherwise the row stays locked out of ordinary amount editing forever, to
        // protect a sum-to-the-bill invariant that has nothing left to protect.
        val group = listOf(
            bill(id = 91, amount = 20_000, category = FOOD).copy(splitGroupId = "sg-1"),
            bill(id = 92, amount = 12_000, category = LATER).copy(splitGroupId = "sg-1"),
        )
        val plan = Splitter.plan(group, listOf(SplitPart(FOOD, 32_000)), "sg-1", sharing)
        assertNull(plan.updates.single().splitGroupId)
    }

    @Test
    fun `a bill entirely lent keeps its group so a repayment has something to join`() {
        val plan = Splitter.plan(
            listOf(bill()),
            listOf(SplitPart(LATER, 32_000, "Arun")),
            "sg-1",
            sharing,
        )
        assertEquals("sg-1", plan.updates.single().splitGroupId)
    }

    @Test
    fun `re-splitting an unchanged group writes nothing`() {
        val group = listOf(
            bill(id = 91, amount = 20_000, category = FOOD)
                .copy(splitGroupId = "sg-1"),
            bill(id = 92, amount = 12_000, category = LATER)
                .copy(splitGroupId = "sg-1", countsAsSpending = false, owedBy = "Arun"),
        )
        val plan = Splitter.plan(
            group,
            listOf(SplitPart(FOOD, 20_000), SplitPart(LATER, 12_000, "Arun")),
            "sg-1",
            sharing,
        )
        assertTrue("an unchanged re-save must not touch the database", plan.isEmpty)
    }

    @Test
    fun `a settlement in the group is never mistaken for part of the bill`() {
        val group = listOf(
            bill(id = 91, amount = 20_000, category = FOOD).copy(splitGroupId = "sg-1"),
            bill(id = 92, amount = 12_000, category = LATER)
                .copy(splitGroupId = "sg-1", countsAsSpending = false, owedBy = "Arun"),
            bill(id = 93, amount = 12_000, source = TxnSource.SETTLEMENT)
                .copy(direction = Direction.CREDIT, splitGroupId = "sg-1"),
        )
        assertEquals(32_000, Splitter.groupTotal(group))
        assertEquals(2, Splitter.partsOf(group).size)
    }

    // ----- un-splitting -----

    @Test
    fun `un-splitting gives the whole amount back to the oldest row`() {
        val group = listOf(
            bill(id = 91, amount = 20_000, category = FOOD).copy(splitGroupId = "sg-1"),
            bill(id = 92, amount = 12_000, category = LATER)
                .copy(splitGroupId = "sg-1", countsAsSpending = false, owedBy = "Arun"),
        )
        val plan = Splitter.unsplit(group)!!
        val restored = plan.updates.single()
        assertEquals(91, restored.id)
        assertEquals(32_000, restored.amountPaise)
        assertNull(restored.splitGroupId)
        assertNull(restored.owedBy)
        assertTrue(restored.countsAsSpending)
        assertEquals(listOf(92L), plan.deleteIds)
    }

    @Test
    fun `un-splitting is refused while a repayment is still attached`() {
        val group = listOf(
            bill(id = 91, amount = 20_000, category = FOOD).copy(splitGroupId = "sg-1"),
            bill(id = 92, amount = 12_000, category = LATER)
                .copy(splitGroupId = "sg-1", countsAsSpending = false, owedBy = "Arun"),
            bill(id = 93, amount = 12_000, source = TxnSource.SETTLEMENT)
                .copy(direction = Direction.CREDIT, splitGroupId = "sg-1"),
        )
        assertNull(Splitter.unsplit(group))
    }

    // ----- what a split teaches -----

    @Test
    fun `a split teaches your own largest share`() {
        val parts = listOf(SplitPart(FOOD, 20_000), SplitPart(LATER, 12_000, "Arun"))
        assertEquals(FOOD, Splitter.learnTarget(parts, sharing))
    }

    @Test
    fun `a lent part never teaches, even when it is the larger one`() {
        // Who you ate with is not a fact about the shop. Without this, one split lunch
        // files every later visit there as a debt to nobody.
        val parts = listOf(SplitPart(FOOD, 10_000), SplitPart(LATER, 22_000, "Arun"))
        assertEquals(FOOD, Splitter.learnTarget(parts, sharing))
    }

    @Test
    fun `a bill entirely somebody else's teaches nothing at all`() {
        assertNull(Splitter.learnTarget(listOf(SplitPart(LATER, 32_000, "Arun")), sharing))
    }

    // ----- the importer must still see the bill the way the bank does -----

    @Test
    fun `a split bill collapses back to its full value for matching`() {
        val ledger = listOf(
            bill(id = 91, amount = 20_000, category = FOOD).copy(splitGroupId = "sg-1"),
            bill(id = 92, amount = 12_000, category = LATER)
                .copy(splitGroupId = "sg-1", countsAsSpending = false, owedBy = "Arun"),
        )
        val collapsed = Splitter.collapseGroups(ledger)
        assertEquals(1, collapsed.size)
        assertEquals(32_000, collapsed.single().amountPaise)
        assertEquals(91, collapsed.single().id)
    }

    @Test
    fun `a repayment stays individually matchable`() {
        // It is a real credit the bank really did message about, so the file will have
        // a row for it and something has to be there to match.
        val ledger = listOf(
            bill(id = 91, amount = 20_000, category = FOOD).copy(splitGroupId = "sg-1"),
            bill(id = 92, amount = 12_000, category = LATER)
                .copy(splitGroupId = "sg-1", countsAsSpending = false),
            bill(id = 93, amount = 12_000, source = TxnSource.SETTLEMENT)
                .copy(direction = Direction.CREDIT, splitGroupId = "sg-1"),
        )
        val collapsed = Splitter.collapseGroups(ledger)
        assertEquals(2, collapsed.size)
        assertTrue(collapsed.any { it.id == 93L && it.amountPaise == 12_000L })
    }

    @Test
    fun `a ledger with no splits is returned untouched`() {
        val ledger = listOf(bill(id = 91), bill(id = 92))
        assertTrue("a ledger with no splits must not be rebuilt", ledger === Splitter.collapseGroups(ledger))
    }

    // ----- the figures the split is for -----

    @Test
    fun `splitting removes the friend's share from your expense total`() {
        val whole = listOf(bill(amount = 32_000, category = FOOD))
        assertEquals(32_000, Ledger.totals(whole).expensePaise)

        val split = listOf(
            bill(id = 91, amount = 20_000, category = FOOD).copy(splitGroupId = "sg-1"),
            bill(id = 92, amount = 12_000, category = LATER)
                .copy(splitGroupId = "sg-1", countsAsSpending = false, owedBy = "Arun"),
        )
        assertEquals(20_000, Ledger.totals(split).expensePaise)
    }

    @Test
    fun `a repayment is not income`() {
        // Ledger already refuses to inflate income with money never earned. This is the
        // same rule: your own 120 coming back is not 120 you earned.
        val repayment = bill(id = 93, amount = 12_000, source = TxnSource.SETTLEMENT)
            .copy(direction = Direction.CREDIT, splitGroupId = "sg-1", countsAsSpending = false)
        assertFalse(Ledger.isIncome(repayment))
        assertEquals(0, Ledger.totals(listOf(repayment)).incomePaise)
    }

    // ----- who owes what -----

    private fun lent(id: Long, amount: Long, who: String, group: String) =
        bill(id = id, amount = amount, category = LATER)
            .copy(splitGroupId = group, countsAsSpending = false, owedBy = who)

    @Test
    fun `debts are grouped by person, largest first`() {
        val ledger = listOf(
            bill(id = 1, amount = 20_000, category = FOOD).copy(splitGroupId = "g1"),
            lent(2, 12_000, "Arun", "g1"),
            lent(3, 45_000, "Vignesh", "g2"),
        )
        val owed = Splitter.owed(ledger, sharing)
        assertEquals(listOf("Vignesh", "Arun"), owed.map { it.person })
        assertEquals(45_000, owed.first().outstandingPaise)
    }

    @Test
    fun `a partial repayment leaves the rest outstanding`() {
        val ledger = listOf(
            lent(2, 12_000, "Arun", "g1"),
            bill(id = 5, amount = 6_000, source = TxnSource.SETTLEMENT)
                .copy(direction = Direction.CREDIT, splitGroupId = "g1", countsAsSpending = false),
        )
        val arun = Splitter.owed(ledger, sharing).single()
        assertEquals(12_000, arun.lentPaise)
        assertEquals(6_000, arun.repaidPaise)
        assertEquals(6_000, arun.outstandingPaise)
    }

    @Test
    fun `a fully repaid debt disappears from the list`() {
        val ledger = listOf(
            lent(2, 12_000, "Arun", "g1"),
            bill(id = 5, amount = 12_000, source = TxnSource.SETTLEMENT)
                .copy(direction = Direction.CREDIT, splitGroupId = "g1", countsAsSpending = false),
        )
        assertTrue(Splitter.owed(ledger, sharing).isEmpty())
    }

    @Test
    fun `one person's repayment never clears another person's debt`() {
        val ledger = listOf(
            lent(2, 12_000, "Arun", "g1"),
            lent(3, 12_000, "Vignesh", "g2"),
            bill(id = 5, amount = 12_000, source = TxnSource.SETTLEMENT)
                .copy(direction = Direction.CREDIT, splitGroupId = "g1", countsAsSpending = false),
        )
        val owed = Splitter.owed(ledger, sharing)
        assertEquals(listOf("Vignesh"), owed.map { it.person })
    }

    @Test
    fun `rows filed under the lent category before splitting existed are left alone`() {
        // They were counted as spending in months you have already read and closed.
        // Retro-converting them would move those totals to assert a debt the app was
        // never tracking.
        val legacy = bill(id = 4, amount = 30_000, category = LATER) // no group, still spending
        assertTrue(Splitter.owed(listOf(legacy), sharing).isEmpty())
        assertEquals(30_000, Ledger.totals(listOf(legacy)).expensePaise)
    }

    @Test
    fun `an unnamed debt is still reported rather than dropped`() {
        val ledger = listOf(
            bill(id = 2, amount = 12_000, category = LATER)
                .copy(splitGroupId = "g1", countsAsSpending = false, owedBy = null),
        )
        assertEquals("Someone", Splitter.owed(ledger, sharing).single().person)
    }
}
