package com.yk.finance.parser

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

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

/** ICICI: "11-Sep-26". Two-digit year, so 26 -> 2026. */
internal fun parseIciciDate(date: String): Long? =
    runCatching { formatter("dd-MMM-yy").parse(date)?.time }.getOrNull()

/** Union: "16-09-2026" with optional "11:23:34". */
internal fun parseUnionDate(date: String, time: String?): Long? = runCatching {
    if (time.isNullOrBlank()) formatter("dd-MM-yyyy").parse(date)?.time
    else formatter("dd-MM-yyyy HH:mm:ss").parse("$date $time")?.time
}.getOrNull()

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
