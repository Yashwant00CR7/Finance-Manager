package com.yk.finance.importer

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** A value parser never throws. Failure is a reason, and the row goes to review. */
sealed class Parsed<out T> {
    data class Ok<out T>(val value: T) : Parsed<T>()
    data class Fail(val reason: String) : Parsed<Nothing>()
}

/** Expense, income, or a move between two of your own accounts. */
enum class RowKind { EXPENSE, INCOME, TRANSFER }

data class ParsedTime(val epochMillis: Long, val inferred: Boolean)

/**
 * Stage 4 - the individually-testable value parsers.
 *
 * These are where an importer usually loses data quietly: a thousands separator read
 * as a decimal point, a day-first date read month-first, a minus sign in a column the
 * reader did not expect. Each one here either produces an exact value or refuses.
 */
object ValueParsers {

    val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    // ----- money -----

    private val CURRENCY = Regex("(?i)(inr|rs\\.?|₹|\\$|usd)")

    /**
     * Text to paise, exactly.
     *
     * Through [BigDecimal] and back again: invariant I4 says an amount that cannot
     * round-trip is rejected rather than rounded. Money that is a little bit wrong is
     * worse than money that is visibly missing, because only one of the two gets
     * noticed.
     */
    fun money(raw: String): Parsed<Long> {
        // Non-breaking space: exports pasted out of a web page are full of them.
        var s = raw.replace('\u00A0', ' ').trim()
        if (s.isEmpty()) return Parsed.Fail("empty amount")

        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true
            s = s.substring(1, s.length - 1).trim()
        }
        s = CURRENCY.replace(s, "").trim()
        when {
            s.startsWith("-") -> { negative = !negative; s = s.substring(1).trim() }
            s.startsWith("+") -> s = s.substring(1).trim()
        }
        if (s.isEmpty()) return Parsed.Fail("no digits in \"$raw\"")

        s = stripGrouping(s) ?: return Parsed.Fail("cannot tell grouping from decimals in \"$raw\"")
        if (!s.matches(Regex("""\d+(\.\d+)?"""))) return Parsed.Fail("not a number: \"$raw\"")

        val decimal = s.toBigDecimalOrNull() ?: return Parsed.Fail("not a number: \"$raw\"")
        if (decimal.scale() > 2) return Parsed.Fail("finer than paise: \"$raw\"")

        val paise = decimal.movePointRight(2).toBigInteger().toLong()
        // The round-trip assertion itself. If this ever fails the parse was lossy.
        if (BigDecimal(paise).movePointLeft(2).compareTo(decimal) != 0) {
            return Parsed.Fail("amount does not round-trip: \"$raw\"")
        }
        return Parsed.Ok(if (negative) -paise else paise)
    }

    /**
     * Removes thousands separators, leaving a plain decimal.
     *
     * When both separators appear the last one is the decimal point - that resolves
     * `1,234.56` and `1.234,56` without guessing. With only commas, a trailing group
     * of exactly three digits is grouping (`12,345`) and anything else is a decimal
     * comma (`12,34`). Indian grouping (`1,23,456.78`) falls out of the first rule.
     */
    private fun stripGrouping(s: String): String? {
        val lastComma = s.lastIndexOf(',')
        val lastDot = s.lastIndexOf('.')
        return when {
            lastComma >= 0 && lastDot >= 0 ->
                if (lastDot > lastComma) s.replace(",", "") else s.replace(".", "").replace(',', '.')

            lastComma >= 0 -> {
                val tail = s.substring(lastComma + 1)
                if (tail.length == 3 && tail.all { it.isDigit() }) s.replace(",", "")
                else s.replace(',', '.')
            }

            else -> s
        }
    }

    // ----- time -----

    private fun formatter(pattern: String): DateTimeFormatter =
        DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern(pattern)
            .toFormatter(Locale.ENGLISH)

    private val DATE_TIME_PATTERNS = listOf(
        "MMM d, yyyy h:mm a",      // My Money Pro
        "MMM d, yyyy H:mm",
        "d MMM yyyy h:mm a",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm",
        "yyyy-MM-dd HH:mm",
        "dd-MM-yyyy HH:mm:ss",
        "dd-MM-yyyy HH:mm",
        "dd/MM/yyyy HH:mm:ss",
        "dd/MM/yyyy HH:mm",
        "dd-MMM-yyyy HH:mm",
        "dd MMM yyyy HH:mm",
    ).map(::formatter)

    /**
     * Date-only patterns. Day-first throughout: `03/04/2026` is 3 April here, which is
     * the Indian convention and is stated in the preview rather than assumed silently.
     */
    private val DATE_ONLY_PATTERNS = listOf(
        "yyyy-MM-dd",
        "dd-MM-yyyy",
        "dd/MM/yyyy",
        "MMM d, yyyy",
        "d MMM yyyy",
        "dd-MMM-yyyy",
        "dd-MMM-yy",
        "dd.MM.yyyy",
    ).map(::formatter)

    fun time(raw: String): Parsed<ParsedTime> {
        val s = raw.trim()
        if (s.isEmpty()) return Parsed.Fail("empty date")

        if (s.all { it.isDigit() }) {
            val n = s.toLongOrNull() ?: return Parsed.Fail("not a timestamp: \"$raw\"")
            return when (s.length) {
                13 -> Parsed.Ok(ParsedTime(n, inferred = false))
                10 -> Parsed.Ok(ParsedTime(n * 1000, inferred = false))
                else -> Parsed.Fail("not a timestamp: \"$raw\"")
            }
        }

        DATE_TIME_PATTERNS.forEach { pattern ->
            runCatching { LocalDateTime.parse(s, pattern) }.getOrNull()?.let {
                return Parsed.Ok(ParsedTime(it.atZone(IST).toInstant().toEpochMilli(), inferred = false))
            }
        }

        DATE_ONLY_PATTERNS.forEach { pattern ->
            runCatching { LocalDate.parse(s, pattern) }.getOrNull()?.let {
                // Midday, and flagged. Midnight would silently move a row into the
                // previous cycle whenever a boundary falls on that date.
                return Parsed.Ok(
                    ParsedTime(
                        it.atTime(12, 0).atZone(IST).toInstant().toEpochMilli(),
                        inferred = true,
                    ),
                )
            }
        }
        return Parsed.Fail("unrecognised date format: \"$raw\"")
    }

    // ----- direction -----

    private val INCOME_WORDS = listOf("income", "credit", "deposit", "paidin", "received", "in")
    private val EXPENSE_WORDS = listOf("expense", "debit", "withdrawal", "paidout", "spent", "out")

    /**
     * Expense, income or transfer, from whichever of the three possible signals the
     * file actually carries: a type column, a split debit/credit pair, or the sign of
     * the amount itself.
     */
    fun kind(
        type: String?,
        amountText: String?,
        debitText: String? = null,
        creditText: String? = null,
    ): Parsed<RowKind> {
        val raw = type?.trim().orEmpty()
        // The marker prefixes have to be read before punctuation is stripped.
        when {
            raw.startsWith("(*)") -> return Parsed.Ok(RowKind.TRANSFER)
            raw.startsWith("(+)") -> return Parsed.Ok(RowKind.INCOME)
            raw.startsWith("(-)") -> return Parsed.Ok(RowKind.EXPENSE)
        }

        val word = raw.lowercase().filter { it.isLetter() }
        when {
            word.contains("transfer") -> return Parsed.Ok(RowKind.TRANSFER)
            // Income before expense: "credit" would otherwise be reached by nothing,
            // but "withdrawal" contains "dr" and must not be read as a credit.
            INCOME_WORDS.any { word == it || word.contains(it) && it.length > 2 } ->
                return Parsed.Ok(RowKind.INCOME)
            EXPENSE_WORDS.any { word == it || word.contains(it) && it.length > 2 } ->
                return Parsed.Ok(RowKind.EXPENSE)
            word == "cr" -> return Parsed.Ok(RowKind.INCOME)
            word == "dr" -> return Parsed.Ok(RowKind.EXPENSE)
        }

        if (!debitText.isNullOrBlank() && money(debitText) is Parsed.Ok) return Parsed.Ok(RowKind.EXPENSE)
        if (!creditText.isNullOrBlank() && money(creditText) is Parsed.Ok) return Parsed.Ok(RowKind.INCOME)

        val amount = amountText?.let { money(it) }
        if (amount is Parsed.Ok) {
            return Parsed.Ok(if (amount.value < 0) RowKind.EXPENSE else RowKind.INCOME)
        }
        return Parsed.Fail(
            if (raw.isBlank()) "no type column and the amount has no sign"
            else "unrecognised transaction type: \"$raw\"",
        )
    }

    /** `Salary->Card`, `Salary -> Card`, `Salary > Card`. Null when it is not a route. */
    fun transferRoute(account: String): Pair<String, String>? {
        val separator = listOf("->", "→", " > ", "=>").firstOrNull { account.contains(it) } ?: return null
        val parts = account.split(separator)
        if (parts.size != 2) return null
        val from = parts[0].trim()
        val to = parts[1].trim()
        return if (from.isEmpty() || to.isEmpty()) null else from to to
    }
}
