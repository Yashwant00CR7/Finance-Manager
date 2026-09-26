package com.yk.finance

import com.yk.finance.parser.Channel
import com.yk.finance.parser.Direction
import com.yk.finance.parser.ParseResult
import com.yk.finance.parser.RuleBasedParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every message in REAL_* below is an actual message from the user's phone (amounts
 * and names as supplied). These are the regression suite: if a change breaks one of
 * these, it breaks the app on the only data that matters.
 */
class ParserTest {

    private val parser = RuleBasedParser()
    private val icici = "VM-ICICIB"
    private val union = "AD-UNIONB"
    private val now = 1_790_000_000_000L // well after all sample dates

    private fun parsed(sender: String, body: String) =
        (parser.parse(sender, body, now) as? ParseResult.Parsed)?.sms

    // ---------- ICICI debits: note every one contains the word "credited" ----------

    private val REAL_ICICI_DEBIT =
        "ICICI Bank Acct XX742 debited for Rs 169.00 on 11-Sep-26; SRI LAKSHMI TRA credited. " +
            "UPI:412233445566. Call 18002662 for dispute. SMS BLOCK 742 to 9000000000."

    @Test
    fun `ICICI debit is a DEBIT even though the body says credited`() {
        val sms = parsed(icici, REAL_ICICI_DEBIT)
        assertNotNull("real ICICI debit must parse", sms)
        // The single most important assertion in this file. The payee is "credited";
        // keying direction on the keyword would file every spend as income.
        assertEquals(Direction.DEBIT, sms!!.direction)
        assertEquals(16900L, sms.amountPaise)
        assertEquals("742", sms.accountToken)
        assertEquals("SRI LAKSHMI TRA", sms.payee)
        assertEquals("412233445566", sms.reference)
        assertEquals(Channel.UPI, sms.channel)
        assertNull("ICICI never sends Avl Bal", sms.availableBalancePaise)
    }

    @Test
    fun `all five real ICICI debits parse as debits with correct amounts`() {
        val cases = listOf(
            REAL_ICICI_DEBIT to 16900L,
            "ICICI Bank Acct XX742 debited for Rs 10.00 on 14-Sep-26; Ramesh S credited. UPI:523344556677." to 1000L,
            "ICICI Bank Acct XX742 debited for Rs 3000.00 on 16-Sep-26; A K SHARMA credited. UPI:634455667788." to 300000L,
            "ICICI Bank Acct XX742 debited for Rs 22.00 on 17-Sep-26; paytmqr 1a2b3cd credited. UPI:745566778899." to 2200L,
            "ICICI Bank Acct XX742 debited for Rs 50.00 on 18-Sep-26; KA 05 JUICE BAR credited. UPI:856677889900." to 5000L,
        )
        cases.forEach { (body, expected) ->
            val sms = parsed(icici, body)
            assertNotNull("failed to parse: $body", sms)
            assertEquals(body, Direction.DEBIT, sms!!.direction)
            assertEquals(body, expected, sms.amountPaise)
        }
    }

    @Test
    fun `ICICI credit uses a different sentence shape and still parses`() {
        val sms = parsed(
            icici,
            "Dear Customer, Acct XX742 is credited with Rs 3000.00 on 17-Sep-26 from R SRINIVASAN . " +
                "UPI:967788990011-ICICI Bank.",
        )
        assertNotNull(sms)
        assertEquals(Direction.CREDIT, sms!!.direction)
        assertEquals(300000L, sms.amountPaise)
        assertEquals("R SRINIVASAN", sms.payee)
        assertEquals("967788990011", sms.reference)
    }

    // ---------- Union Bank ----------

    private val REAL_UNION_CREDIT =
        "A/c *8317 Credited for Rs:3000.00 on 16-09-2026 11:23:34 by Mob Bk ref no 634455667788 " +
            "Avl Bal Rs:2841.37.Never Share OTP/PIN/CVV-Union Bank of India"

    private val REAL_UNION_DEBIT =
        "A/c *8317 Debited for Rs:70.00 on 15-05-2026 10:57:50 by Mob Bk ref no 178899001122 " +
            "Avl Bal Rs:912.68.If not you, Call 1800222243 -Union Bank of India"

    @Test
    fun `real Union messages parse despite containing OTP and If-not-you trailers`() {
        // Regression guard: blocklisting "OTP" or "If not you" would reject every
        // genuine Union Bank transaction. Both strings appear in real messages.
        val credit = parsed(union, REAL_UNION_CREDIT)
        assertNotNull("Union credit containing 'Never Share OTP/PIN/CVV' must still parse", credit)
        assertEquals(Direction.CREDIT, credit!!.direction)
        assertEquals(300000L, credit.amountPaise)
        assertEquals("8317", credit.accountToken)
        assertEquals(284137L, credit.availableBalancePaise)

        val debit = parsed(union, REAL_UNION_DEBIT)
        assertNotNull("Union debit containing 'If not you, Call' must still parse", debit)
        assertEquals(Direction.DEBIT, debit!!.direction)
        assertEquals(7000L, debit.amountPaise)
        assertEquals(91268L, debit.availableBalancePaise)
    }

    @Test
    fun `Union second real credit parses with balance`() {
        val sms = parsed(
            union,
            "A/c *8317 Credited for Rs:5000.00 on 10-07-2026 10:00:59 by Mob Bk ref no 289900112233 " +
                "Avl Bal Rs:3390.22.Never Share OTP/PIN/CVV-Union Bank of India",
        )
        assertNotNull(sms)
        assertEquals(500000L, sms!!.amountPaise)
        assertEquals(339022L, sms.availableBalancePaise)
    }

    // ---------- rejection: the allowlist doing its job ----------

    @Test
    fun `fraud warning is not a transaction`() {
        val result = parser.parse(icici, "Rs.5,000 debited? If not you, call 1800266 immediately.", now)
        assertTrue("must not become a transaction", result !is ParseResult.Parsed)
    }

    @Test
    fun `future mandate is rejected outright`() {
        val result = parser.parse(
            icici,
            "Dear Customer, your Acct XX742 will be debited with Rs 2500.00 on 25-Sep-26 for SIP.",
            now,
        )
        assertTrue(result is ParseResult.Ignored)
    }

    @Test
    fun `promotional offer is rejected`() {
        val result = parser.parse(icici, "Spend Rs 500 on your ICICI card & get Rs 100 cashback!", now)
        assertTrue(result is ParseResult.Ignored)
    }

    @Test
    fun `transaction-shaped message from a personal mobile number is rejected`() {
        val result = parser.parse("9876543210", REAL_ICICI_DEBIT, now)
        assertTrue("banks never send from personal numbers", result is ParseResult.Ignored)
    }

    @Test
    fun `unmatched but financial message goes to the review tray`() {
        val result = parser.parse(
            icici,
            "ICICI Bank: Rs 250.00 was spent using some format we have never seen before.",
            now,
        )
        assertTrue("must be queued, never silently dropped", result is ParseResult.NeedsReview)
    }

    @Test
    fun `OTP message does not reach the tray`() {
        val result = parser.parse(icici, "123456 is your ICICI Bank OTP. Do not share it.", now)
        assertTrue("junk must be dropped, not queued", result is ParseResult.Ignored)
    }

    // ATM used to be covered here by an invented ICICI format. Real ATM samples turned up
    // during the multi-bank work and showed the guess was wrong, so both the pattern and the
    // tests that propped it up were deleted rather than adjusted. This file is for messages
    // that actually arrived on this phone; the real ICICI ATM alert is third-party evidence
    // and is asserted in BankFixtureTest instead.

    @Test
    fun `amount parsing keeps paise exactly`() {
        val sms = parsed(union, REAL_UNION_DEBIT)
        assertEquals(91268L, sms!!.availableBalancePaise)
    }
}
