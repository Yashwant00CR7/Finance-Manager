package com.yk.finance

import com.yk.finance.domain.RecordedAlert
import com.yk.finance.parser.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wording of the shade notification, tested where it is pure.
 *
 * This is the only part of the app most payments will ever show you - the app files
 * them, you never open it, and the notification is the whole account of what happened.
 * Getting a guess presented as a fact, or an empty second line, matters more here than
 * it would on a screen you went looking for.
 */
class RecordedAlertTest {

    private fun say(
        direction: Direction = Direction.DEBIT,
        amountPaise: Long = 359_900,
        categoryName: String? = "Bills",
        accountName: String? = "Salary Account",
        payee: String? = "MYJIO",
        guessed: Boolean = false,
    ) = RecordedAlert.compose(direction, amountPaise, categoryName, accountName, payee, guessed)

    @Test
    fun `money leads and the rest follows in one line`() {
        val said = say()
        assertEquals("Spent ₹3,599.00", said.title)
        assertEquals("Bills · Salary Account · MYJIO", said.body)
    }

    @Test
    fun `a credit is received rather than spent`() {
        val said = say(direction = Direction.CREDIT, amountPaise = 2_616_200, categoryName = "Salary", payee = null)
        assertEquals("Received ₹26,162.00", said.title)
        assertEquals("Salary · Salary Account", said.body)
    }

    @Test
    fun `a guessed category says so`() {
        // Every other surface marks its guesses. A notification that quietly presented
        // one as settled would be the single place the app overstated itself.
        assertTrue(say(guessed = true).body.startsWith("Bills (guess)"))
        assertTrue(!say(guessed = false).body.contains("guess"))
    }

    @Test
    fun `a payee-less Union row still reads as something`() {
        val said = say(payee = null, categoryName = "Food")
        assertEquals("Food · Salary Account", said.body)
    }

    @Test
    fun `nothing known at all still beats an empty line`() {
        val said = say(categoryName = null, accountName = null, payee = null)
        assertEquals("Spent ₹3,599.00", said.title)
        assertEquals("Recorded", said.body)
    }

    @Test
    fun `blank strings count as absent, not as separators`() {
        // A blank payee joined naively gives "Food · Salary Account · ", which looks
        // like a field the app failed to fill in.
        assertEquals("Food · Salary Account", say(categoryName = "Food", payee = "  ").body)
        assertEquals("Salary Account · MYJIO", say(categoryName = "").body)
    }

    @Test
    fun `paise are never dropped`() {
        assertEquals("Spent ₹50.00", say(amountPaise = 5_000).title)
        assertEquals("Spent ₹0.99", say(amountPaise = 99).title)
    }
}
