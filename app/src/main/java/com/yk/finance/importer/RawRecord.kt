package com.yk.finance.importer

/**
 * Stage 3 - one source row, decoded but not yet interpreted.
 *
 * [extras] and [rawLine] together are invariant I2: every column the importer does not
 * understand is still carried, and the original text survives beside the parsed form.
 * If the mapping turns out to be wrong, the truth is still here and nothing has to be
 * exported again.
 */
data class RawRecord(
    val lineNumber: Int,
    val rawLine: String,
    val values: Map<Canonical, String>,
    val extras: Map<String, String>,
) {
    operator fun get(field: Canonical): String? = values[field]?.takeIf { it.isNotBlank() }

    /** Every column, mapped and unmapped, as JSON. Hand-written: no parser to add. */
    fun rawJson(): String {
        val entries = values.entries.map { (field, value) -> field.name to value } +
            extras.entries.map { (header, value) -> header to value }
        return entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
            "\"" + escape(key) + "\":\"" + escape(value) + "\""
        }
    }

    private fun escape(s: String): String = buildString {
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}

/**
 * A row that survived stages 1-4: understood, but not yet bound to account ids or
 * written anywhere. One source row makes exactly one of these, transfers included -
 * the two legs of a transfer are expanded at commit, so conservation stays countable.
 */
data class StagedTxn(
    val lineNumber: Int,
    val kind: RowKind,
    /** Always positive. Direction lives in [kind]. */
    val amountPaise: Long,
    val occurredAt: Long,
    val timeWasInferred: Boolean,
    val accountName: String,
    val toAccountName: String?,
    val categoryName: String?,
    val note: String?,
    val reference: String?,
    val rawLine: String,
    val rawJson: String,
)

/** A row that could not be understood, with the reason and where to find it. */
data class RejectedRow(val lineNumber: Int, val rawLine: String, val reason: String)
