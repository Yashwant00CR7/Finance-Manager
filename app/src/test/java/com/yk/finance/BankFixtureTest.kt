package com.yk.finance

import com.yk.finance.parser.GENERIC_PATTERNS
import com.yk.finance.parser.ParseResult
import com.yk.finance.parser.RESEARCHED_PATTERNS
import com.yk.finance.parser.RuleBasedParser
import com.yk.finance.parser.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds every researched pattern against the sample it was written from.
 *
 * Kept apart from ParserTest on purpose. That file asserts against messages the author
 * actually received, and merging these into it would quietly promote a stranger's forum post
 * to the same standing as a real alert. The distinction is the whole basis on which the app
 * decides what may book itself without asking.
 */
class BankFixtureTest {

    private val parser = RuleBasedParser()
    private val now = 1_800_000_000_000L // 2027, comfortably after every sample date

    private fun parse(sender: String, body: String) =
        (parser.parse(sender, body, now) as? ParseResult.Parsed)?.sms

    @Test
    fun `every researched fixture parses into the values its sample shows`() {
        val failures = mutableListOf<String>()

        BANK_FIXTURES.forEach { f ->
            val sms = parse(f.sender, f.body)
            if (sms == null) {
                failures += "${f.patternId}: did not parse at all"
                return@forEach
            }
            fun check(what: String, expected: Any?, actual: Any?) {
                if (expected != actual) failures += "${f.patternId}: $what expected <$expected> was <$actual>"
            }
            check("patternId", f.patternId, sms.patternId)
            check("bank", f.bank, sms.bank)
            check("direction", f.direction, sms.direction)
            check("amountPaise", f.amountPaise, sms.amountPaise)
            check("accountToken", f.account, sms.accountToken)
            check("isCard", f.isCard, sms.isCard)
            if (f.payee != null) check("payee", f.payee, sms.payee)
        }

        assertTrue(
            "${failures.size} fixture mismatches:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    /**
     * The most expensive mistake available in this file.
     *
     * A card alert's "Avl Lmt" is money the bank will lend, not money you have. Written to an
     * account balance it would add a debt to your net worth as though it were savings.
     */
    @Test
    fun `a card alert never yields an account balance`() {
        BANK_FIXTURES.filter { it.isCard }.forEach { f ->
            val sms = parse(f.sender, f.body)
            assertNotNull("${f.patternId} must parse", sms)
            assertNull(
                "${f.patternId}: a credit limit must never become an account balance",
                sms!!.availableBalancePaise,
            )
        }
    }

    @Test
    fun `no researched pattern books itself`() {
        BANK_FIXTURES.forEach { f ->
            val sms = parse(f.sender, f.body)
            assertEquals(
                "${f.patternId} must stay untrusted until a person confirms it",
                Tier.RESEARCHED,
                sms?.tier,
            )
        }
    }

    @Test
    fun `nothing in the negative corpus ever becomes a transaction`() {
        val booked = NEGATIVE_FIXTURES.mapNotNull { f ->
            val result = parser.parse(f.sender, f.body, now)
            (result as? ParseResult.Parsed)?.let { "${f.why}\n    -> parsed as ${it.sms.patternId}" }
        }
        assertTrue(
            "${booked.size} messages that must never book, did:\n" + booked.joinToString("\n"),
            booked.isEmpty(),
        )
    }

    // ---------- properties of the table itself ----------

    @Test
    fun `every pattern id is unique`() {
        val all = RESEARCHED_PATTERNS + GENERIC_PATTERNS
        val duplicates = all.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue("pattern ids must be unique, these are not: $duplicates", duplicates.isEmpty())
    }

    /**
     * The evidence rule, enforced rather than trusted.
     *
     * A researched pattern with no citation is indistinguishable from one somebody invented
     * because it looked about right, and that is the failure this whole design exists to
     * prevent. PatternSpec's init block rejects it at construction; this asserts it too, so
     * the rule is visible in the test report and not only in a require().
     */
    @Test
    fun `every researched pattern cites its source`() {
        val uncited = RESEARCHED_PATTERNS.filter { it.evidence.isNullOrBlank() }.map { it.id }
        assertTrue("researched patterns with no evidence: $uncited", uncited.isEmpty())
    }

    /**
     * A pattern nobody ever saw match anything is a liability, not a feature: it is untested
     * regex that will fire on a stranger's phone before it fires here.
     */
    @Test
    fun `every researched pattern has at least one fixture`() {
        val covered = BANK_FIXTURES.map { it.patternId }.toSet()
        val orphans = RESEARCHED_PATTERNS.map { it.id }.filterNot { it in covered }
        assertTrue("patterns with no fixture: $orphans", orphans.isEmpty())
    }

    @Test
    fun `generic patterns can never book, whatever they match`() {
        assertTrue(
            "the generic tier must stay generic",
            GENERIC_PATTERNS.all { it.tier == Tier.GENERIC },
        )
    }

    /**
     * Regression for a bug that was live before this change.
     *
     * "cashback" was rejected anywhere in a body, so a cashback that had actually been paid
     * was dropped without so much as a tray entry - real money, silently missing from the
     * ledger. It must now at least reach the tray.
     */
    @Test
    fun `a cashback that was actually paid is not discarded as promotional`() {
        val body = "Cashback of Rs.50.00 has been sent to your Kotak Bank A/c x5555. " +
            "Credited on 14-10-25. UPI Ref 9999999999"
        val result = parser.parse("VM-KOTAKB", body, now)
        assertTrue(
            "a paid cashback must not be thrown away as an offer, got $result",
            result !is ParseResult.Ignored,
        )
    }

    @Test
    fun `a cashback offer is still rejected`() {
        val result = parser.parse(
            "VM-KOTAKB",
            "Spend Rs 500 on your Kotak card & get Rs 100 cashback!",
            now,
        )
        assertTrue("an offer must still be ignored, got $result", result is ParseResult.Ignored)
    }
}
