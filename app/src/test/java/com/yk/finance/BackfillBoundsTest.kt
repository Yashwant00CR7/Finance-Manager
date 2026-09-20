package com.yk.finance

import com.yk.finance.data.Txn
import com.yk.finance.domain.Categorizer
import com.yk.finance.parser.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug that started all of this.
 *
 * Learning a payee rule used to run `UPDATE transactions SET categoryId = ? WHERE
 * payee = ?` with no other condition - no date bound, no "only if unset". Correcting
 * one lunch reached back through the entire ledger and rewrote every earlier payment to
 * that shop, including ones deliberately filed under something else. A month you had
 * already read, and a budget you had already blown or already survived, moved because
 * of something you tapped weeks later, with nothing on screen to say so.
 */
class BackfillBoundsTest {

    private val FOOD = 1L
    private val OTHERS = 2L

    private fun row(id: Long, payee: String?, category: Long? = null) = Txn(
        id = id,
        accountId = 7,
        direction = Direction.DEBIT,
        amountPaise = 20_000,
        occurredAt = 1_757_000_000_000,
        payee = payee,
        reference = null,
        categoryId = category,
        countsAsSpending = true,
    )

    private val key = Categorizer.payeeKey("KA 05 JUICE BAR")!!

    @Test
    fun `a row you already filed by hand is never overwritten`() {
        val august = row(1, "KA 05 JUICE BAR", category = OTHERS)
        val september = row(2, "KA 05 JUICE BAR")
        val targets = Categorizer.backfillTargets(listOf(august, september), key, exceptId = 9)
        assertEquals(
            "the August decision must survive September's correction",
            listOf(2L),
            targets,
        )
    }

    @Test
    fun `rows that were never filed are still cleaned up in one tap`() {
        // The back-fill is worth having: this is the twelve identical truncated ICICI
        // rows that would otherwise be labelled by hand, one at a time.
        val rows = (1L..12L).map { row(it, "KA 05 JUICE BAR") }
        assertEquals(12, Categorizer.backfillTargets(rows, key, exceptId = 99).size)
    }

    @Test
    fun `the row you tapped is left out of its own back-fill`() {
        val rows = listOf(row(1, "KA 05 JUICE BAR"), row(2, "KA 05 JUICE BAR"))
        assertEquals(listOf(2L), Categorizer.backfillTargets(rows, key, exceptId = 1))
    }

    @Test
    fun `another payee is never touched`() {
        val rows = listOf(row(1, "SWIGGY"), row(2, "KA 05 JUICE BAR"))
        assertEquals(listOf(2L), Categorizer.backfillTargets(rows, key, exceptId = 9))
    }

    @Test
    fun `matching is on the normalised key, not the raw bank text`() {
        // The rule was stored uppercased with whitespace collapsed while the back-fill
        // compared the raw string, so the two disagreed about which rows belonged to a
        // payee - the rule would fire on a row its own back-fill had skipped.
        val rows = listOf(
            row(1, "ka 05   juice bar"),
            row(2, "  KA 05 JUICE BAR  "),
            row(3, "KA 05 JUICE BAR"),
        )
        assertEquals(listOf(1L, 2L, 3L), Categorizer.backfillTargets(rows, key, exceptId = 9))
    }

    @Test
    fun `a row with no payee at all is never swept up`() {
        // Union sends no payee. Every one of its debits would otherwise match every
        // rule, which would file a quarter of all spending under whatever you tapped
        // last.
        val rows = listOf(row(1, null), row(2, ""), row(3, "KA 05 JUICE BAR"))
        assertEquals(listOf(3L), Categorizer.backfillTargets(rows, key, exceptId = 9))
    }

    @Test
    fun `undo has exactly the ids it needs to put things back`() {
        val rows = listOf(
            row(1, "KA 05 JUICE BAR"),
            row(2, "KA 05 JUICE BAR", category = OTHERS),
            row(3, "KA 05 JUICE BAR"),
        )
        val targets = Categorizer.backfillTargets(rows, key, exceptId = 9)
        // Undo clears these back to no category. Anything it did not file must not be
        // in the list, or undoing would erase a decision the rule never made.
        assertTrue(rows.filter { it.id in targets }.all { it.categoryId == null })
        assertEquals(setOf(1L, 3L), targets.toSet())
    }

    @Test
    fun `filing the whole ledger under one category is no longer possible`() {
        val everything = listOf(
            row(1, "SWIGGY", category = FOOD),
            row(2, "AMAZON", category = FOOD),
            row(3, "KA 05 JUICE BAR", category = OTHERS),
        )
        assertTrue(Categorizer.backfillTargets(everything, key, exceptId = 9).isEmpty())
    }
}
