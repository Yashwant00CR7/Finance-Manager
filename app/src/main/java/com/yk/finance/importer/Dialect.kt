package com.yk.finance.importer

/**
 * Stage 1 - what shape this particular file is.
 *
 * Not over-engineering: the very first real export already needed most of it. Every
 * line in the My Money Pro file ends with a space *after* the closing quote, and its
 * header is literally `"NOTES" `, so a naive reader produces a column named `NOTES" `
 * and then fails to find the notes. A file that is nearly-but-not-quite CSV is the
 * normal case, not the exception.
 */
data class Dialect(
    val delimiter: Char,
    val quote: Char = '"',
    val hadBom: Boolean = false,
) {
    val description: String
        get() = buildString {
            append(
                when (delimiter) {
                    '\t' -> "tab-separated"
                    ';' -> "semicolon-separated"
                    '|' -> "pipe-separated"
                    else -> "comma-separated"
                },
            )
            append(", quoted with ").append(quote)
            if (hadBom) append(", byte-order mark")
        }
}

/** One parsed row, with the original text kept alongside it (invariant I2). */
data class CsvRow(val lineNumber: Int, val fields: List<String>, val rawLine: String) {
    val arity get() = fields.size
    val isBlank get() = fields.all { it.isBlank() }
}

object Csv {

    private val CANDIDATE_DELIMITERS = listOf(',', ';', '\t', '|')

    private const val BOM = '\uFEFF'

    /**
     * Picks the delimiter that splits the file most consistently.
     *
     * Consistency rather than frequency: a notes column full of commas can easily
     * contain more semicolons than the real delimiter appears, but only the real
     * delimiter yields the same field count on line after line.
     */
    fun sniff(text: String): Dialect {
        val hadBom = text.startsWith(BOM)
        val body = if (hadBom) text.substring(1) else text
        val sample = body.lineSequence().filter { it.isNotBlank() }.take(20).toList()
        if (sample.isEmpty()) return Dialect(',', hadBom = hadBom)

        val quote = if (sample.any { it.contains('"') }) '"' else '\''
        val best = CANDIDATE_DELIMITERS
            .map { it to consistency(sample, it, quote) }
            .filter { (_, score) -> score.arity >= 2 }
            .maxByOrNull { (_, score) -> score.agreeing * 100 + score.arity }
            ?.first
            ?: ','

        return Dialect(delimiter = best, quote = quote, hadBom = hadBom)
    }

    private data class Consistency(val arity: Int, val agreeing: Int)

    private fun consistency(lines: List<String>, delimiter: Char, quote: Char): Consistency {
        val arities = lines.map { line ->
            var count = 1
            var inQuotes = false
            line.forEach { c ->
                when {
                    c == quote -> inQuotes = !inQuotes
                    c == delimiter && !inQuotes -> count++
                }
            }
            count
        }
        // Widest wins a tie. A preamble of one-cell lines can be as numerous as the
        // table itself, and the table is the part with columns.
        val modal = arities.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key })
            .firstOrNull() ?: return Consistency(0, 0)
        return Consistency(arity = modal.key, agreeing = modal.value)
    }

    /**
     * Stage 1 proper: text to rows.
     *
     * Handles doubled quotes, embedded newlines inside quoted fields, all three line
     * endings, and - the reason this is hand-written rather than borrowed - junk
     * between a closing quote and the next delimiter, which is discarded from the
     * field but never from [CsvRow.rawLine].
     */
    fun parse(text: String, dialect: Dialect): List<CsvRow> {
        val body = if (dialect.hadBom) text.substring(1) else text
        val rows = mutableListOf<CsvRow>()
        var i = 0
        var line = 1

        while (i < body.length) {
            val rowStart = i
            val rowLine = line
            val fields = mutableListOf<String>()
            val field = StringBuilder()
            var inQuotes = false
            var endOfRow = false

            while (i < body.length && !endOfRow) {
                val c = body[i]
                when {
                    inQuotes && c == dialect.quote &&
                        i + 1 < body.length && body[i + 1] == dialect.quote -> {
                        field.append(dialect.quote)
                        i += 2
                    }

                    inQuotes && c == dialect.quote -> {
                        inQuotes = false
                        i++
                        // Anything between the closing quote and the delimiter is not
                        // data. This is the trailing space that breaks naive readers.
                        while (i < body.length &&
                            body[i] != dialect.delimiter && body[i] != '\n' && body[i] != '\r'
                        ) {
                            i++
                        }
                    }

                    inQuotes -> {
                        if (c == '\n') line++
                        field.append(c)
                        i++
                    }

                    c == dialect.quote -> { inQuotes = true; i++ }

                    c == dialect.delimiter -> {
                        fields.add(field.toString())
                        field.setLength(0)
                        i++
                    }

                    c == '\r' -> {
                        i++
                        if (i < body.length && body[i] == '\n') i++
                        line++
                        endOfRow = true
                    }

                    c == '\n' -> { i++; line++; endOfRow = true }

                    else -> { field.append(c); i++ }
                }
            }
            fields.add(field.toString())

            val raw = body.substring(rowStart, i).trimEnd('\r', '\n')
            val row = CsvRow(rowLine, fields.map { it.trim() }, raw)
            if (!row.isBlank) rows.add(row)
        }
        return rows
    }

    /**
     * Finds the header among any preamble.
     *
     * Bank statements routinely open with an account holder's name and a date range
     * before the real table starts. The header is the first row whose field count
     * matches the file's dominant field count.
     */
    fun headerIndex(rows: List<CsvRow>): Int {
        if (rows.isEmpty()) return -1
        val counts = rows.filter { it.arity >= 2 }.groupingBy { it.arity }.eachCount()
        if (counts.isEmpty()) return 0
        val modal = counts.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key })
            .first().key
        return rows.indexOfFirst { it.arity == modal }
    }
}
