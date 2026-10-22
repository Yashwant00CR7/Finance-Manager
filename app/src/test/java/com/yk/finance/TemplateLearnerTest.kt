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

    private val unionAccount = Account(
        id = 3,
        displayName = "Union Bank ..2209",
        bank = "UNION",
        accountToken = "2209",
        kind = AccountKind.BANK,
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

    @Test
    fun `learns from Union Bank message and parses future alert with different time, amount, and date`() {
        val userMsg = "Union Bank of India A/c *2209 Debited Rs:1755.00 on 22-10-2026 10:40:05 by Mob Bk ref no 629576250805, " +
            "Fvg: AONE TRA Avl Bal Rs:5328.67. Not you?Call 18002333/SMS BLOCK 2209 to 8879365472"

        val learned = TemplateLearner.learn(
            rawMessage = userMsg,
            amountPaise = 175500L,
            direction = Direction.DEBIT,
            account = unionAccount,
            payee = "AONE TRA",
        )

        assertNotNull("Template should be learned from user input", learned)
        val spec = learned!!.toPatternSpec()

        // Test with different date, time, ref, amount, and payee
        val futureAlert = "Union Bank of India A/c *2209 Debited Rs:340.50 on 23-10-2026 18:22:45 by Mob Bk ref no 998811223344, " +
            "Fvg: COFFEE CAFE Avl Bal Rs:4988.17. Not you?Call 18002333/SMS BLOCK 2209 to 8879365472"

        val parsed = applySpec(spec, futureAlert, 1729680000000L, bank = "UNION")
        assertNotNull("Future alert with different time and amount must parse", parsed)
        assertEquals(34050L, parsed!!.amountPaise)
        assertEquals("2209", parsed.accountToken)
        assertEquals("COFFEE CAFE", parsed.payee)
        assertEquals(Direction.DEBIT, parsed.direction)
        assertEquals("998811223344", parsed.reference)
        assertEquals(498817L, parsed.availableBalancePaise)
    }

    @Test
    fun `learned template with direction slot handles both money out and money in`() {
        val debitMsg = "Union Bank of India A/c *2209 Debited Rs:1755.00 on 22-10-2026 10:40:05 by Mob Bk ref no 629576250805, " +
            "Fvg: AONE TRA Avl Bal Rs:5328.67. Not you?Call 18002333"

        val learned = TemplateLearner.learn(
            rawMessage = debitMsg,
            amountPaise = 175500L,
            direction = Direction.DEBIT,
            account = unionAccount,
            payee = "AONE TRA",
        )
        assertNotNull(learned)
        val spec = learned!!.toPatternSpec()

        // Future CREDIT (Money in) alert using the same format
        val creditAlert = "Union Bank of India A/c *2209 Credited Rs:10000.00 on 24-10-2026 09:12:00 by Mob Bk ref no 112233445566, " +
            "Fvg: SALARY CORP Avl Bal Rs:15328.67. Not you?Call 18002333"

        val parsed = applySpec(spec, creditAlert, 1729750000000L, bank = "UNION")
        assertNotNull("Credit alert on learned template must parse", parsed)
        assertEquals(Direction.CREDIT, parsed!!.direction)
        assertEquals(1000000L, parsed.amountPaise)
        assertEquals("SALARY CORP", parsed.payee)
        assertEquals(1532867L, parsed.availableBalancePaise)
    }

    @Test
    fun `learned template matches future message even if disclaimer trailer changes or is omitted`() {
        val origMsg = "Union Bank of India A/c *2209 Debited Rs:1755.00 on 22-10-2026 10:40:05 by Mob Bk ref no 629576250805, " +
            "Fvg: AONE TRA Avl Bal Rs:5328.67. Not you?Call 18002333/SMS BLOCK 2209 to 8879365472"

        val learned = TemplateLearner.learn(
            rawMessage = origMsg,
            amountPaise = 175500L,
            direction = Direction.DEBIT,
            account = unionAccount,
            payee = "AONE TRA",
        )
        assertNotNull(learned)
        val spec = learned!!.toPatternSpec()

        // Future message has a completely different helpline disclaimer trailer
        val changedTrailerAlert = "Union Bank of India A/c *2209 Debited Rs:500.00 on 25-10-2026 12:00:00 by Mob Bk ref no 445566778899, " +
            "Fvg: STORE Avl Bal Rs:4828.67. If not you, Call 1800999999 immediately"

        val parsed = applySpec(spec, changedTrailerAlert, 1729850000000L, bank = "UNION")
        assertNotNull("Message with changed disclaimer trailer must parse", parsed)
        assertEquals(50000L, parsed!!.amountPaise)
        assertEquals("STORE", parsed.payee)
    }
}
