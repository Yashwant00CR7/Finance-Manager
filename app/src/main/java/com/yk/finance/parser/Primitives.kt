package com.yk.finance.parser

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The currency token, as banks actually print it: "Rs", "Rs.", "Rs:", "INR", "INR.", "₹".
 * The rupee sign is real and increasingly common - several banks switched to it, and a
 * parser that only knows "Rs" silently stops working the day a bank modernises its
 * template.
 */
internal const val CURRENCY = """(?:Rs|INR|₹)\.?:?"""

/**
 * An amount as printed, including Indian lakh grouping ("1,00,000.00"). Exposed so specs
 * and the shared label patterns capture money the same way everywhere.
 */
internal const val AMOUNT_GROUP = """([0-9][0-9,]*(?:\.\d{1,2})?)"""

/** Money is stored as paise. Never as Double - rounding drift in a ledger is unforgivable. */
internal fun parseAmountToPaise(raw: String): Long? {
    val cleaned = raw.replace(",", "").trim()
    val parts = cleaned.split(".")
    return when (parts.size) {
        1 -> parts[0].toLongOrNull()?.times(100)
        2 -> {
            val rupees = parts[0].toLongOrNull() ?: return null
            // "5" in "Rs 10.5" means 50 paise, not 5.
            val paise = parts[1].padEnd(2, '0').take(2).toLongOrNull() ?: return null
            rupees * 100 + paise
        }
        else -> null
    }
}

private val IST: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

private fun formatter(pattern: String) = SimpleDateFormat(pattern, Locale.ENGLISH).apply {
    timeZone = IST
    isLenient = false
}

/**
 * Every date layout seen across Indian bank alerts, most specific first.
 *
 * Order is load-bearing, for a reason that is easy to miss: SimpleDateFormat's "yy" will
 * happily consume a four-digit year, so "16-09-2026" read with "dd-MM-yy" yields 2020 and
 * leaves "26" unconsumed. [parseExact] rejects leftovers, and the four-digit patterns sit
 * ahead of their two-digit twins so the right one is reached first either way.
 */
private val DATE_PATTERNS = listOf(
    "dd-MMM-yyyy", "dd-MMM-yy",
    "dd/MMM/yyyy", "dd/MMM/yy",
    "ddMMMyyyy", "ddMMMyy",
    "dd-MM-yyyy", "dd-MM-yy",
    "dd/MM/yyyy", "dd/MM/yy",
    "dd.MM.yyyy", "dd.MM.yy",
    "yyyy-MM-dd",
)

private val TIME_PATTERNS = listOf("HH:mm:ss", "HH:mm")

/**
 * Parses only if the pattern consumes the whole string.
 *
 * SimpleDateFormat matches a prefix and ignores the rest, which turns a near-miss format
 * into a confidently wrong date rather than a failure. In a ledger a wrong date is worse
 * than no date: no date falls back to the receipt time, which is almost right, while a
 * wrong one files the payment in the wrong month and quietly corrupts a budget.
 */
private fun parseExact(pattern: String, text: String): Long? {
    val position = ParsePosition(0)
    val parsed = runCatching { formatter(pattern).parse(text, position) }.getOrNull() ?: return null
    if (position.index != text.length) return null
    return parsed.time
}

/**
 * A date from any Indian bank alert, with an optional companion time.
 *
 * Day-first is assumed throughout, which is safe here: Indian banks do not send US-style
 * month-first dates, so "05-09-26" is the fifth of September and never the ninth of May.
 */
internal fun parseIndianDate(date: String, time: String? = null): Long? {
    val cleanDate = date.trim()
    val cleanTime = time?.trim()?.takeIf { it.isNotEmpty() }

    if (cleanTime != null) {
        for (datePattern in DATE_PATTERNS) {
            for (timePattern in TIME_PATTERNS) {
                parseExact("$datePattern $timePattern", "$cleanDate $cleanTime")?.let { return it }
            }
        }
    }
    for (datePattern in DATE_PATTERNS) {
        parseExact(datePattern, cleanDate)?.let { return it }
    }
    return null
}

/** ICICI: "11-Sep-26". Two-digit year, so 26 -> 2026. */
internal fun parseIciciDate(date: String): Long? = parseIndianDate(date)

/** Union: "16-09-2026" with optional "11:23:34". */
internal fun parseUnionDate(date: String, time: String?): Long? = parseIndianDate(date, time)

/**
 * A bank date carries no year ambiguity we can trust blindly: a message received
 * on 01-Jan quoting "31-Dec-25" is last year. If the parsed date lands more than
 * a day in the future relative to receipt, it is wrong - fall back to receipt time.
 */
internal fun sanityCheckDate(parsed: Long?, receivedAt: Long): Long {
    if (parsed == null) return receivedAt
    val oneDayAhead = receivedAt + 24 * 60 * 60 * 1000L
    val twoYearsBack = receivedAt - 730L * 24 * 60 * 60 * 1000L
    return if (parsed > oneDayAhead || parsed < twoYearsBack) receivedAt else parsed
}

internal fun startOfDayIst(epochMillis: Long): Long = Calendar.getInstance(IST).apply {
    timeInMillis = epochMillis
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

internal fun istCalendar(): Calendar = Calendar.getInstance(IST)
