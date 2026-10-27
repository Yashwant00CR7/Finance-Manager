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
    "dd MMM yyyy", "dd MMM yy",
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
    // The trap in the other direction: "yyyy" reads a two-digit year literally, so
    // "11-Sep-26" through "dd-MMM-yyyy" is the year 26 AD - and it is tried first. The
    // sanity check then threw that away for being two thousand years old and used the
    // receipt time instead, so every two-digit-year date the app ever parsed (every ICICI
    // alert among them) was silently the time the message arrived rather than the date it
    // stated. Refused here, so the "yy" twin gets its turn.
    if ("yyyy" in pattern && Calendar.getInstance(IST).apply { time = parsed }.get(Calendar.YEAR) < 1000) return null
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

/** A date as any bank prints it, with the time that sometimes follows it. */
private val DATE_IN_TEXT = Regex(
    """(?<![\d])(\d{4}[-/.]\d{1,2}[-/.]\d{1,2}|\d{1,2}[-/ ]?[A-Za-z]{3}[-/ ]?\d{2,4}|\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4})(?![\d])""" +
        """(?:[\s,:]+(?:at\s+)?(\d{1,2}:\d{2}(?::\d{2})?))?""",
)

/**
 * When a message says the money moved, falling back to when it arrived.
 *
 * For a message filed by hand from the tray. It used to be stamped with the moment you
 * tapped Record, so an alert cleared from the tray on Monday was filed under Monday even
 * though the bank said Friday - and a message from the 30th, cleared on the 2nd, landed in
 * the wrong salary cycle and moved two months' totals at once.
 *
 * A stated date with no time is taken at the receipt time when they fall on the same day,
 * which keeps the day's order honest; any other stated date stands at midnight.
 */
internal fun occurredAtFor(body: String, receivedAt: Long): Long {
    val stated = DATE_IN_TEXT.findAll(body).firstNotNullOfOrNull { m ->
        parseIndianDate(m.groupValues[1], m.groupValues[2].ifEmpty { null })
    } ?: return receivedAt
    val sameDayNoTime = stated == startOfDayIst(stated) && stated == startOfDayIst(receivedAt)
    return if (sameDayNoTime) receivedAt else sanityCheckDate(stated, receivedAt)
}

private const val PAYEE_END =
    """(?:\s+(?:on|via|using|avl|ref(?:erence)?(?:\s*no)?\b|through\b)|\s+(?:(?:Avl|Avb|Avbl|Available)\s+)?Bal\b|\s+Not\s+you\b|[,\n\r;]|\.$|$)"""

private val FAVOURING = Regex(
    """\bFvg:?\s*(.+?)(?:\s+(?:(?:Avl|Avb|Avbl|Available|Clear|Total|Current)\s+)?Bal(?:ance)?\b|[,\n\r;]|\s+Not\s+you\b|\s+If\s+not\s+you\b|\s+Call\s+\d+|\s+ref\s*no\b|\.$|$)""",
    RegexOption.IGNORE_CASE,
)
private val TO_PAYEE = Regex("""\b(?:to|towards)\s+([A-Za-z0-9][A-Za-z0-9\s.&'/-]{1,50}?)$PAYEE_END""", RegexOption.IGNORE_CASE)
private val VPA_PAYEE = Regex("""\b(?:VPA|vpa)\s+([a-zA-Z0-9.-]+@[a-zA-Z0-9.-]+)""", RegexOption.IGNORE_CASE)

/** "UPI/P2M/629812345678/SWIGGY" - the payee is the last segment of the UPI path. */
private val UPI_PATH_PAYEE = Regex(
    """\bUPI/(?:P2[AM]|DR|CR)/\w+/([^/\n.]+?)(?:\s+(?:Value|on)\b|/|\n|\.|$)""",
    RegexOption.IGNORE_CASE,
)

/**
 * Card spends: "spent on Card XX1234 at AMAZON on 12-Jan". Only after a word that says
 * money was spent - "update your KYC at nearest branch" is an "at" too. A time never
 * starts with a letter, which keeps "at 10:15" out.
 */
private val AT_PAYEE = Regex(
    """\b(?:spent|spend|used|purchase|paid|charged)\b[^.\n]{0,80}?\bat\s+([A-Za-z][A-Za-z0-9\s.&'*/-]{1,50}?)(?:\s+on\b|\s+(?:Avl|Avb|Avbl|Available)\b|[,\n\r;(]|\.(?:\s|$)|$)""",
    RegexOption.IGNORE_CASE,
)

/** Credits name whoever sent the money: "credited ... from R SRINIVASAN", "from VPA x@y". */
private val FROM_PAYEE = Regex(
    """\bfrom\s+(?:VPA\s+)?([A-Za-z0-9][A-Za-z0-9\s.&'@/-]{1,50}?)(?:\s+(?:on|via|using|avl|ref\s*no\b|through\b|UPI\b)|\s+(?:(?:Avl|Avb|Avbl|Available)\s+)?Bal\b|\s+Not\s+you\b|[,\n\r;(]|\.(?:\s|$)|$)""",
    RegexOption.IGNORE_CASE,
)

/**
 * Generic payee extraction across all banks for standard patterns such as:
 * - "Fvg: AONE TRA" ("Favoring")
 * - "to <Payee>"
 * - "VPA <upi-id>"
 *
 * Kept in this order for callers that do not know the direction. See [extractGenericPayee].
 */
internal val GENERIC_PAYEE_PATTERNS = listOf(FAVOURING, TO_PAYEE, VPA_PAYEE)

/**
 * Money going out is named by who it went to; money coming in by who sent it. "to" in a
 * credit is almost always our own account ("credited to your A/c"), and "from" in a debit
 * is the same ("debited from A/c XX1234"), so each direction looks only where its
 * counterparty actually stands.
 */
private val DEBIT_PAYEE_PATTERNS = listOf(FAVOURING, UPI_PATH_PAYEE, TO_PAYEE, AT_PAYEE, VPA_PAYEE)
private val CREDIT_PAYEE_PATTERNS = listOf(FAVOURING, UPI_PATH_PAYEE, VPA_PAYEE, FROM_PAYEE)

/**
 * Things that sit where a payee would and are not one.
 *
 * Every entry was a real wrong answer. Union's trailer "SMS BLOCK 2209 to 8879365472" made the
 * bank's own phone number the payee of every Union debit without a Fvg clause - and because
 * rules are keyed on the payee, filing one of those as Food filed all of them as Food.
 * "credited to your A/c XX2209" and "to HDFC Bank A/c XX1234" named our own account.
 */
private val NOT_A_PAYEE = listOf(
    Regex("""^[+\d\s-]+$"""),
    Regex("""^(?:your|ur|you|the|self|a/c|acct)\b""", RegexOption.IGNORE_CASE),
    Regex("""\b(?:A/c|Acct|Account)\b""", RegexOption.IGNORE_CASE),
    Regex("""[Xx*]{2,}\d{3,}"""),
)

private fun isPlausiblePayee(candidate: String): Boolean =
    candidate.length >= 2 && NOT_A_PAYEE.none { it.containsMatchIn(candidate) }

/**
 * The counterparty, from whichever of the common clauses the message uses.
 *
 * Every match of a clause is considered, not only the first, so a junk "to" early in the
 * message no longer hides a real one - or a VPA - later on.
 */
internal fun extractGenericPayee(body: String, direction: Direction? = null): String? {
    val patterns = when (direction) {
        Direction.DEBIT -> DEBIT_PAYEE_PATTERNS
        Direction.CREDIT -> CREDIT_PAYEE_PATTERNS
        null -> GENERIC_PAYEE_PATTERNS
    }
    for (pattern in patterns) {
        pattern.findAll(body)
            .map { it.groupValues[1].trim() }
            .firstOrNull(::isPlausiblePayee)
            ?.let { return it }
    }
    return null
}

