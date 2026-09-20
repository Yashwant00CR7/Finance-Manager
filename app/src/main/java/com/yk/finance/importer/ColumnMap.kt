package com.yk.finance.importer

/** The fields the importer understands. Everything else is kept, not understood. */
enum class Canonical {
    TIME, TYPE, AMOUNT, DEBIT, CREDIT, CATEGORY, ACCOUNT, TO_ACCOUNT,
    NOTES, REFERENCE, BALANCE, CURRENCY,
}

/**
 * Stage 2 - header row to canonical fields.
 *
 * No fixed column order and no fixed names, because the next export will not be this
 * one. A column that matches nothing is not an error: it is retained verbatim under
 * invariant I2, which is what lets the importer accept any fields that come in.
 */
object ColumnMap {

    private val SYNONYMS: Map<Canonical, List<String>> = mapOf(
        Canonical.TIME to listOf(
            "time", "date", "transactiondate", "txndate", "valuedate", "datetime",
            "timestamp", "when", "postingdate",
        ),
        Canonical.TYPE to listOf("type", "kind", "direction", "drcr", "debitcredit", "transactiontype"),
        Canonical.AMOUNT to listOf("amount", "amt", "value", "sum", "money", "transactionamount"),
        Canonical.DEBIT to listOf("debit", "withdrawal", "withdrawals", "paidout", "dr", "debitamount"),
        Canonical.CREDIT to listOf("credit", "deposit", "deposits", "paidin", "cr", "creditamount"),
        Canonical.CATEGORY to listOf("category", "tag", "label", "head", "subcategory"),
        Canonical.ACCOUNT to listOf("account", "wallet", "source", "from", "paidby", "fromaccount"),
        Canonical.TO_ACCOUNT to listOf("to", "toaccount", "destination", "transferto"),
        Canonical.NOTES to listOf(
            "notes", "note", "description", "remark", "remarks", "narration",
            "particulars", "memo", "payee", "details", "comment",
        ),
        Canonical.REFERENCE to listOf(
            "reference", "ref", "refno", "utr", "rrn", "chequeno", "transactionid", "txnid",
        ),
        Canonical.BALANCE to listOf("balance", "runningbalance", "closingbalance", "avlbal", "availablebalance"),
        Canonical.CURRENCY to listOf("currency", "ccy"),
    )

    /** Case, spacing and punctuation are all noise. `"NOTES "` and `Notes` are one column. */
    fun normalise(header: String): String =
        header.lowercase().filter { it.isLetterOrDigit() }

    data class Mapping(
        val byField: Map<Canonical, Int>,
        val headers: List<String>,
    ) {
        val unmapped: List<String>
            get() = headers.filterIndexed { index, _ -> index !in byField.values }

        fun sourceColumn(field: Canonical): String? = byField[field]?.let { headers[it] }

        /**
         * A file with no time or no amount cannot be imported at all, and saying so
         * before anything is written beats a run that produces 226 rejected rows.
         */
        fun missingRequired(): String? = when {
            Canonical.TIME !in byField -> "no date or time column"
            Canonical.AMOUNT in byField -> null
            Canonical.DEBIT in byField || Canonical.CREDIT in byField -> null
            else -> "no amount column, and no debit/credit pair either"
        }
    }

    fun map(headers: List<String>, overrides: Map<String, Canonical> = emptyMap()): Mapping {
        val byField = mutableMapOf<Canonical, Int>()
        headers.forEachIndexed { index, header ->
            val key = normalise(header)
            val field = overrides[key] ?: SYNONYMS.entries.firstOrNull { (_, names) -> key in names }?.key
            // First column wins: a statement with both "Date" and "Value Date" should
            // use the one the bank printed first rather than the last one seen.
            if (field != null && field !in byField) byField[field] = index
        }
        return Mapping(byField, headers)
    }
}
