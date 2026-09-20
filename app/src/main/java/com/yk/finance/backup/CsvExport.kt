package com.yk.finance.backup

import com.yk.finance.data.Account
import com.yk.finance.data.Category
import com.yk.finance.data.Txn
import com.yk.finance.data.TxnSource
import com.yk.finance.parser.Direction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Readable export of the ledger.
 *
 * Deliberately not a backup - it cannot round-trip balances, budgets or cycle state,
 * and pretending otherwise is how people lose data. [Snapshot] is the backup; this is
 * the file you open on a laptop.
 *
 * The column set is a superset of what My Money Pro emits, in the same vocabulary, so
 * that the importer being built next can be exercised against our own output as well
 * as against a foreign export. No Android types appear here, so it is unit-testable.
 */
object CsvExport {

    private val TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Kolkata"))

    val HEADER = listOf(
        "TIME", "TYPE", "AMOUNT", "CATEGORY", "ACCOUNT", "NOTES",
        "PAYEE", "REFERENCE", "CHANNEL", "SOURCE", "COUNTS_AS_SPENDING",
        "TRANSFER_GROUP", "ACCOUNT_BANK", "ACCOUNT_TOKEN",
        // A split exports as its parts, which already sum to the bill. The group is
        // what lets a reader see that 200 Food and 120 For Others were one payment
        // rather than two, and OWED_BY is the only place a person's name survives
        // outside the app.
        "SPLIT_GROUP", "OWED_BY",
    )

    /** "Expense" / "Income" / "Transfer" / "Correction". */
    fun typeOf(txn: Txn): String = when {
        txn.source == TxnSource.ADJUSTMENT -> "Correction"
        txn.transferGroupId != null -> "Transfer"
        txn.direction == Direction.DEBIT -> "Expense"
        else -> "Income"
    }

    /** Paise to a plain decimal: no symbol, no grouping, always two places. */
    fun formatAmount(paise: Long): String {
        val abs = if (paise < 0) -paise else paise
        val sign = if (paise < 0) "-" else ""
        return sign + (abs / 100) + "." + (abs % 100).toString().padStart(2, '0')
    }

    fun formatTime(millis: Long): String = TIME.format(Instant.ofEpochMilli(millis))

    fun row(txn: Txn, account: Account?, category: Category?): List<String> = listOf(
        formatTime(txn.occurredAt),
        typeOf(txn),
        formatAmount(txn.amountPaise),
        category?.name.orEmpty(),
        account?.displayName.orEmpty(),
        txn.note.orEmpty(),
        txn.payee.orEmpty(),
        txn.reference.orEmpty(),
        txn.channel.name,
        txn.source.name,
        if (txn.countsAsSpending) "yes" else "no",
        txn.transferGroupId.orEmpty(),
        account?.bank.orEmpty(),
        account?.accountToken.orEmpty(),
        txn.splitGroupId.orEmpty(),
        txn.owedBy.orEmpty(),
    )

    fun write(
        transactions: List<Txn>,
        accounts: List<Account>,
        categories: List<Category>,
        out: Appendable,
    ) {
        val accountById = accounts.associateBy { it.id }
        val categoryById = categories.associateBy { it.id }
        writeLine(HEADER, out)
        transactions
            .sortedBy { it.occurredAt }
            .forEach { txn ->
                writeLine(row(txn, accountById[txn.accountId], txn.categoryId?.let(categoryById::get)), out)
            }
    }

    private fun writeLine(fields: List<String>, out: Appendable) {
        out.append(fields.joinToString(",") { escape(it) })
        out.append("\r\n")
    }

    /**
     * RFC 4180 quoting. Quotes whenever the field holds a comma, a quote or a line
     * break, and doubles any embedded quote. Notes are free text - "Snacks, tea" would
     * otherwise silently become two columns.
     */
    fun escape(field: String): String {
        val needsQuotes = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }
}
