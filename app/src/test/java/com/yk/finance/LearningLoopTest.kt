package com.yk.finance

import com.yk.finance.data.Account
import com.yk.finance.data.AccountKind
import com.yk.finance.domain.IngestOutcome
import com.yk.finance.domain.SmsIngestor
import com.yk.finance.domain.TemplateLearner
import com.yk.finance.parser.Direction
import com.yk.finance.parser.RuleBasedParser
import com.yk.finance.parser.parseIndianDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tray-to-template loop, run through the real ingestor and repository.
 *
 * The loop is the app's answer to a bank it has no pattern for: you file one message by
 * hand, and the next one of that shape files itself. Every test here is a way it used to
 * break without saying so - the message never reaching the tray, the template learned
 * under a bank no message would ever come from, or "confirm once" asking again forever.
 */
class LearningLoopTest {

    private val dao = InMemoryDao()
    private val repo = dao.repository()
    private val ingestor = SmsIngestor(dao, RuleBasedParser())
    private val received = parseIndianDate("25-10-2026", "09:00:00")!!

    private val apex = "AD-APEXBK"
    private fun apexDebit(amount: String, payee: String, ref: String, bal: String, date: String = "20-Oct-2026 15:30:00") =
        "Dear Member, your Apex Co-op Bank Acct **5544 is debited with INR $amount on $date " +
            "towards $payee ref# $ref. Avl Bal INR $bal. Help: 1800-APEX"

    private suspend fun declared(name: String, opening: Long): Account {
        repo.createDeclaredAccount(name, opening, isCash = false)
        return dao.accounts.values.single { it.displayName == name }
    }

    private suspend fun account(name: String, bank: String, token: String): Account {
        val row = Account(displayName = name, bank = bank, accountToken = token)
        return row.copy(id = dao.insertAccount(row))
    }

    @Test
    fun `filing an unknown bank's message against a hand-added account links them, and the next one files itself`() = runTest {
        val savings = declared("Co-op Savings", 50_000_00)

        assertTrue(ingestor.ingest(apex, apexDebit("3,450.00", "APEX WHOLESALE", "APX98765", "45,000.00"), received) is IngestOutcome.Queued)
        val review = dao.reviews.values.single()

        repo.recordFromReview(review.id, savings.id, 3_450_00, Direction.DEBIT, "APEX WHOLESALE", null)

        // Filed under the bank that sent it - not "UNBOUND", which nothing would ever match.
        assertEquals("APEXBK", dao.learned.values.single().bank)
        // The hand-added account took the digits, so the bank's messages now belong to it.
        val linked = dao.accounts[savings.id]!!
        assertEquals("APEXBK", linked.bank)
        assertEquals("5544", linked.accountToken)
        // Dated by the message, not by the tap three days later.
        val filed = dao.txns.values.single()
        assertEquals(parseIndianDate("20-10-2026", "15:30:00"), filed.occurredAt)
        assertTrue(dao.reviews.isEmpty())

        val next = ingestor.ingest(apex, apexDebit("820.50", "CAFE DAY", "APX11223", "44,179.50", "22-Oct-2026 18:45:12"), received)

        assertEquals(savings.id, (next as IngestOutcome.Recorded).accountId)
        assertEquals(1, dao.accounts.size)
        assertEquals(44_179_50, dao.accounts[savings.id]!!.currentBalancePaise)
        assertEquals("CAFE DAY", dao.txns[next.txnId]!!.payee)
    }

    @Test
    fun `a copy of a learned message from a personal number is never booked`() = runTest {
        val savings = declared("Co-op Savings", 0)
        ingestor.ingest(apex, apexDebit("3,450.00", "APEX WHOLESALE", "APX98765", "45,000.00"), received)
        repo.recordFromReview(dao.reviews.values.single().id, savings.id, 3_450_00, Direction.DEBIT, "APEX WHOLESALE", null)

        val forged = ingestor.ingest("+919876543210", apexDebit("49,999.00", "SCAMMER", "APX00001", "1.00"), received)

        assertTrue(forged is IngestOutcome.Ignored)
        assertEquals(1, dao.txns.size)
    }

    @Test
    fun `filing one message files the identical ones queued beside it`() = runTest {
        val savings = declared("Co-op Savings", 0)
        ingestor.ingest(apex, apexDebit("3,450.00", "APEX WHOLESALE", "APX98765", "45,000.00"), received)
        ingestor.ingest(apex, apexDebit("120.00", "MILK BOOTH", "APX98766", "44,880.00", "21-Oct-2026 07:10:00"), received + 1)
        ingestor.ingest(apex, apexDebit("75.00", "BAKERY", "APX98767", "44,805.00", "21-Oct-2026 08:00:00"), received + 2)
        assertEquals(3, dao.reviews.size)

        val first = dao.reviews.values.minBy { it.receivedAt }
        repo.recordFromReview(first.id, savings.id, 3_450_00, Direction.DEBIT, "APEX WHOLESALE", null)

        assertTrue("the backlog should have filed itself", dao.reviews.isEmpty())
        assertEquals(setOf("APEX WHOLESALE", "MILK BOOTH", "BAKERY"), dao.txns.values.map { it.payee }.toSet())
        assertTrue(dao.txns.values.all { it.accountId == savings.id })
    }

    @Test
    fun `recording the same tray entry twice files it once`() = runTest {
        val savings = declared("Co-op Savings", 0)
        ingestor.ingest(apex, apexDebit("3,450.00", "APEX WHOLESALE", "APX98765", "45,000.00"), received)
        val id = dao.reviews.values.single().id

        repo.recordFromReview(id, savings.id, 3_450_00, Direction.DEBIT, "APEX WHOLESALE", null)
        repo.recordFromReview(id, savings.id, 3_450_00, Direction.DEBIT, "APEX WHOLESALE", null)

        assertEquals(1, dao.txns.size)
    }

    @Test
    fun `confirming a generic reading learns the sentence, so the next one does not ask`() = runTest {
        val sbi = "VM-SBIUPI"
        val first = "Dear UPI user A/C X1234 debited by 50.0 on date 20Oct26 trf to SWIGGY Refno 629812345678. " +
            "If not u? call 1800111109. -SBI"
        val second = "Dear UPI user A/C X1234 debited by 120.0 on date 21Oct26 trf to ZOMATO Refno 629812345679. " +
            "If not u? call 1800111109. -SBI"

        assertTrue(ingestor.ingest(sbi, first, received) is IngestOutcome.AwaitingConfirmation)
        repo.confirmReview(dao.reviews.values.single().id)
        assertEquals(1, dao.txns.size)

        val next = ingestor.ingest(sbi, second, received)

        // It used to come back to the tray every time - a generic shape is never trusted,
        // and nothing else was learned from the confirmation.
        assertTrue("expected the learned sentence to book it, got $next", next is IngestOutcome.Recorded)
        val txn = dao.txns[(next as IngestOutcome.Recorded).txnId]!!
        assertEquals(120_00, txn.amountPaise)
        assertEquals("ZOMATO", txn.payee)
        assertTrue(dao.learned.values.single().id.startsWith(TemplateLearner.ID_PREFIX))
    }

    @Test
    fun `a debit card's digits filed to its savings account route there from then on`() = runTest {
        val savings = account("ICICI Savings", "ICICI", "742")
        val sender = "AX-ICICIT-S"
        val first = "Dear Customer, your ICICI Bank Debit Card XX5678 has been used for INR 250.00 at DMART on 21-Oct-26. " +
            "Avl Bal: INR 9,750.00"
        val second = "Dear Customer, your ICICI Bank Debit Card XX5678 has been used for INR 99.00 at CAFE on 22-Oct-26. " +
            "Avl Bal: INR 9,651.00"

        assertTrue(ingestor.ingest(sender, first, received) is IngestOutcome.Queued)
        repo.recordFromReview(dao.reviews.values.single().id, savings.id, 250_00, Direction.DEBIT, "DMART", null)

        assertEquals(savings.id, dao.aliasFor("ICICI", "5678")?.accountId)

        val next = ingestor.ingest(sender, second, received)
        assertEquals(savings.id, (next as IngestOutcome.Recorded).accountId)
        assertEquals(Direction.DEBIT, dao.txns[next.txnId]!!.direction)
        assertEquals(1, dao.accounts.size)
    }

    @Test
    fun `filing a message against another bank's account links nothing`() = runTest {
        val icici = account("ICICI Savings", "ICICI", "742")
        ingestor.ingest(apex, apexDebit("3,450.00", "APEX WHOLESALE", "APX98765", "45,000.00"), received)

        repo.recordFromReview(dao.reviews.values.single().id, icici.id, 3_450_00, Direction.DEBIT, "APEX WHOLESALE", null)

        // One wrong tap must not send every future Apex message to ICICI.
        assertTrue(dao.aliases.isEmpty())
        assertEquals("ICICI", dao.accounts[icici.id]!!.bank)
        assertEquals("742", dao.accounts[icici.id]!!.accountToken)
    }

    @Test
    fun `a corrected payee teaches the rule under both names`() = runTest {
        val savings = declared("Co-op Savings", 0)
        val food = dao.insertCategoryReturningId(com.yk.finance.data.Category(name = "Food"))
        ingestor.ingest(apex, apexDebit("3,450.00", "APEX WHOLESALE", "APX98765", "45,000.00"), received)

        repo.recordFromReview(dao.reviews.values.single().id, savings.id, 3_450_00, Direction.DEBIT, "Apex Wholesale Mart", food)

        assertEquals(food, dao.ruleFor("APEX WHOLESALE MART")?.categoryId)
        // The name the bank prints, which is what the next message will be looked up by.
        assertEquals(food, dao.ruleFor("APEX WHOLESALE")?.categoryId)
    }

    @Test
    fun `an update that teaches the parser a shape files what was waiting for it`() = runTest {
        // Queued as if by an older build that could not read it.
        dao.insertReview(
            com.yk.finance.data.PendingReview(
                sender = "AX-ICICIT-S",
                rawMessage = "ICICI Bank Acct XX742 debited for ₹199.00 on 01-Oct-2026; NETFLIX credited. UPI:627412345678.",
                receivedAt = received,
                reason = "no rule matched for ICICI",
            ),
        )

        assertEquals(1, ingestor.fileQueued())
        assertTrue(dao.reviews.isEmpty())
        assertEquals("NETFLIX", dao.txns.values.single().payee)
    }

    @Test
    fun `filing the queue leaves what still needs a person exactly where it was`() = runTest {
        ingestor.ingest(apex, apexDebit("3,450.00", "APEX WHOLESALE", "APX98765", "45,000.00"), received)
        ingestor.ingest("VM-SBIUPI", "Dear UPI user A/C X1234 debited by 50.0 on date 20Oct26 trf to SWIGGY Refno 629812345678.", received)
        assertEquals(2, dao.reviews.size)

        assertEquals(0, ingestor.fileQueued())

        assertEquals(2, dao.reviews.size)
        assertTrue(dao.txns.isEmpty())
    }

    @Test
    fun `an old relaxed template no longer books a credit as a debit`() = runTest {
        // Shape written by the old fallback: three words, then anything, fixed direction.
        dao.insertLearnedPattern(
            com.yk.finance.data.LearnedPattern(
                id = "LEARNED.UNION.1",
                bank = "UNION",
                regex = """(?i)Union\s+Bank.*?(?<amount>\d+(?:,\d+)*(?:\.\d{1,2})?).*?[Xx*.-]*(?<acct>\d{3,6})""",
                direction = Direction.DEBIT,
                accountToken = "2209",
            ),
        )

        val outcome = ingestor.ingest(
            "JM-UNIONB-T",
            "Union Bank of India A/c *2209 Credited Rs:2500.00 on 22-10-2026 11:15:00 by Mob Bk ref no 778899112233, " +
                "Fvg: RAMESH ENTERPRISES Avl Bal Rs:7828.67. Not you?Call 18002333",
            received,
        )

        val txn = dao.txns[(outcome as IngestOutcome.Recorded).txnId]!!
        assertEquals(Direction.CREDIT, txn.direction)
        assertEquals(2_500_00, txn.amountPaise)
    }

    @Test
    fun `a template that cannot read back its own message is not learned`() {
        val learned = TemplateLearner.learn(
            rawMessage = "Your A/c XX1234 was debited. Thank you.",
            amountPaise = 500_00,
            direction = Direction.DEBIT,
            account = Account(displayName = "x", bank = "UNION", accountToken = "1234", kind = AccountKind.BANK),
            payee = null,
        )
        assertNull(learned)
    }
}
