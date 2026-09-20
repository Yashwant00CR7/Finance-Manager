package com.yk.finance

import com.yk.finance.domain.Calculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keypad's arithmetic, checked in paise.
 *
 * Every assertion here is about exactness. A calculator that is a paisa out is not a
 * rounding detail - it is the difference between a ledger that reconciles with the bank
 * and one that does not, which is the whole reason Double is banned from this path.
 */
class CalculatorTest {

    private fun paise(expression: String): Long? = Calculator.evaluate(expression)?.paise

    @Test
    fun `plain amounts convert exactly`() {
        assertEquals(32000L, paise("320"))
        assertEquals(32050L, paise("320.50"))
        assertEquals(32030L, paise("320.3"))
        assertEquals(5L, paise("0.05"))
    }

    @Test
    fun `the classic float failure does not happen here`() {
        // 0.1 + 0.2 is 0.30000000000000004 in Double. In paise it is 30, exactly.
        assertEquals(30L, paise("0.1+0.2"))
    }

    @Test
    fun `multiplication and division take precedence over addition`() {
        assertEquals(70000L, paise("100+200*3"))
        assertEquals(15000L, paise("100+100/2"))
    }

    @Test
    fun `a split bill rounds to the nearest paisa and says so`() {
        val result = Calculator.evaluate("700/3")
        assertEquals(23333L, result?.paise)
        assertTrue("dividing 700 by 3 cannot land on a paisa", result!!.rounded)
    }

    @Test
    fun `a division that lands exactly is not reported as rounded`() {
        val result = Calculator.evaluate("700/4")
        assertEquals(17500L, result?.paise)
        assertFalse(result!!.rounded)
    }

    @Test
    fun `a sum that lands exactly is not reported as rounded`() {
        assertFalse(Calculator.evaluate("12.50+7.50")!!.rounded)
    }

    @Test
    fun `malformed input is refused rather than guessed`() {
        assertNull(paise("12..5"))
        assertNull(paise("12+"))
        assertNull(paise("+"))
        assertNull(paise("12++5"))
        assertNull(paise("abc"))
        assertNull(paise(""))
    }

    @Test
    fun `division by zero is refused`() {
        assertNull(paise("100/0"))
    }

    @Test
    fun `a leading minus is a sign, not a dangling operator`() {
        assertEquals(-5000L, paise("-50"))
    }

    @Test
    fun `currency formatting and separators are tolerated`() {
        assertEquals(123450L, paise("₹1,234.50"))
    }

    @Test
    fun `a third decimal is not silently truncated`() {
        // 12.345 is half a paisa. HALF_UP is deliberate and stated, not accidental.
        val result = Calculator.evaluate("12.345")
        assertEquals(1235L, result?.paise)
        assertTrue(result!!.rounded)
    }
}
