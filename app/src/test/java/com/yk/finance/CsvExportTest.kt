package com.yk.finance

import com.yk.finance.backup.CsvExport
import com.yk.finance.data.Account
import com.yk.finance.data.AccountKind
import com.yk.finance.data.AppDatabase
import com.yk.finance.data.Category
import com.yk.finance.data.MIGRATION_1_2
import com.yk.finance.data.MIGRATION_2_3
import com.yk.finance.data.MIGRATION_3_4
import com.yk.finance.data.MIGRATION_4_5
import com.yk.finance.data.MIGRATION_5_6
import com.yk.finance.data.Txn
import com.yk.finance.data.TxnSource
import com.yk.finance.parser.Channel
import com.yk.finance.parser.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvExportTest {

    private val icici = Account(
        id = 1, displayName = "Salary", bank = "ICICI", accountToken = "742",
        kind = AccountKind.BANK,
    )
    private val cash = Account(
        id = 2, displayName = "Cash", bank = "CASH", accountToken = "CASH",
        kind = AccountKind.CASH,
    )
    private val food = Category(id = 7, name = "Food")

    private fun txn(
        amountPaise: Long = 5000,
        direction: Direction = Direction.DEBIT,
        occurredAt: Long = 1_758_000_000_000,
        note: String? = null,
        source: TxnSource = TxnSource.SMS,
        transferGroupId: String? = null,
        countsAsSpending: Boolean = true,
        accountId: Long = 1,
    ) = Txn(
        id = 0, accountId = accountId, direction = direction, amountPaise = amountPaise,
        occurredAt = occurredAt, payee = "SRI LAKSHMI TRA", reference = "634455667788",
        channel = Channel.UPI, categoryId = 7, countsAsSpending = countsAsSpending,
        transferGroupId = transferGroupId, source = source, note = note,
    )

    // ----- money -----

    @Test
    fun `paise render with exactly two decimal places`() {
        assertEquals("50.00", CsvExport.formatAmount(5000))
        assertEquals("0.05", CsvExport.formatAmount(5))
        assertEquals("0.00", CsvExport.formatAmount(0))
        assertEquals("68.25", CsvExport.formatAmount(6825))
        assertEquals("21050.00", CsvExport.formatAmount(2_105_000))
    }

    @Test
    fun `no thousands separator, because the importer has to read this back`() {
        assertTrue("," !in CsvExport.formatAmount(2_105_000))
    }

    // ----- quoting -----

    @Test
    fun `a note containing a comma stays one field`() {
        val escaped = CsvExport.escape("Snacks, tea")
        assertEquals("\"Snacks, tea\"", escaped)
    }

    @Test
    fun `embedded quotes are doubled`() {
        assertEquals("\"He said \"\"hi\"\"\"", CsvExport.escape("He said \"hi\""))
    }

    @Test
    fun `plain text is left alone`() {
        assertEquals("Fruits", CsvExport.escape("Fruits"))
    }

    @Test
    fun `a newline inside a note is quoted rather than breaking the row`() {
        assertEquals("\"two\nlines\"", CsvExport.escape("two\nlines"))
    }

    // ----- type vocabulary -----

    @Test
    fun `a transfer leg is never reported as an expense`() {
        val leg = txn(transferGroupId = "g1", countsAsSpending = false, source = TxnSource.TRANSFER_LEG)
        assertEquals("Transfer", CsvExport.typeOf(leg))
    }

    @Test
    fun `a reconcile adjustment is reported as a correction`() {
        assertEquals("Correction", CsvExport.typeOf(txn(source = TxnSource.ADJUSTMENT)))
    }

    @Test
    fun `direction decides expense versus income for ordinary rows`() {
        assertEquals("Expense", CsvExport.typeOf(txn(direction = Direction.DEBIT)))
        assertEquals("Income", CsvExport.typeOf(txn(direction = Direction.CREDIT)))
    }

    // ----- whole file -----

    @Test
    fun `every row has exactly as many fields as the header`() {
        val out = StringBuilder()
        CsvExport.write(
            transactions = listOf(
                txn(note = "Fruits"),
                txn(note = "Snacks, tea", amountPaise = 12000),
                txn(accountId = 2, note = null, direction = Direction.CREDIT),
            ),
            accounts = listOf(icici, cash),
            categories = listOf(food),
            out = out,
        )
        val lines = out.toString().trim().lines()
        assertEquals(4, lines.size)
        lines.forEach { line ->
            assertEquals("field count in: $line", CsvExport.HEADER.size, countFields(line))
        }
    }

    @Test
    fun `rows come out oldest first`() {
        val out = StringBuilder()
        CsvExport.write(
            transactions = listOf(
                txn(occurredAt = 2_000_000_000_000, note = "later"),
                txn(occurredAt = 1_000_000_000_000, note = "earlier"),
            ),
            accounts = listOf(icici),
            categories = listOf(food),
            out = out,
        )
        val body = out.toString().trim().lines().drop(1)
        assertTrue(body[0].contains("earlier"))
        assertTrue(body[1].contains("later"))
    }

    @Test
    fun `account and category are written by name, not by id`() {
        val out = StringBuilder()
        CsvExport.write(listOf(txn()), listOf(icici), listOf(food), out)
        val row = out.toString().trim().lines()[1]
        assertTrue(row.contains("Food"))
        assertTrue(row.contains("Salary"))
    }

    /** Minimal RFC 4180 field counter, so the test does not trust the writer's own logic. */
    private fun countFields(line: String): Int {
        var fields = 1
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> i++
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> fields++
            }
            i++
        }
        return fields
    }
}

class MigrationVersionTest {

    @Test
    fun `the migration chain is contiguous and ends where the database declares`() {
        // A gap here is not a test failure in the abstract: Room refuses to open a
        // database it cannot walk to the current version, and the ledger is unreachable
        // until the app is fixed and reinstalled.
        val chain = listOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
        )
            .sortedBy { it.startVersion }

        assertEquals(1, chain.first().startVersion)
        assertEquals(AppDatabase.SCHEMA_VERSION, chain.last().endVersion)
        chain.zipWithNext { earlier, later ->
            assertEquals(earlier.endVersion, later.startVersion)
        }
    }
}
