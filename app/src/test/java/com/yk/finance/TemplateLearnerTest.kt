package com.yk.finance

import com.yk.finance.data.Account
import com.yk.finance.data.AccountKind
import com.yk.finance.domain.TemplateLearner
import com.yk.finance.domain.toPatternSpec
import com.yk.finance.parser.Direction
import com.yk.finance.parser.Tier
import com.yk.finance.parser.applySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateLearnerTest {

    private val sbiAccount = Account(
        id = 1,
        displayName = "SBI ..0208",
        bank = "SBI",
        accountToken = "0208",
        kind = AccountKind.BANK,
    )

    private val hdfcCard = Account(
        id = 2,
        displayName = "HDFC Card ..4321",
        bank = "HDFC",
        accountToken = "4321",
        kind = AccountKind.CREDIT_CARD,
    )

    @Test
    fun `learns a debit template and matches a future similar message with different values`() {
        val originalMessage = "Dear UPI user A/C X0208 debited by 500.0 on 26Sep26 tran id 426912345678 to Swiggy. If not you, call 1800111109 -SBI"
        val learned = TemplateLearner.learn(
            rawMessage = originalMessage,
            amountPaise = 50000L,
            direction = Direction.DEBIT,
            account = sbiAccount,
            payee = "Swiggy",
        )

        assertNotNull("Template should be learned successfully", learned)
        assertEquals("SBI", learned!!.bank)
        assertEquals(Direction.DEBIT, learned.direction)

        val spec = learned.toPatternSpec()
        assertEquals(Tier.VERIFIED, spec.tier)

        // 1. Verify it parses the original message correctly
        val parsedOrig = applySpec(spec, originalMessage, 1727400000000L, bank = "SBI")
        assertNotNull("Original message should parse", parsedOrig)
        assertEquals(50000L, parsedOrig!!.amountPaise)
        assertEquals("0208", parsedOrig.accountToken)
        assertEquals(Direction.DEBIT, parsedOrig.direction)
        assertEquals("Swiggy", parsedOrig.payee)

        // 2. Verify a future similar message parses with different amount, date, ref, and payee
        val futureMessage = "Dear UPI user A/C X0208 debited by 1,250.75 on 28Sep26 tran id 998877665544 to Zomato. If not you, call 1800111109 -SBI"
        val parsedFuture = applySpec(spec, futureMessage, 1727500000000L, bank = "SBI")
        assertNotNull("Future similar message should parse automatically", parsedFuture)
        assertEquals(125075L, parsedFuture!!.amountPaise)
        assertEquals("0208", parsedFuture.accountToken)
        assertEquals(Direction.DEBIT, parsedFuture.direction)
        assertEquals("Zomato", parsedFuture.payee)
        assertEquals("998877665544", parsedFuture.reference)
    }

    @Test
    fun `learns a credit card spend template`() {
        val cardAlert = "Alert: You have made a spend of INR 1,299.00 on your HDFC Bank Credit Card ending 4321 at AMAZON on 26-SEP-26."
        val learned = TemplateLearner.learn(
            rawMessage = cardAlert,
            amountPaise = 129900L,
            direction = Direction.DEBIT,
            account = hdfcCard,
            payee = "AMAZON",
        )

        assertNotNull(learned)
        assertTrue(learned!!.isCard)

        val spec = learned.toPatternSpec()
        val futureCardAlert = "Alert: You have made a spend of INR 450.00 on your HDFC Bank Credit Card ending 4321 at FLIPKART on 29-SEP-26."
        val parsed = applySpec(spec, futureCardAlert, 1727600000000L, bank = "HDFC")

        assertNotNull(parsed)
        assertEquals(45000L, parsed!!.amountPaise)
        assertEquals("4321", parsed.accountToken)
        assertEquals("FLIPKART", parsed.payee)
        assertTrue(parsed.isCard)
    }
}
