package com.yk.finance

import com.yk.finance.data.Account
import com.yk.finance.data.AccountKind
import com.yk.finance.domain.TemplateLearner
import com.yk.finance.domain.toPatternSpec
import com.yk.finance.parser.Channel
import com.yk.finance.parser.Direction
import com.yk.finance.parser.ParseResult
import com.yk.finance.parser.RuleBasedParser
import com.yk.finance.parser.applySpec
import com.yk.finance.parser.extractGenericPayee
import com.yk.finance.parser.extractPreFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hard stress test battery for SMS Parser and Template Learning lifecycle.
 * Validates complex Indian currency formats, punctuation/whitespace irregularities,
 * dense multi-numeric structures, anti-fraud/declined filters, and direction switching.
 */
class ParserAndLearningStressTest {

    private val parser = RuleBasedParser()
    private val unionSender = "JM-UNIONB-T"
    private val now = 1_793_000_000_000L

    private fun parseUnion(body: String) =
        (parser.parse(unionSender, body, now) as? ParseResult.Parsed)?.sms

    // =========================================================================
    // 1. PARSER HARD BATTERY: CURRENCY, PAYEE COMPLEXITY, DENSE NUMERICS
    // =========================================================================

    @Test
    fun `parses Indian lakhs and multi-comma amounts correctly`() {
        val lakhsDebit = "Union Bank of India A/c *2209 Debited Rs:1,25,000.50 on 22-10-2026 10:40:05 by Mob Bk ref no 629576250805, " +
            "Fvg: LUXURY MOTORS Avl Bal Rs:15,53,280.00. Not you?Call 18002333"

        val sms = parseUnion(lakhsDebit)
        assertNotNull("Lakhs debit must parse", sms)
        assertEquals(Direction.DEBIT, sms!!.direction)
        assertEquals(12500050L, sms.amountPaise)
        assertEquals("2209", sms.accountToken)
        assertEquals("LUXURY MOTORS", sms.payee)
        assertEquals(155328000L, sms.availableBalancePaise)

        val lakhsCredit = "Union Bank of India A/c *2209 Credited Rs:10,00,000.00 on 22-10-2026 11:15:00 by Mob Bk ref no 778899112233, " +
            "Fvg: TREASURY DEPT Avl Bal Rs:25,53,280.00. Not you?Call 18002333"

        val creditSms = parseUnion(lakhsCredit)
        assertNotNull("Ten lakhs credit must parse", creditSms)
        assertEquals(Direction.CREDIT, creditSms!!.direction)
        assertEquals(100000000L, creditSms.amountPaise)
        assertEquals("2209", creditSms.accountToken)
        assertEquals("TREASURY DEPT", creditSms.payee)
        assertEquals(255328000L, creditSms.availableBalancePaise)
    }

    @Test
    fun `parses complex payee names with dots, initials, slashes, and ampersands`() {
        val initialsMsg = "Union Bank of India A/c *2209 Debited Rs:4500.00 on 22-10-2026 14:00:00 by Mob Bk ref no 112233445566, " +
            "Fvg: MR. A.B. SHARMA & CO. Avl Bal Rs:50000.00. Not you?Call 18002333"

        val initialsSms = parseUnion(initialsMsg)
        assertNotNull(initialsSms)
        assertEquals("MR. A.B. SHARMA & CO.", initialsSms!!.payee)

        val slashMsg = "Union Bank of India A/c *2209 Debited Rs:320.00 on 22-10-2026 15:30:00 by Mob Bk ref no 998877665544, " +
            "Fvg: GOOGLE*SERVICES/PLAYSTORE Avl Bal Rs:49680.00. Not you?Call 18002333"

        val slashSms = parseUnion(slashMsg)
        assertNotNull(slashSms)
        assertEquals("GOOGLE*SERVICES/PLAYSTORE", slashSms!!.payee)
    }

    @Test
    fun `parses high density numeric text without mixing account, ref, amount, bal, or phone numbers`() {
        val denseMsg = "Union Bank of India A/c *2209 Debited Rs:1755.00 on 22-10-2026 10:40:05 by Mob Bk ref no 629576250805, " +
            "Fvg: AONE TRA Avl Bal Rs:5328.67. Not you?Call 18002333/SMS BLOCK 2209 to 8879365472"

        val sms = parseUnion(denseMsg)
        assertNotNull(sms)
        assertEquals("2209", sms!!.accountToken)
        assertEquals(175500L, sms.amountPaise)
        assertEquals("629576250805", sms.reference)
        assertEquals(532867L, sms.availableBalancePaise)
        assertEquals("AONE TRA", sms.payee)
        assertEquals(Channel.MOBILE_BANKING, sms.channel)
    }

    @Test
    fun `handles whitespace and delimiter irregularities like missing space after colon and newlines`() {
        val irregularMsg = "Union Bank of India\nA/c *2209\nDebited Rs:1755.00 on 22-10-2026 10:40:05 by Mob Bk ref no 629576250805,\n" +
            "Fvg:AONE TRA Avl Bal Rs:5328.67.\nNot you?Call 18002333"

        val sms = parseUnion(irregularMsg)
        assertNotNull("Irregular whitespace with newlines must parse", sms)
        assertEquals(175500L, sms!!.amountPaise)
        assertEquals("AONE TRA", sms.payee)
    }

    // =========================================================================
    // 2. DEFENSIVE GUARDS: DECLINED, MANDATES, FRAUD, AND PERSONAL SENDER
    // =========================================================================

    @Test
    fun `declined and failed transactions are never booked as completed transfers`() {
        val declined = "Union Bank of India A/c *2209: Transaction of Rs 1755.00 was declined due to insufficient balance."
        val result1 = parser.parse(unionSender, declined, now)
        assertTrue("Declined transaction must not parse as Parsed", result1 !is ParseResult.Parsed)

        val failed = "Your payment of Rs. 2,000.00 from Union Bank A/c *2209 failed. Any debited amount will be refunded."
        val result2 = parser.parse(unionSender, failed, now)
        assertTrue("Failed transaction must not parse as Parsed", result2 !is ParseResult.Parsed)
    }

    @Test
    fun `mandate requests and fraud alerts are rejected`() {
        val mandate = "Payment request of Rs. 1,500.00 received on your Union Bank A/c *2209 for Spotify AutoPay."
        val result1 = parser.parse(unionSender, mandate, now)
        assertTrue("Mandate request must not parse as completed spend", result1 !is ParseResult.Parsed)

        val fraudWarning = "Rs.10,000 debited? If not you, call 18002333 immediately to block your account."
        val result2 = parser.parse(unionSender, fraudWarning, now)
        assertTrue("Fraud warning must not become a transaction", result2 !is ParseResult.Parsed)
    }

    @Test
    fun `messages from personal 10-digit mobile numbers are ignored even if transaction shaped`() {
        val fakeMsg = "Union Bank of India A/c *2209 Debited Rs:1755.00 on 22-10-2026 10:40:05 by Mob Bk ref no 629576250805, Fvg: SCAMMER Avl Bal Rs:100.00"
        val result = parser.parse("+919876543210", fakeMsg, now)
        assertTrue("Personal number must be ignored", result is ParseResult.Ignored)
    }

    // =========================================================================
    // 3. END-TO-END TEMPLATE LEARNING: COOP BANK, DIRECTION SWITCHING, ISOLATION
    // =========================================================================

    private val apexAccount = Account(
        id = 101,
        displayName = "Apex Co-op Bank ..5544",
        bank = "APEX",
        accountToken = "5544",
        kind = AccountKind.BANK,
    )

    @Test
    fun `unrecognized cooperative bank alert learns template and dynamically matches future debits and credits`() {
        val origAlert = "Dear Member, your Apex Co-op Bank Acct **5544 is debited with INR 3,450.00 on 20-Oct-2026 15:30:00 " +
            "towards APEX WHOLESALE ref# APX98765. Avl Bal INR 45,000.00. Help: 1800-APEX"

        // Step 1: Parser does not recognise Apex Bank custom template
        val parseInitial = parser.parse("AD-APEXBK", origAlert, now)
        assertTrue("Unrecognized bank format is not parsed automatically", parseInitial !is ParseResult.Parsed)

        // Step 2: User records the transaction from review input
        val learned = TemplateLearner.learn(
            rawMessage = origAlert,
            amountPaise = 345000L,
            direction = Direction.DEBIT,
            account = apexAccount,
            payee = "APEX WHOLESALE",
        )
        assertNotNull("Template should be learned from user input", learned)
        val spec = learned!!.toPatternSpec()

        // Step 3: Next debit alert with different amount, date, time, payee, and reference auto-parses
        val futureDebit = "Dear Member, your Apex Co-op Bank Acct **5544 is debited with INR 820.50 on 22-Oct-2026 18:45:12 " +
            "towards CAFE DAY ref# APX11223. Avl Bal INR 44,179.50. Help: 1800-APEX"

        val parsedDebit = applySpec(spec, futureDebit, now, bank = "APEX")
        assertNotNull("Future debit alert must parse automatically", parsedDebit)
        assertEquals(82050L, parsedDebit!!.amountPaise)
        assertEquals(Direction.DEBIT, parsedDebit.direction)
        assertEquals("5544", parsedDebit.accountToken)
        assertEquals("CAFE DAY", parsedDebit.payee)
        assertEquals("APX11223", parsedDebit.reference)
        assertEquals(4417950L, parsedDebit.availableBalancePaise)

        // Step 4: Next credit alert with same template structure matches and flips direction to CREDIT (Money In)
        val futureCredit = "Dear Member, your Apex Co-op Bank Acct **5544 is credited with INR 12,000.00 on 23-Oct-2026 11:00:00 " +
            "towards CLIENT DEPOSIT ref# APX44556. Avl Bal INR 56,179.50. Help: 1800-APEX"

        val parsedCredit = applySpec(spec, futureCredit, now, bank = "APEX")
        assertNotNull("Future credit alert must parse automatically", parsedCredit)
        assertEquals(1200000L, parsedCredit!!.amountPaise)
        assertEquals(Direction.CREDIT, parsedCredit.direction)
        assertEquals("5544", parsedCredit.accountToken)
        assertEquals("CLIENT DEPOSIT", parsedCredit.payee)
        assertEquals("APX44556", parsedCredit.reference)
        assertEquals(5617950L, parsedCredit.availableBalancePaise)
    }

    @Test
    fun `learned pattern is bank-isolated and does not match phishing from another sender`() {
        val origAlert = "Dear Member, your Apex Co-op Bank Acct **5544 is debited with INR 3,450.00 on 20-Oct-2026 15:30:00 " +
            "towards APEX WHOLESALE ref# APX98765. Avl Bal INR 45,000.00. Help: 1800-APEX"

        val learned = TemplateLearner.learn(
            rawMessage = origAlert,
            amountPaise = 345000L,
            direction = Direction.DEBIT,
            account = apexAccount,
            payee = "APEX WHOLESALE",
        )
        assertNotNull(learned)
        val spec = learned!!.toPatternSpec()

        // Phishing attempt from a different bank
        val phishingAttempt = "Dear Member, your Fake Bank Acct **5544 is debited with INR 3,450.00 on 20-Oct-2026 15:30:00 " +
            "towards APEX WHOLESALE ref# APX98765. Avl Bal INR 45,000.00. Help: 1800-APEX"

        val parsed = applySpec(spec, phishingAttempt, now, bank = "FAKE")
        assertNull("Phishing message with different bank text must not match", parsed)
    }

    // =========================================================================
    // 4. INBOX PRE-FILL CORNER CASES
    // =========================================================================

    @Test
    fun `inbox prefill correctly extracts complex payees and handles non-standard spacings`() {
        val complex = "Union Bank of India A/c *2209 Debited Rs:125,000.00 on 22-10-2026 10:40:05 by Mob Bk ref no 629576250805, " +
            "Fvg: DR. S.K. SEN & ASSOCIATES Avl Bal Rs:500,000.00"

        val prefill = extractPreFill(complex)
        assertEquals("125000.00", prefill.amountRupees)
        assertTrue(prefill.isDebit)
        assertEquals("DR. S.K. SEN & ASSOCIATES", prefill.payee)
    }
}
