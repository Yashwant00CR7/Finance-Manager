package com.yk.finance.importer

/**
 * Everything stages 1-5 worked out about a file, and nothing more.
 *
 * Deliberately inert: no account ids, no database, no writes. This is the object the
 * dry run reports on and the object the committer will later consume, which is what
 * makes the rehearsal and the real thing the same pipeline.
 */
data class ImportPlan(
    val fileName: String,
    val profileName: String,
    val dialect: Dialect,
    val mapping: ColumnMap.Mapping,
    val dataRows: Int,
    val staged: List<StagedTxn>,
    val rejected: List<RejectedRow>,
) {
    /** Invariant I1. A row can never just vanish. */
    val conserved: Boolean get() = staged.size + rejected.size == dataRows

    val accountNames: List<String>
        get() = staged.flatMap { listOfNotNull(it.accountName, it.toAccountName) }.distinct().sorted()

    val categoryNames: List<String>
        get() = staged.mapNotNull { it.categoryName }.distinct().sorted()

    val firstAt: Long? get() = staged.minOfOrNull { it.occurredAt }
    val lastAt: Long? get() = staged.maxOfOrNull { it.occurredAt }

    fun countOf(kind: RowKind) = staged.count { it.kind == kind }

    fun totalPaise(kind: RowKind) = staged.filter { it.kind == kind }.sumOf { it.amountPaise }
}

sealed interface ImportOutcome {
    data class Ready(val plan: ImportPlan) : ImportOutcome

    /** Refused before anything was interpreted, with a reason a person can act on. */
    data class Refused(val reason: String) : ImportOutcome
}

/**
 * Stages 1-5, orchestrated. No Android, no I/O, no Room - so the whole pipeline is
 * unit-testable on the JVM, the same way `parser/` already is.
 */
object CsvImporter {

    /**
     * Stands in when the file has no account column at all, which is normal for a
     * single-account bank statement. Rejecting those rows would throw away a whole
     * file over a column it never needed; naming the gap lets the report show it.
     */
    const val UNSPECIFIED_ACCOUNT = "(no account column)"

    fun read(fileName: String, text: String): ImportOutcome {
        if (text.isBlank()) return ImportOutcome.Refused("that file is empty")

        val dialect = Csv.sniff(text)
        val rows = Csv.parse(text, dialect)
        if (rows.isEmpty()) return ImportOutcome.Refused("no rows could be read from that file")

        val headerIndex = Csv.headerIndex(rows)
        if (headerIndex < 0) return ImportOutcome.Refused("no header row could be found")
        val header = rows[headerIndex]

        val profile = ImportProfiles.forHeaders(header.fields)
        val mapping = ColumnMap.map(header.fields, profile.columnOverrides)
        mapping.missingRequired()?.let {
            return ImportOutcome.Refused("$it - the columns found were ${header.fields.joinToString(", ")}")
        }

        val body = rows.drop(headerIndex + 1)
        val staged = mutableListOf<StagedTxn>()
        val rejected = mutableListOf<RejectedRow>()

        body.forEach { row ->
            when (val result = stage(row, mapping, profile)) {
                is Parsed.Ok -> staged.add(result.value)
                is Parsed.Fail -> rejected.add(RejectedRow(row.lineNumber, row.rawLine, result.reason))
            }
        }

        return ImportOutcome.Ready(
            ImportPlan(
                fileName = fileName,
                profileName = profile.name,
                dialect = dialect,
                mapping = mapping,
                dataRows = body.size,
                staged = staged,
                rejected = rejected,
            ),
        )
    }

    /** Stages 3 and 4 for one row. Never throws; a failure is a reason. */
    private fun stage(
        row: CsvRow,
        mapping: ColumnMap.Mapping,
        profile: ImportProfile,
    ): Parsed<StagedTxn> {
        val record = decode(row, mapping)

        val timeText = record[Canonical.TIME] ?: return Parsed.Fail("no date on this row")
        val time = when (val t = ValueParsers.time(timeText)) {
            is Parsed.Ok -> t.value
            is Parsed.Fail -> return Parsed.Fail(t.reason)
        }

        val amountText = record[Canonical.AMOUNT]
        val debitText = record[Canonical.DEBIT]
        val creditText = record[Canonical.CREDIT]

        val kind = when (
            val k = ValueParsers.kind(record[Canonical.TYPE], amountText, debitText, creditText)
        ) {
            is Parsed.Ok -> k.value
            is Parsed.Fail -> return Parsed.Fail(k.reason)
        }

        val moneyText = amountText
            ?: debitText
            ?: creditText
            ?: return Parsed.Fail("no amount on this row")
        val amount = when (val m = ValueParsers.money(moneyText)) {
            is Parsed.Ok -> m.value
            is Parsed.Fail -> return Parsed.Fail(m.reason)
        }
        if (amount == 0L) return Parsed.Fail("amount is zero")

        val accountCell = record[Canonical.ACCOUNT].orEmpty()
        val route = ValueParsers.transferRoute(accountCell)
        val toCell = record[Canonical.TO_ACCOUNT]

        val from = route?.first ?: accountCell.trim().ifEmpty { UNSPECIFIED_ACCOUNT }
        val to = route?.second ?: toCell
        if (kind == RowKind.TRANSFER && to.isNullOrBlank()) {
            return Parsed.Fail("transfer with no destination account")
        }

        return Parsed.Ok(
            StagedTxn(
                lineNumber = row.lineNumber,
                kind = kind,
                // Sign is carried by kind, so a "-425.00 Expense" does not become a
                // negative expense, which would net out against itself downstream.
                amountPaise = if (amount < 0) -amount else amount,
                occurredAt = time.epochMillis,
                timeWasInferred = time.inferred,
                accountName = from,
                toAccountName = to?.trim()?.ifEmpty { null },
                categoryName = if (kind == RowKind.TRANSFER) null else profile.category(record[Canonical.CATEGORY]),
                note = record[Canonical.NOTES],
                reference = record[Canonical.REFERENCE],
                rawLine = row.rawLine,
                rawJson = record.rawJson(),
            ),
        )
    }

    private fun decode(row: CsvRow, mapping: ColumnMap.Mapping): RawRecord {
        val values = mutableMapOf<Canonical, String>()
        val extras = mutableMapOf<String, String>()
        val mappedIndices = mapping.byField.values.toSet()

        mapping.byField.forEach { (field, index) ->
            row.fields.getOrNull(index)?.let { values[field] = it }
        }
        mapping.headers.forEachIndexed { index, header ->
            if (index !in mappedIndices) {
                row.fields.getOrNull(index)?.let { extras[header] = it }
            }
        }
        // A row with more cells than the header still keeps them - losing a column
        // because the header was short is exactly the silent loss I2 forbids.
        if (row.fields.size > mapping.headers.size) {
            (mapping.headers.size until row.fields.size).forEach { index ->
                extras["column${index + 1}"] = row.fields[index]
            }
        }
        return RawRecord(row.lineNumber, row.rawLine, values, extras)
    }
}
