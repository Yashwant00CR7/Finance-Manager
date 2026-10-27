package com.yk.finance

import com.yk.finance.data.Account
import com.yk.finance.domain.TemplateLearner
import com.yk.finance.domain.toPatternSpec
import com.yk.finance.parser.Direction
import com.yk.finance.parser.ParseResult
import com.yk.finance.parser.ParsedSms
import com.yk.finance.parser.RuleBasedParser
import com.yk.finance.parser.Tier
import com.yk.finance.parser.accountTokensIn
import com.yk.finance.parser.describesFutureOrOffer
import com.yk.finance.parser.extractGenericPayee
import com.yk.finance.parser.occurredAtFor
import com.yk.finance.parser.parseIndianDate
import com.yk.finance.parser.senderBankKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Messages the parser used to lose, misread or misfile.
 *
 * Each case below failed before this suite was written, in one of three ways: dropped
 * without even reaching the tray, parsed with a payee that was really a phone number or our
 * own account, or held back by a verified rule over a token the bank had swapped.
 */
class ParserCoverageTest {

    private val parser = RuleBasedParser()
    private val now = 1_793_000_000_000L

    private fun parse(sender: String, body: String) = parser.parse(sender, body, now)
    private fun parsed(sender: String, body: String): ParsedSms {
        val result = parse(sender, body)
        assertTrue("expected a parse, got $result for: $body", result is ParseResult.Parsed)
        return (result as ParseResult.Parsed).sms
    }

    // ---------- the verified rules accept the tokens banks swap ----------

    @Test
    fun `ICICI debit with the rupee sign and a four-digit year still books`() {
        val sms = parsed(
            "AX-ICICIT-S",
            "ICICI Bank Acct XX742 debited for ₹199.00 on 01-Oct-2026; NETFLIX credited. UPI:627412345678.",
        )
        assertEquals(Tier.VERIFIED, sms.tier)
        assertEquals(Direction.DEBIT, sms.direction)
        assertEquals(19900L, sms.amountPaise)
        assertEquals("NETFLIX", sms.payee)
        assertEquals("627412345678", sms.reference)
    }

    @Test
    fun `a two-digit year is the date the bank stated, not the time the message arrived`() {
        // "dd-MMM-yyyy" read "26" as the year 26 AD, the sanity check discarded it, and the
        // receipt time stood in - for every ICICI alert, silently.
        val sms = parsed(
            "VM-ICICIB",
            "ICICI Bank Acct XX742 debited for Rs 169.00 on 11-Sep-26; SRI LAKSHMI TRA credited. UPI:412233445566.",
        )
        assertEquals(parseIndianDate("11-09-2026"), sms.occurredAt)
        assertEquals(parseIndianDate("07-01-2026"), parseIndianDate("07/01/26"))
        assertEquals(parseIndianDate("13-09-2025"), parseIndianDate("13Sep25"))
    }

    @Test
    fun `ICICI IMPS transfer carries its reference so the other leg can pair with it`() {
        val sms = parsed(
            "VM-ICICIB",
            "ICICI Bank Acct XX742 debited for Rs 5000.00 on 18-Sep-26 & Acct XX123 credited. " +
                "IMPS:626112345678. Call 18002662 for dispute. SMS BLOCK 742 to 9215676766.",
        )
        assertEquals(Tier.VERIFIED, sms.tier)
        assertEquals("626112345678", sms.reference)
    }

    @Test
    fun `Union with a two-digit year books on the verified rule`() {
        val sms = parsed(
            "JM-UNIONB-T",
            "A/c *2209 Debited for Rs:500.00 on 23-10-26 10:10:10 by Mob Bk ref no 629612345678 " +
                "Avl Bal Rs:4328.67.If not you, Call 1800222243 -Union Bank of India",
        )
        assertEquals(Tier.VERIFIED, sms.tier)
        assertEquals(50000L, sms.amountPaise)
        assertEquals(432867L, sms.availableBalancePaise)
    }

    @Test
    fun `Union with is between the account and the verb books on the verified rule`() {
        val sms = parsed(
            "JM-UNIONB-T",
            "Your A/c XXXXXXXX2209 is debited for Rs.150.00 on 24-10-2026 13:05:11 by UPI. " +
                "Avl Bal Rs.4678.67 -Union Bank of India",
        )
        assertEquals(Tier.VERIFIED, sms.tier)
        assertEquals("2209", sms.accountToken)
        assertEquals(15000L, sms.amountPaise)
    }

    @Test
    fun `Union NEFT credit keeps its alphanumeric reference`() {
        val sms = parsed(
            "JM-UNIONB-T",
            "A/c *2209 Credited for Rs:25000.00 on 30-10-2026 by NEFT ref no SBIN426303123456 " +
                "Avl Bal Rs:29828.67.Never Share OTP/PIN/CVV-Union Bank of India",
        )
        assertEquals("SBIN426303123456", sms.reference)
    }

    // ---------- payees that were not payees ----------

    @Test
    fun `Union SMS BLOCK number is never a payee`() {
        val sms = parsed(
            "JM-UNIONB-T",
            "A/c *2209 Debited for Rs:20.00 on 23-10-2026 10:10:10 by Mob Bk ref no 629612345678 " +
                "Avl Bal Rs:4308.67. SMS BLOCK 2209 to 8879365472",
        )
        // Not the phone number, and not "Mob Bk" either - a channel is not a counterparty,
        // and a rule keyed on it would file every Union payment the same way.
        assertNull(sms.payee)
    }

    @Test
    fun `Union credit still names its channel, which salary matching reads`() {
        val sms = parsed(
            "AD-UNIONB",
            "A/c *8317 Credited for Rs:3000.00 on 16-09-2026 11:23:34 by Mob Bk ref no 634455667788 " +
                "Avl Bal Rs:2841.37.Never Share OTP/PIN/CVV-Union Bank of India",
        )
        assertEquals("Mob Bk", sms.payee)
    }

    @Test
    fun `our own account is never the payee of a credit`() {
        val sms = parsed(
            "JM-UNIONB-T",
            "Dear Customer, Rs.1,000.00 credited to your A/c XX2209 on 24/10/2026 by UPI ref no 629712345678. " +
                "-Union Bank of India",
        )
        assertEquals(Direction.CREDIT, sms.direction)
        assertNull(sms.payee)
    }

    @Test
    fun `a credit names the VPA that sent it, not the bank account it landed in`() {
        val sms = parsed(
            "AX-HDFCBK-S",
            "Credit Alert!\nRs.1.00 credited to HDFC Bank A/c XX1234 on 20-10-26 from VPA abc@okicici (UPI 629812345678)",
        )
        assertEquals(Direction.CREDIT, sms.direction)
        assertEquals("1234", sms.accountToken)
        assertEquals("abc@okicici", sms.payee)
        assertEquals("629812345678", sms.reference)
    }

    @Test
    fun `a UPI path names its payee and its reference`() {
        val sms = parsed(
            "AX-AXISBK-S",
            "INR 500.00 debited\nA/c no. XX1234\n20-10-26, 10:15:30\nUPI/P2M/629812345678/SWIGGY\n" +
                "Not you? SMS BLOCKUPI Cust ID to 919951860002\nAxis Bank",
        )
        assertEquals(Direction.DEBIT, sms.direction)
        assertEquals(50000L, sms.amountPaise)
        assertEquals("SWIGGY", sms.payee)
        assertEquals("629812345678", sms.reference)
    }

    @Test
    fun `junk candidates are skipped in favour of a real one later in the message`() {
        assertEquals(
            "payment@okhdfcbank",
            extractGenericPayee("Rs 40 credited to your A/c XX9999 by VPA payment@okhdfcbank", Direction.CREDIT),
        )
        assertNull(extractGenericPayee("Rs 40 debited. SMS BLOCK 2209 to 8879365472", Direction.DEBIT))
        assertNull(extractGenericPayee("Please update your KYC at nearest branch.", Direction.DEBIT))
    }

    // ---------- messages that used to vanish without reaching the tray ----------

    @Test
    fun `money received is read, not dropped as not-financial`() {
        val sms = parsed(
            "AX-ICICIT-S",
            "You have received Rs 250.00 in your ICICI Bank Acct XX742 from RAMESH on 20-Oct-26. UPI Ref 629812345678",
        )
        assertEquals(Tier.GENERIC, sms.tier)
        assertEquals(Direction.CREDIT, sms.direction)
        assertEquals(25000L, sms.amountPaise)
        assertEquals("742", sms.accountToken)
        assertEquals("RAMESH", sms.payee)
    }

    @Test
    fun `an account written out in full is read`() {
        val sms = parsed(
            "AX-ICICIT-S",
            "Dear Customer, your ICICI Bank Account XX742 has been credited with INR 50,000.00 on 30-Sep-26. " +
                "Info:NEFT-ACME. The Available Balance is INR 62,345.67.",
        )
        assertEquals(Direction.CREDIT, sms.direction)
        assertEquals(5000000L, sms.amountPaise)
        assertEquals(6234567L, sms.availableBalancePaise)
    }

    @Test
    fun `SBI UPI debit with no currency in front of the amount is read`() {
        val sms = parsed(
            "VM-SBIUPI",
            "Dear UPI user A/C X1234 debited by 50.0 on date 20Oct26 trf to SWIGGY Refno 629812345678. " +
                "If not u? call 1800111109. -SBI",
        )
        assertEquals(Direction.DEBIT, sms.direction)
        assertEquals(5000L, sms.amountPaise)
        assertEquals("1234", sms.accountToken)
        assertEquals("SWIGGY", sms.payee)
        assertEquals("629812345678", sms.reference)
    }

    @Test
    fun `Dr written short is read as a debit`() {
        val sms = parsed("AX-AXISBK-S", "Rs 500 Dr from A/c XX1234 on 20-10-26 UPI/629812345678. Avl Bal Rs 1000")
        assertEquals(Direction.DEBIT, sms.direction)
        assertEquals(50000L, sms.amountPaise)
        assertEquals(100000L, sms.availableBalancePaise)
    }

    @Test
    fun `a bank nobody registered still reaches the tray when it names an account`() {
        val result = parse(
            "AD-APEXBK",
            "Dear Member, your Apex Co-op Bank Acct **5544 is debited with INR 3,450.00 on 20-Oct-2026 15:30:00 " +
                "towards APEX WHOLESALE ref# APX98765. Avl Bal INR 45,000.00.",
        )
        assertTrue("must be queued, not dropped: $result", result is ParseResult.NeedsReview)
    }

    @Test
    fun `a debit with a due-date reminder on the end is still a debit`() {
        val body = "Your EMI of Rs 5000 debited from A/c *2209 on 05-10-2026. Next EMI is due on 05-11-2026. " +
            "-Union Bank of India"
        assertNull(describesFutureOrOffer(body))
        val sms = parsed("JM-UNIONB-T", body)
        assertEquals(Direction.DEBIT, sms.direction)
        assertEquals(500000L, sms.amountPaise)
    }

    // ---------- and what must still stay out ----------

    @Test
    fun `reminders and notices with no completed movement are still rejected`() {
        listOf(
            "Your EMI of Rs 5000 for loan XX12 is due on 05-11-2026. Please keep sufficient balance.",
            "Your HDFC Bank Credit Card XX1234 statement has been generated. Total amount due Rs 15,000.",
            "Rs 2500 is to be debited from your A/c XX2209 on 05-11-2026 for SIP.",
        ).forEach { body ->
            assertNotNull("must stay rejected: $body", describesFutureOrOffer(body))
        }
    }

    @Test
    fun `an OTP or a delivery notice from an unknown sender does not reach the tray`() {
        listOf(
            "123456 is your OTP for login. Do not share it with anyone.",
            "Your order of Rs 499 has been shipped and will be delivered tomorrow.",
        ).forEach { body ->
            assertTrue(body, parse("AD-SHOPCO", body) is ParseResult.Ignored)
        }
    }

    // ---------- learned templates sit behind the same gates as everything else ----------

    private val apexMessage =
        "Dear Member, your Apex Co-op Bank Acct **5544 is debited with INR 3,450.00 on 20-Oct-2026 15:30:00 " +
            "towards APEX WHOLESALE ref# APX98765. Avl Bal INR 45,000.00."

    private val apexNext =
        "Dear Member, your Apex Co-op Bank Acct **5544 is debited with INR 820.50 on 22-Oct-2026 18:45:12 " +
            "towards CAFE DAY ref# APX11223. Avl Bal INR 44,179.50."

    private fun apexTemplate() = TemplateLearner.learn(
        rawMessage = apexMessage,
        amountPaise = 345000L,
        direction = Direction.DEBIT,
        account = Account(id = 1, displayName = "Apex", bank = "APEXBK", accountToken = "5544"),
        payee = "APEX WHOLESALE",
        bank = senderBankKey("AD-APEXBK", apexMessage)!!,
    )!!.toPatternSpec()

    @Test
    fun `a learned template books the bank's next message`() {
        val result = parser.parse("AD-APEXBK", apexNext, now, listOf(apexTemplate()))
        val sms = (result as ParseResult.Parsed).sms
        assertEquals(Tier.VERIFIED, sms.tier)
        assertEquals("APEXBK", sms.bank)
        assertEquals(82050L, sms.amountPaise)
        assertEquals("CAFE DAY", sms.payee)
    }

    @Test
    fun `a learned template never reads a message from a personal number`() {
        val result = parser.parse("+919876543210", apexNext, now, listOf(apexTemplate()))
        assertTrue(result is ParseResult.Ignored)
    }

    @Test
    fun `a learned template is never tried against another bank's messages`() {
        val result = parser.parse("AD-OTHRBK", apexNext, now, listOf(apexTemplate()))
        assertTrue("another bank's template must not book: $result", result !is ParseResult.Parsed)
    }

    @Test
    fun `a learned template never books a message announcing a future debit`() {
        val future = apexNext.replace("is debited with", "will be debited with")
        assertTrue(parser.parse("AD-APEXBK", future, now, listOf(apexTemplate())) is ParseResult.Ignored)
    }

    // ---------- small pieces ----------

    @Test
    fun `account tokens are read from every common mask`() {
        assertEquals(listOf("2209"), accountTokensIn("A/c *2209 Debited"))
        assertEquals(listOf("5468"), accountTokensIn("debited A/cXX5468 and credited to SAI"))
        assertEquals(listOf("742", "123"), accountTokensIn("Acct XX742 debited & Acct XX123 credited"))
        assertEquals(listOf("4321"), accountTokensIn("Credit Card ending 4321 at AMAZON"))
        assertTrue(accountTokensIn("SMS BLOCK 2209 to 8879365472").isEmpty())
    }

    @Test
    fun `a message is dated by what it says, not by when it was filed`() {
        val received = parseIndianDate("25-10-2026", "09:00:00")!!
        assertEquals(
            parseIndianDate("22-10-2026", "10:40:05"),
            occurredAtFor("Debited Rs:1755.00 on 22-10-2026 10:40:05 by Mob Bk", received),
        )
        assertEquals(
            parseIndianDate("20-10-2026"),
            occurredAtFor("debited by 50.0 on date 20Oct26 trf to SWIGGY", received),
        )
        // A stated date with no time, on the day it arrived, keeps the arrival time.
        assertEquals(received, occurredAtFor("debited by 50.0 on date 25Oct26", received))
        assertEquals(received, occurredAtFor("Rs 50 debited from A/c XX1234", received))
    }
}
