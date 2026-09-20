package com.yk.finance.ui

import com.yk.finance.domain.CycleCalculator
import com.yk.finance.domain.Period
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val DAY_HEADER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM dd, EEEE", Locale.ENGLISH)

internal val SHORT_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

internal val FULL_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

internal val TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

fun dayHeaderText(millis: Long): String =
    CycleCalculator.toLocalDate(millis).format(DAY_HEADER)

/**
 * The real dates behind a month name.
 *
 * The header says "September, 2026" over a salary cycle that may run 28 Aug to 29 Sep,
 * which is a useful label and a slightly false one. Printing the range underneath is
 * what keeps it from being a lie - and an inferred boundary says so, because it came
 * from arithmetic that cannot see a bank holiday.
 */
fun periodRangeText(period: Period): String {
    val start = CycleCalculator.toLocalDate(period.startMillis).format(SHORT_DATE)
    val range = if (period.isOpen) {
        "since $start"
    } else {
        val end = CycleCalculator.toLocalDate(period.toExclusive).minusDays(1).format(SHORT_DATE)
        "$start - $end"
    }
    return if (period.inferred) "$range · start estimated" else range
}

/**
 * What to print under a month name, if anything.
 *
 * A finished month needs no gloss - "September, 2026" already says 1 to 30 September
 * and repeating it is noise. The month you are *in* is the exception: its figures cover
 * part of a month, and a total that will keep growing should say so rather than be read
 * as the month's final answer.
 */
fun monthSubtitle(period: Period, now: Long): String? {
    if (!period.isOpen) return null
    val today = CycleCalculator.toLocalDate(now)
    val start = CycleCalculator.toLocalDate(period.startMillis)
    return "1 - ${today.format(SHORT_DATE)} so far".takeIf { !today.isBefore(start) }
}

/**
 * 32000 paise -> "320.00", ready to be typed over.
 *
 * The inverse of [rupeesToPaise], and deliberately not [formatRupees]: a text field
 * seeded with "₹3,200.00" is a field whose contents its own parser would have to
 * strip before reading. Plain digits and one dot round-trip exactly.
 */
internal fun paiseToPlain(paise: Long): String {
    val sign = if (paise < 0) "-" else ""
    val abs = kotlin.math.abs(paise)
    return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}

/**
 * "1,234.50" -> 123450 paise. Refuses anything that is not a clean amount.
 *
 * Refuses a third decimal rather than truncating it, and refuses "1.2.3" rather than
 * reading the first two parts. Silently turning a typed 12.345 into 12.34 is exactly
 * the lossy coercion invariant I3 forbids - and this is the function a real bank
 * balance is typed into when an import is anchored, where a quietly dropped digit would
 * be booked as an adjustment and never questioned again.
 *
 * Never goes near a Double: 12.3 is twelve rupees thirty paise, so a short fraction is
 * padded rather than read as written.
 */
internal fun rupeesToPaise(input: String): Long? {
    val cleaned = input.trim().replace(",", "").replace("₹", "")
    if (cleaned.isBlank() || cleaned.count { it == '.' } > 1) return null
    val rupees = cleaned.substringBefore('.')
    val paise = cleaned.substringAfter('.', "").padEnd(2, '0')
    if (paise.length > 2) return null
    if (rupees.isEmpty() || !rupees.all { it.isDigit() } || !paise.all { it.isDigit() }) return null
    return runCatching { rupees.toLong() * 100 + paise.toLong() }.getOrNull()
}
