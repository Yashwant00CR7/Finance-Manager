package com.yk.finance

import com.yk.finance.importer.Canonical
import com.yk.finance.importer.ColumnMap
import com.yk.finance.importer.Csv
import com.yk.finance.importer.CsvImporter
import com.yk.finance.importer.ImportOutcome
import com.yk.finance.importer.ImportProfiles
import com.yk.finance.importer.Parsed
import com.yk.finance.importer.RowKind
import com.yk.finance.importer.ValueParsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDateTime

private fun <T> ok(parsed: Parsed<T>): T = when (parsed) {
    is Parsed.Ok -> parsed.value
    is Parsed.Fail -> throw AssertionError("expected success, got: ${parsed.reason}")
}

private fun failReason(parsed: Parsed<*>): String = when (parsed) {
    is Parsed.Fail -> parsed.reason
    is Parsed.Ok -> throw AssertionError("expected failure, got: ${parsed.value}")
}

class MoneyParserTest {

    @Test
    fun `plain amounts become paise`() {
        assertEquals(42500L, ok(ValueParsers.money("425.00")))
        assertEquals(5L, ok(ValueParsers.money("0.05")))
        assertEquals(6825L, ok(ValueParsers.money("68.25")))
        assertEquals(123400L, ok(ValueParsers.money("1234")))
    }

    @Test
    fun `currency symbols and spaces are noise`() {
        assertEquals(50000L, ok(ValueParsers.money("₹ 500.00")))
        assertEquals(50000L, ok(ValueParsers.money("Rs. 500")))
        assertEquals(50000L, ok(ValueParsers.money("INR 500.00")))
        assertEquals(50000L, ok(ValueParsers.money(" 500 ")))
    }

    @Test
    fun `indian grouping survives`() {
        assertEquals(12345678L, ok(ValueParsers.money("1,23,456.78")))
        assertEquals(210500000L, ok(ValueParsers.money("21,05,000")))
    }

    @Test
    fun `european decimals are read as decimals, not grouping`() {
        assertEquals(123456L, ok(ValueParsers.money("1.234,56")))
        assertEquals(1234L, ok(ValueParsers.money("12,34")))
    }

    @Test
    fun `both ways of writing a negative`() {
        assertEquals(-50000L, ok(ValueParsers.money("-500.00")))
        assertEquals(-50000L, ok(ValueParsers.money("(500.00)")))
        assertEquals(-50000L, ok(ValueParsers.money("(₹500)")))
    }

    @Test
    fun `sub-paise precision is refused rather than rounded`() {
        // Invariant I4. Rounding here would be invisible and permanent.
        assertTrue(failReason(ValueParsers.money("10.005")).contains("finer than paise"))
    }

    @Test
    fun `nonsense is refused with a reason naming the value`() {
        assertTrue(failReason(ValueParsers.money("abc")).contains("abc"))
        assertTrue(failReason(ValueParsers.money("")).isNotEmpty())
        assertTrue(failReason(ValueParsers.money("1.2.3")).isNotEmpty())
    }

    @Test
    fun `every parsed amount round-trips exactly`() {
        listOf("0.01", "9.99", "425.00", "1,23,456.78", "68.25", "1000").forEach { text ->
            val paise = ok(ValueParsers.money(text))
            val back = (paise / 100).toString() + "." + (paise % 100).toString().padStart(2, '0')
            assertEquals(
                text.replace(",", "").replace("Rs.", "").trim().toBigDecimal().stripTrailingZeros(),
                back.toBigDecimal().stripTrailingZeros(),
            )
        }
    }
}

class TimeParserTest {

    private fun ist(text: String) = ok(ValueParsers.time(text))

    @Test
    fun `the my money pro format parses in IST`() {
        val parsed = ist("Jul 05, 2026 11:49 AM")
        val expected = LocalDateTime.of(2026, 7, 5, 11, 49)
            .atZone(ValueParsers.IST).toInstant().toEpochMilli()
        assertEquals(expected, parsed.epochMillis)
        assertEquals(false, parsed.inferred)
    }

    @Test
    fun `pm is not am`() {
        val am = ist("Jul 05, 2026 11:49 AM").epochMillis
        val pm = ist("Jul 05, 2026 11:49 PM").epochMillis
        assertEquals(12 * 3_600_000L, pm - am)
    }

    @Test
    fun `iso and dashed formats both work`() {
        assertNotNull(ist("2026-09-19 14:30:00"))
        assertNotNull(ist("2026-09-19T14:30"))
        assertNotNull(ist("19-09-2026 14:30"))
    }

    @Test
    fun `a date with no time is placed at midday and says so`() {
        val parsed = ist("19-09-2026")
        assertTrue(parsed.inferred)
        val hour = Instant.ofEpochMilli(parsed.epochMillis).atZone(ValueParsers.IST).hour
        // Midnight would silently move a row into the previous cycle at a boundary.
        assertEquals(12, hour)
    }

    @Test
    fun `ambiguous slashes are read day-first, the Indian convention`() {
        val parsed = ist("03/04/2026")
        val date = Instant.ofEpochMilli(parsed.epochMillis).atZone(ValueParsers.IST).toLocalDate()
        assertEquals(4, date.monthValue)
        assertEquals(3, date.dayOfMonth)
    }

    @Test
    fun `epoch timestamps are recognised by length`() {
        assertEquals(1_758_000_000_000L, ist("1758000000000").epochMillis)
        assertEquals(1_758_000_000_000L, ist("1758000000").epochMillis)
    }

    @Test
    fun `an unreadable date is refused, not guessed`() {
        assertTrue(failReason(ValueParsers.time("sometime last week")).contains("unrecognised"))
    }
}

class DirectionParserTest {

    @Test
    fun `my money pro type prefixes`() {
        assertEquals(RowKind.EXPENSE, ok(ValueParsers.kind("(-) Expense", "425.00")))
        assertEquals(RowKind.INCOME, ok(ValueParsers.kind("(+) Income", "6000.00")))
        assertEquals(RowKind.TRANSFER, ok(ValueParsers.kind("(*) Transfer", "3000.00")))
    }

    @Test
    fun `bank vocabulary`() {
        assertEquals(RowKind.EXPENSE, ok(ValueParsers.kind("Debit", "1")))
        assertEquals(RowKind.INCOME, ok(ValueParsers.kind("Credit", "1")))
        assertEquals(RowKind.EXPENSE, ok(ValueParsers.kind("DR", "1")))
        assertEquals(RowKind.INCOME, ok(ValueParsers.kind("CR", "1")))
        assertEquals(RowKind.EXPENSE, ok(ValueParsers.kind("Withdrawal", "1")))
        assertEquals(RowKind.INCOME, ok(ValueParsers.kind("Deposit", "1")))
    }

    @Test
    fun `split debit and credit columns`() {
        assertEquals(RowKind.EXPENSE, ok(ValueParsers.kind(null, null, debitText = "425.00")))
        assertEquals(RowKind.INCOME, ok(ValueParsers.kind(null, null, creditText = "425.00")))
    }

    @Test
    fun `the sign of the amount is the last resort`() {
        assertEquals(RowKind.EXPENSE, ok(ValueParsers.kind(null, "-425.00")))
        assertEquals(RowKind.INCOME, ok(ValueParsers.kind(null, "425.00")))
    }

    @Test
    fun `transfer routes are split, and a plain name is not a route`() {
        assertEquals("Salary" to "Card", ValueParsers.transferRoute("Salary->Card"))
        assertEquals("Salary" to "Cash", ValueParsers.transferRoute("Salary -> Cash"))
        assertNull(ValueParsers.transferRoute("Salary"))
    }
}

class DialectTest {

    @Test
    fun `a trailing space after the closing quote is not part of the field`() {
        // The exact quirk in the real file. Without this the notes column is named
        // `NOTES" ` and nothing ever maps to it.
        val text = "\"TIME\",\"TYPE\",\"NOTES\" \n\"a\",\"b\",\"c\" \n"
        val rows = Csv.parse(text, Csv.sniff(text))
        assertEquals(listOf("TIME", "TYPE", "NOTES"), rows[0].fields)
        assertEquals(listOf("a", "b", "c"), rows[1].fields)
    }

    @Test
    fun `semicolons and tabs are detected`() {
        val semi = "a;b;c\n1;2;3\n4;5;6\n"
        assertEquals(';', Csv.sniff(semi).delimiter)
        val tab = "a\tb\tc\n1\t2\t3\n4\t5\t6\n"
        assertEquals('\t', Csv.sniff(tab).delimiter)
    }

    @Test
    fun `a comma-heavy notes column does not steal the delimiter`() {
        val text = "date;notes;amount\n1;\"a, b, c, d\";2\n3;\"e, f, g, h\";4\n"
        assertEquals(';', Csv.sniff(text).delimiter)
    }

    @Test
    fun `byte order marks, CRLF and doubled quotes`() {
        val text = "\uFEFFa,b\r\n\"he said \"\"hi\"\"\",2\r\n"
        val dialect = Csv.sniff(text)
        assertTrue(dialect.hadBom)
        val rows = Csv.parse(text, dialect)
        assertEquals("he said \"hi\"", rows[1].fields[0])
    }

    @Test
    fun `a newline inside a quoted field does not split the row`() {
        val text = "a,b\n\"two\nlines\",2\n"
        val rows = Csv.parse(text, Csv.sniff(text))
        assertEquals(2, rows.size)
        assertEquals("two\nlines", rows[1].fields[0])
    }

    @Test
    fun `a preamble before the real header is skipped`() {
        val text = "Account statement\nGenerated 19 Sep 2026\ndate,amount,notes\n1,2,3\n"
        val rows = Csv.parse(text, Csv.sniff(text))
        assertEquals(2, Csv.headerIndex(rows))
    }

    @Test
    fun `blank lines are not rows`() {
        val text = "a,b\n1,2\n\n\n3,4\n"
        assertEquals(3, Csv.parse(text, Csv.sniff(text)).size)
    }
}

class ColumnMapTest {

    @Test
    fun `header names are matched past case, spacing and punctuation`() {
        val mapping = ColumnMap.map(listOf("TIME", "Type", "AMOUNT", "Category", "ACCOUNT", "NOTES "))
        assertEquals(0, mapping.byField[Canonical.TIME])
        assertEquals(5, mapping.byField[Canonical.NOTES])
        assertNull(mapping.missingRequired())
    }

    @Test
    fun `synonyms across different exports`() {
        val mapping = ColumnMap.map(listOf("Txn Date", "Narration", "Withdrawal", "Deposit", "Closing Balance"))
        assertEquals(Canonical.TIME, mapping.byField.entries.first { it.value == 0 }.key)
        assertEquals(Canonical.NOTES, mapping.byField.entries.first { it.value == 1 }.key)
        assertEquals(Canonical.DEBIT, mapping.byField.entries.first { it.value == 2 }.key)
        assertEquals(Canonical.CREDIT, mapping.byField.entries.first { it.value == 3 }.key)
    }

    @Test
    fun `a debit-credit pair satisfies the amount requirement`() {
        val mapping = ColumnMap.map(listOf("Date", "Withdrawal", "Deposit"))
        assertNull(mapping.missingRequired())
    }

    @Test
    fun `a file with no date is refused`() {
        assertNotNull(ColumnMap.map(listOf("Amount", "Notes")).missingRequired())
    }

    @Test
    fun `a file with no amount at all is refused`() {
        assertNotNull(ColumnMap.map(listOf("Date", "Notes")).missingRequired())
    }

    @Test
    fun `columns nobody recognises are kept, not dropped`() {
        val mapping = ColumnMap.map(listOf("Date", "Amount", "Mood", "Weather"))
        assertEquals(listOf("Mood", "Weather"), mapping.unmapped)
    }
}

class ImportPipelineTest {

    private val header = "\"TIME\",\"TYPE\",\"AMOUNT\",\"CATEGORY\",\"ACCOUNT\",\"NOTES\" \n"

    private fun plan(vararg rows: String) =
        when (val outcome = CsvImporter.read("test.csv", header + rows.joinToString(""))) {
            is ImportOutcome.Ready -> outcome.plan
            is ImportOutcome.Refused -> throw AssertionError("refused: ${outcome.reason}")
        }

    @Test
    fun `the my money pro profile is recognised from its header`() {
        val p = plan("\"Jul 05, 2026 11:49 AM\",\"(-) Expense\",\"425.00\",\"Food\",\"Card\",\"\" \n")
        assertEquals(ImportProfiles.MY_MONEY_PRO.name, p.profileName)
        assertEquals(1, p.staged.size)
        assertEquals(RowKind.EXPENSE, p.staged[0].kind)
        assertEquals(42500L, p.staged[0].amountPaise)
        assertEquals("Card", p.staged[0].accountName)
        assertEquals("Food", p.staged[0].categoryName)
    }

    @Test
    fun `a transfer keeps both ends and takes no category`() {
        val p = plan("\"Aug 31, 2026 10:22 AM\",\"(*) Transfer\",\"5000.00\",\"  -  \",\"Salary->Card\",\" \" \n")
        val row = p.staged.single()
        assertEquals(RowKind.TRANSFER, row.kind)
        assertEquals("Salary", row.accountName)
        assertEquals("Card", row.toAccountName)
        // The placeholder category on a transfer is not a category.
        assertNull(row.categoryName)
    }

    @Test
    fun `the sort-hack dot is stripped from a category name`() {
        val p = plan("\"Jul 05, 2026 11:49 AM\",\"(-) Expense\",\"425.00\",\".Clothing\",\"Card\",\"\" \n")
        assertEquals("Clothing", p.staged.single().categoryName)
    }

    @Test
    fun `a negative expense stays an expense of a positive amount`() {
        // Otherwise the sign is applied twice and the row nets against itself.
        val p = plan("\"Jul 05, 2026 11:49 AM\",\"(-) Expense\",\"-425.00\",\"Food\",\"Card\",\"\" \n")
        assertEquals(RowKind.EXPENSE, p.staged.single().kind)
        assertEquals(42500L, p.staged.single().amountPaise)
    }

    @Test
    fun `a bad row is rejected with its line number, and conservation still holds`() {
        val p = plan(
            "\"Jul 05, 2026 11:49 AM\",\"(-) Expense\",\"425.00\",\"Food\",\"Card\",\"\" \n",
            "\"whenever\",\"(-) Expense\",\"425.00\",\"Food\",\"Card\",\"\" \n",
            "\"Jul 06, 2026 11:49 AM\",\"(-) Expense\",\"nonsense\",\"Food\",\"Card\",\"\" \n",
        )
        assertEquals(3, p.dataRows)
        assertEquals(1, p.staged.size)
        assertEquals(2, p.rejected.size)
        assertTrue(p.conserved)
        assertEquals(3, p.rejected[0].lineNumber)
        assertTrue(p.rejected.all { it.reason.isNotBlank() })
    }

    @Test
    fun `unmapped columns survive into the retained json`() {
        val text = "date,amount,mood\n\"2026-09-19 10:00\",100.00,cheerful\n"
        val outcome = CsvImporter.read("x.csv", text) as ImportOutcome.Ready
        assertTrue(outcome.plan.staged.single().rawJson.contains("cheerful"))
        assertTrue(outcome.plan.staged.single().rawLine.contains("cheerful"))
    }

    @Test
    fun `a file with no usable columns is refused whole`() {
        val outcome = CsvImporter.read("x.csv", "colour,mood\nred,cheerful\n")
        assertTrue(outcome is ImportOutcome.Refused)
    }

    @Test
    fun `an empty file is refused`() {
        assertTrue(CsvImporter.read("x.csv", "   ") is ImportOutcome.Refused)
    }
}

/**
 * The real export, used as a fixture rather than as a payload.
 *
 * It is the only test that proves the pipeline survives a file nobody designed for it,
 * so a missing fixture fails rather than skips. A test that passes by not running is
 * worse than no test: it reports success for work it never did.
 */
class RealExportTest {

    private val file: File =
        listOf(File("../export_19_09_26_1014.csv"), File("export_19_09_26_1014.csv"))
            .firstOrNull { it.exists() }
            ?: throw AssertionError(
                "fixture export_19_09_26_1014.csv not found from " + File(".").absolutePath,
            )

    private fun plan() =
        (CsvImporter.read(file.name, file.readText()) as ImportOutcome.Ready).plan

    @Test
    fun `every one of the 226 rows is accounted for`() {
        val p = plan()
        assertEquals(226, p.dataRows)
        assertTrue("conservation: ${p.staged.size} + ${p.rejected.size} != ${p.dataRows}", p.conserved)
    }

    @Test
    fun `nothing in the real file is rejected`() {
        val p = plan()
        assertEquals(
            "unexpected rejects: " + p.rejected.joinToString { "line ${it.lineNumber}: ${it.reason}" },
            0,
            p.rejected.size,
        )
    }

    @Test
    fun `the type breakdown matches the file`() {
        val p = plan()
        assertEquals(206, p.countOf(RowKind.EXPENSE))
        assertEquals(16, p.countOf(RowKind.INCOME))
        assertEquals(4, p.countOf(RowKind.TRANSFER))
    }

    @Test
    fun `the three account names are found and the transfer routes are split`() {
        val p = plan()
        assertEquals(listOf("Card", "Cash", "Salary"), p.accountNames)
    }

    @Test
    fun `no category placeholder leaks through as a category`() {
        val p = plan()
        assertTrue("-" !in p.categoryNames)
        assertTrue(".Clothing" !in p.categoryNames)
        assertTrue("Clothing" in p.categoryNames)
    }
}
