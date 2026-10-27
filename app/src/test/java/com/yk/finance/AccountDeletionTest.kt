package com.yk.finance

import com.yk.finance.data.Account
import com.yk.finance.data.AccountAlias
import com.yk.finance.data.AccountKind
import com.yk.finance.data.Txn
import com.yk.finance.data.TxnSource
import com.yk.finance.domain.AccountRemoval
import com.yk.finance.domain.IngestOutcome
import com.yk.finance.domain.SmsIngestor
import com.yk.finance.parser.Direction
import com.yk.finance.parser.RuleBasedParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deleting accounts - which the app could not do at all - and merging them, which it did
 * only through the inbox's bind and got two things wrong in.
 */
class AccountDeletionTest {

    private val dao = InMemoryDao()
    private val repo = dao.repository()
    private val ingestor = SmsIngestor(dao, RuleBasedParser())
    private val t = 1_792_000_000_000L

    private val unionDebit2209 =
        "Union Bank of India A/c *2209 Debited Rs:1755.00 on 22-10-2026 10:40:05 by Mob Bk ref no 629576250805, " +
            "Fvg: AONE TRA Avl Bal Rs:5328.67. Not you?Call 18002333/SMS BLOCK 2209 to 8879365472"

    private val unionDebit2209Again =
        "Union Bank of India A/c *2209 Debited Rs:40.00 on 23-10-2026 09:00:00 by Mob Bk ref no 629576250806, " +
            "Fvg: TEA STALL Avl Bal Rs:5288.67. Not you?Call 18002333"

    private suspend fun account(
        name: String,
        bank: String,
        token: String,
        balance: Long = 0,
        kind: AccountKind = AccountKind.BANK,
    ): Account {
        val row = Account(
            displayName = name, bank = bank, accountToken = token, kind = kind,
            openingBalancePaise = balance, currentBalancePaise = balance,
        )
        return row.copy(id = dao.insertAccount(row))
    }

    private fun live(account: Account) = dao.accounts[account.id]

    // ---------- deleting with history ----------

    @Test
    fun `deleting removes the account and its rows, and leaves the other leg of a transfer alone`() = runTest {
        val union = account("Union", "UNION", "2209", 10_000_00)
        val icici = account("ICICI", "ICICI", "742", 5_000_00)
        repo.addTransfer(union.id, icici.id, 1_000_00, null, t)
        repo.addSpend(union.id, 200_00, "SHOP", null, null, t)

        repo.deleteAccount(live(union)!!, moveToId = null, stopTracking = false)

        assertNull(live(union))
        assertTrue(dao.txns.values.none { it.accountId == union.id })
        val leg = dao.txns.values.single { it.accountId == icici.id }
        // Still not income: the money came from an account of yours. Just no longer
        // linked to a row that is gone.
        assertNull(leg.transferGroupId)
        assertEquals(TxnSource.TRANSFER_LEG, leg.source)
        assertFalse(leg.countsAsSpending)
        assertEquals(6_000_00, live(icici)!!.currentBalancePaise)
    }

    @Test
    fun `a repayment of a debt on the deleted account goes back to being an ordinary credit`() = runTest {
        val union = account("Union", "UNION", "2209", 1_000_00)
        val cash = account("Cash", "CASH", "CASH", 0, AccountKind.CASH)
        dao.insertTxn(
            Txn(
                accountId = union.id, direction = Direction.DEBIT, amountPaise = 120_00, occurredAt = t,
                payee = "LUNCH", reference = null, countsAsSpending = false, splitGroupId = "g1", owedBy = "Arun",
            ),
        )
        val repaid = dao.insertTxn(
            Txn(
                accountId = cash.id, direction = Direction.CREDIT, amountPaise = 120_00, occurredAt = t,
                payee = "Arun", reference = null, countsAsSpending = false, splitGroupId = "g1",
                source = TxnSource.SETTLEMENT,
            ),
        )

        repo.deleteAccount(live(union)!!, moveToId = null, stopTracking = false)

        val credit = dao.txns[repaid]!!
        assertNull(credit.splitGroupId)
        assertEquals(TxnSource.MANUAL, credit.source)
    }

    @Test
    fun `without switching messages off, the next one brings the account back`() = runTest {
        ingestor.ingest("JM-UNIONB-T", unionDebit2209, t)
        val auto = dao.accounts.values.single()
        repo.deleteAccount(auto, moveToId = null, stopTracking = false)
        assertTrue(dao.accounts.isEmpty())

        val outcome = ingestor.ingest("JM-UNIONB-T", unionDebit2209Again, t)

        assertTrue(outcome is IngestOutcome.Recorded)
        assertTrue(dao.accounts.values.single().needsConfirmation)
    }

    @Test
    fun `switching messages off keeps a deleted account deleted`() = runTest {
        ingestor.ingest("JM-UNIONB-T", unionDebit2209, t)
        val auto = dao.accounts.values.single()
        repo.deleteAccount(auto, moveToId = null, stopTracking = true)

        val outcome = ingestor.ingest("JM-UNIONB-T", unionDebit2209Again, t)

        assertTrue(outcome is IngestOutcome.Ignored)
        assertTrue(dao.accounts.isEmpty())
        assertTrue(dao.txns.isEmpty())

        repo.unignoreAccount("UNION", "2209")
        assertTrue(ingestor.ingest("JM-UNIONB-T", unionDebit2209Again, t) is IngestOutcome.Recorded)
    }

    @Test
    fun `switching messages off covers every set of digits the account answered to`() = runTest {
        val icici = account("ICICI", "ICICI", "742")
        dao.upsertAlias(AccountAlias("ICICI", "5678", icici.id))

        repo.deleteAccount(icici, moveToId = null, stopTracking = true)

        assertTrue(dao.isAccountIgnored("ICICI", "742"))
        assertTrue(dao.isAccountIgnored("ICICI", "5678"))
        assertTrue(dao.aliases.isEmpty())
    }

    @Test
    fun `cash and hand-added accounts have no messages to switch off`() = runTest {
        val cash = account("Cash", "CASH", "CASH", 0, AccountKind.CASH)
        repo.deleteAccount(cash, moveToId = null, stopTracking = true)
        assertTrue(dao.ignored.isEmpty())
    }

    // ---------- moving the history to another account ----------

    @Test
    fun `moving into another account carries the rows, the balance and the messages`() = runTest {
        val old = account("Old Union", "UNION", "2209", 1_000_00)
        val main = account("Main Union", "UNION", "8317", 5_000_00)
        repo.addSpend(old.id, 200_00, "SHOP", null, null, t)
        repo.addTransfer(old.id, main.id, 300_00, null, t) // main: 5,300

        repo.deleteAccount(live(old)!!, moveToId = main.id, stopTracking = false)

        assertNull(live(old))
        // The spend moved; the transfer between the two would now be from an account to
        // itself, so both legs went and main's +300 came back out.
        val rows = dao.txns.values.filter { it.accountId == main.id }
        assertEquals(listOf("SHOP"), rows.map { it.payee })
        assertTrue(dao.txns.values.none { it.transferGroupId != null })
        assertEquals(5_000_00 - 200_00, live(main)!!.currentBalancePaise)
        // Main keeps its own digits and now answers to the old ones too.
        assertEquals("8317", live(main)!!.accountToken)
        assertEquals(main.id, dao.aliasFor("UNION", "2209")?.accountId)

        val outcome = ingestor.ingest("JM-UNIONB-T", unionDebit2209Again, t)
        assertEquals(main.id, (outcome as IngestOutcome.Recorded).accountId)
        assertEquals(1, dao.accounts.size)
    }

    @Test
    fun `binding an auto-created account takes the bank's balance, not the sum of two`() = runTest {
        val savings = account("Savings", "UNBOUND", "PENDING-1", 10_000_00)
        ingestor.ingest("JM-UNIONB-T", unionDebit2209, t)
        val auto = dao.accounts.values.single { it.needsConfirmation }
        assertEquals(5_328_67, auto.currentBalancePaise)

        repo.bindAutoAccountTo(auto, live(savings)!!)

        val bound = live(savings)!!
        assertEquals("UNION", bound.bank)
        assertEquals("2209", bound.accountToken)
        // The bank printed this account's balance. It used to come out as 10,000 + 5,328.67.
        assertEquals(5_328_67, bound.currentBalancePaise)
        assertTrue(bound.providesBalance)
        assertNull(live(auto))
        assertEquals(savings.id, dao.txns.values.single().accountId)

        val outcome = ingestor.ingest("JM-UNIONB-T", unionDebit2209Again, t)
        assertEquals(savings.id, (outcome as IngestOutcome.Recorded).accountId)
    }

    @Test
    fun `binding an account with no bank balance adds what it saw onto the opening balance`() = runTest {
        val savings = account("Savings", "UNBOUND", "PENDING-1", 10_000_00)
        ingestor.ingest(
            "VM-ICICIB",
            "ICICI Bank Acct XX742 debited for Rs 169.00 on 11-Sep-26; SRI LAKSHMI TRA credited. UPI:412233445566.",
            t,
        )
        val auto = dao.accounts.values.single { it.needsConfirmation }

        repo.bindAutoAccountTo(auto, live(savings)!!)

        assertEquals(10_000_00 - 169_00, live(savings)!!.currentBalancePaise)
        assertEquals("742", live(savings)!!.accountToken)
    }

    @Test
    fun `binding into an account that already has digits keeps them`() = runTest {
        val main = account("Main Union", "UNION", "8317", 5_000_00)
        ingestor.ingest("JM-UNIONB-T", unionDebit2209, t)
        val auto = dao.accounts.values.single { it.needsConfirmation }

        repo.bindAutoAccountTo(auto, live(main)!!)

        // It used to be overwritten with 2209, so 8317's own messages created a new account.
        assertEquals("8317", live(main)!!.accountToken)
        assertEquals(main.id, dao.aliasFor("UNION", "2209")?.accountId)
    }

    @Test
    fun `a card cannot be moved into a bank account, and nothing is lost trying`() = runTest {
        val card = account("Card", "HDFC", "4321", 0, AccountKind.CREDIT_CARD)
        val bank = account("Bank", "UNION", "2209", 1_000_00)
        repo.addSpend(card.id, 500_00, "AMAZON", null, null, t)

        repo.deleteAccount(live(card)!!, moveToId = bank.id, stopTracking = false)

        assertNotNull(live(card))
        assertEquals(card.id, dao.txns.values.single().accountId)
        assertEquals(1_000_00, live(bank)!!.currentBalancePaise)
    }

    @Test
    fun `a merged account keeps answering to the names an import knew the old one by`() = runTest {
        val old = account("Salary", "UNION", "2209").also {
            dao.updateAccount(it.copy(aliases = "Union SB"))
        }
        val main = account("Main", "UNION", "8317")

        repo.deleteAccount(live(old)!!, moveToId = main.id, stopTracking = false)

        assertEquals(listOf("Union SB", "Salary"), live(main)!!.aliases!!.split("\n"))
    }

    @Test
    fun `a bank account is never folded into the cash wallet`() = runTest {
        val union = account("Union", "UNION", "2209", 1_000_00)
        val cash = account("Cash", "CASH", "CASH", 500_00, AccountKind.CASH)
        repo.addSpend(union.id, 100_00, "SHOP", null, null, t)

        repo.deleteAccount(live(union)!!, moveToId = cash.id, stopTracking = false)

        assertNotNull(live(union))
        assertEquals(500_00, live(cash)!!.currentBalancePaise)
        assertTrue(dao.aliases.isEmpty())
    }

    @Test
    fun `moving into an account that no longer exists deletes nothing`() = runTest {
        val union = account("Union", "UNION", "2209", 1_000_00)
        repo.addSpend(union.id, 100_00, "SHOP", null, null, t)

        repo.deleteAccount(live(union)!!, moveToId = 999L, stopTracking = false)

        assertNotNull(live(union))
        assertEquals(1, dao.txns.size)
    }

    // ---------- the rules, on their own ----------

    @Test
    fun `which accounts can take which, and which carry messages`() {
        val bank = Account(id = 1, displayName = "a", bank = "UNION", accountToken = "1")
        val other = Account(id = 2, displayName = "b", bank = "ICICI", accountToken = "2")
        val card = Account(id = 3, displayName = "c", bank = "HDFC", accountToken = "3", kind = AccountKind.CREDIT_CARD)
        val declared = Account(id = 4, displayName = "d", bank = "UNBOUND", accountToken = "PENDING-1")
        val cash = Account(id = 5, displayName = "e", bank = "CASH", accountToken = "CASH", kind = AccountKind.CASH)

        assertTrue(AccountRemoval.canMoveInto(bank, other))
        assertFalse(AccountRemoval.canMoveInto(bank, bank))
        assertFalse(AccountRemoval.canMoveInto(bank, card))
        assertFalse(AccountRemoval.canMoveInto(card, bank))

        assertTrue(AccountRemoval.adoptsIdentity(bank, declared))
        assertFalse(AccountRemoval.adoptsIdentity(bank, other))
        assertFalse(AccountRemoval.adoptsIdentity(cash, declared))

        assertTrue(AccountRemoval.isMessageLinked(bank))
        assertFalse(AccountRemoval.isMessageLinked(declared))
        assertFalse(AccountRemoval.isMessageLinked(cash))
    }
}
