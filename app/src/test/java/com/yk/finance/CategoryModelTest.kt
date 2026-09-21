package com.yk.finance

import com.yk.finance.data.Txn
import com.yk.finance.domain.CategoryModel
import com.yk.finance.domain.Features
import com.yk.finance.parser.Channel
import com.yk.finance.parser.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate, which is the part that decides whether the app writes to your ledger.
 *
 * Every test here is really the same question asked four ways: can the model be made
 * to file something it has no business filing. Being wrong in a ranking costs a
 * scroll; being wrong in the ledger moves a total.
 */
class CategoryModelTest {

    private val food = 1L
    private val transport = 2L
    private val salary = 3L

    private fun txn(
        payee: String?,
        rupees: Long = 150,
        at: Long = AT_LUNCH,
        accountId: Long = 1,
        direction: Direction = Direction.DEBIT,
        categoryId: Long? = null,
        inferred: Boolean = false,
    ) = Txn(
        accountId = accountId,
        direction = direction,
        amountPaise = rupees * 100,
        occurredAt = at,
        payee = payee,
        reference = null,
        channel = Channel.UPI,
        categoryId = categoryId,
        countsAsSpending = direction == Direction.DEBIT,
        categoryWasInferred = inferred,
    )

    /** Enough history that the floors are satisfied and the gate is the only thing left. */
    private fun trained(): CategoryModel {
        val model = CategoryModel()
        model.rebuildFrom(
            buildList {
                repeat(30) { add(txn("KA 05 JUICE BAR", categoryId = food)) }
                repeat(30) { add(txn("UBER TRIP", rupees = 320, at = AT_MORNING, categoryId = transport)) }
            },
        )
        return model
    }

    @Test
    fun `a fresh install files nothing`() {
        val model = CategoryModel()
        model.rebuildFrom(listOf(txn("KA 05 JUICE BAR", categoryId = food)))

        val prediction = model.predict(txn("KA 05 JUICE BAR"))
        assertNotNull("it should still have an opinion", prediction)
        assertFalse("but never a confident one", prediction!!.confident)
    }

    @Test
    fun `it will not file a category it has barely seen`() {
        val model = CategoryModel()
        model.rebuildFrom(
            buildList {
                repeat(60) { add(txn("KA 05 JUICE BAR", categoryId = food)) }
                // Two examples clears the total floor on the back of Food's 60, and is
                // exactly the case MIN_CATEGORY_EXAMPLES exists to refuse.
                repeat(2) { add(txn("SOME NEW SHOP", rupees = 900, categoryId = transport)) }
            },
        )
        val prediction = model.predict(txn("SOME NEW SHOP", rupees = 900))
        assertEquals(transport, prediction!!.categoryId)
        assertFalse(prediction.confident)
    }

    @Test
    fun `a familiar payee is filed`() {
        val prediction = trained().predict(txn("KA 05 JUICE BAR"))
        assertEquals(food, prediction!!.categoryId)
        assertTrue(prediction.confident)
    }

    @Test
    fun `the toggle stops it filing without stopping it ranking`() {
        val model = trained()
        model.autoFileEnabled = false

        val prediction = model.predict(txn("KA 05 JUICE BAR"))
        assertEquals("still ranks", food, prediction!!.categoryId)
        assertFalse("but files nothing", prediction.confident)
    }

    @Test
    fun `income categories are never offered for money going out`() {
        val model = CategoryModel()
        model.rebuildFrom(
            buildList {
                repeat(40) {
                    add(txn(null, rupees = 50_000, direction = Direction.CREDIT, categoryId = salary))
                }
                repeat(40) { add(txn("KA 05 JUICE BAR", categoryId = food)) }
            },
        )
        val allowed = setOf(food, transport)
        val ranked = model.rank(Features.of(txn(null, rupees = 50_000)), allowed)

        assertTrue("salary must not appear at all", ranked.none { it.categoryId == salary })
    }

    @Test
    fun `a single candidate is ranked but never filed`() {
        // With one category there is no runner-up, so there is no way to tell a real
        // winner from the only thing on the list.
        val model = CategoryModel()
        model.rebuildFrom(List(60) { txn("KA 05 JUICE BAR", categoryId = food) })

        val prediction = model.predict(txn("KA 05 JUICE BAR"))
        assertEquals(food, prediction!!.categoryId)
        assertEquals(0.0, prediction.margin, 1e-9)
        assertFalse(prediction.confident)
    }

    @Test
    fun `it never trains on its own guesses`() {
        val model = CategoryModel()
        model.rebuildFrom(
            buildList {
                repeat(20) { add(txn("KA 05 JUICE BAR", categoryId = food)) }
                repeat(40) { add(txn("GUESSED SHOP", categoryId = transport, inferred = true)) }
            },
        )
        assertEquals("only the 20 real ones", 20, model.totalExamples)
    }

    @Test
    fun `a row with no category teaches nothing`() {
        val model = CategoryModel()
        model.rebuildFrom(List(40) { txn("KA 05 JUICE BAR", categoryId = null) })
        assertEquals(0, model.totalExamples)
    }

    @Test
    fun `one correction moves the next guess`() {
        val model = trained()
        // A payee it has never seen, at an amount and a time this ledger only ever
        // spends on food, already leans Food. That is the context features working,
        // and it is the whole reason a payee-less Union row has anything to go on.
        val row = txn("BRAND NEW PLACE")
        assertEquals(food, model.predict(row)!!.categoryId)

        // Filing it the other way moves it, because the payee is now evidence of its
        // own and it points somewhere else.
        repeat(6) { model.learn(row, transport) }
        assertEquals(transport, model.predict(row)!!.categoryId)
    }

    @Test
    fun `context alone decides nothing when the contexts overlap`() {
        // trained() is unrealistically tidy - food and transport never share an amount
        // band or an hour there, so context separates them perfectly. A real ledger is
        // not like that, and this is the case that matters: an unknown payee, at a
        // time of day you spend on several things, must not be filed.
        //
        // This is also the case that caught a real bug. Scored by feature counts
        // rather than presence, the category whose payees had shorter names won here
        // on nothing but the size of its denominator.
        val model = CategoryModel()
        model.rebuildFrom(
            buildList {
                repeat(30) { add(txn("KA 05 JUICE BAR", categoryId = food)) }
                repeat(30) { add(txn("SOME SHOP", categoryId = transport)) }
            },
        )
        val prediction = model.predict(txn("PAYEE NEVER SEEN BEFORE"))
        assertFalse(
            "identical context and no payee evidence must not clear the gate",
            prediction!!.confident,
        )
        assertEquals("and the two must be all but tied", 0.0, prediction.margin, 0.5)
    }

    @Test
    fun `undo puts the model back`() {
        val model = trained()
        val row = txn("BRAND NEW PLACE")
        val before = model.totalExamples

        model.learn(row, food)
        model.unlearn(row, food)

        assertEquals(before, model.totalExamples)
        assertEquals(food, model.predict(txn("KA 05 JUICE BAR"))!!.categoryId)
    }

    @Test
    fun `changing your mind swaps the example rather than adding a second`() {
        // The model is a projection over the ledger, and the ledger holds one category
        // per row. Learning the new one without retiring the old would leave the model
        // asserting both - and believing a row is Food and Transport at once is how a
        // correction makes the next guess worse instead of better.
        val model = trained()
        val row = txn("SOME PLACE")
        val before = model.totalExamples

        model.learn(row, food)
        model.unlearn(row, food)
        model.learn(row, transport)

        assertEquals(before + 1, model.totalExamples)
        repeat(8) { model.learn(row, transport) }
        assertEquals(transport, model.predict(row)!!.categoryId)
    }

    @Test
    fun `an empty model has no opinion at all`() {
        assertNull(CategoryModel().predict(txn("KA 05 JUICE BAR")))
    }

    private companion object {
        /** 2026-09-15 13:20 IST, a Tuesday. */
        const val AT_LUNCH = 1_789_458_600_000L

        /** 2026-09-15 08:20 IST. */
        const val AT_MORNING = 1_789_440_600_000L
    }
}
