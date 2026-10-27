package com.yk.finance.domain

import com.yk.finance.data.Account
import com.yk.finance.data.AccountKind
import com.yk.finance.data.LearnedPattern
import com.yk.finance.parser.BalanceMeaning
import com.yk.finance.parser.DIRECTION_WORDS
import com.yk.finance.parser.Direction
import com.yk.finance.parser.PatternSpec
import com.yk.finance.parser.Tier
import com.yk.finance.parser.applySpec
import com.yk.finance.parser.extractGenericPayee
import com.yk.finance.parser.parseAmountToPaise
import com.yk.finance.parser.parseIndianDate

/**
 * Whether a stored template may still be matched against messages.
 *
 * Earlier versions fell back, when a template would not fit, to a "relaxed" one: the first
 * three words, then `.*?` to the amount and `.*?` to any account digits, with the direction
 * fixed. It matched any message from that bank that opened the same way - a credit as
 * readily as the debit it was learned from - and booked it unattended as a debit. Those
 * rows are left in the database, since they record something you did, but never matched
 * again. [TemplateLearner] no longer writes `.*?` anywhere, so the marker is unambiguous.
 */
fun LearnedPattern.isUsable(): Boolean = !regex.contains(".*?")

/**
 * Converts a [LearnedPattern] database row into an executable [PatternSpec].
 * Learned patterns are marked as [Tier.VERIFIED] because the user manually reviewed
 * and confirmed the transaction from this template.
 */
fun LearnedPattern.toPatternSpec(): PatternSpec {
    val hasDirGroup = regex.contains("(?<dir>")
    return PatternSpec(
        id = id,
        bank = bank,
        tier = Tier.VERIFIED,
        direction = if (hasDirGroup) null else direction,
        regex = Regex(regex, RegexOption.IGNORE_CASE),
        // A card alert's balance-shaped figure is the remaining limit. See BalanceMeaning.
        balanceMeaning = if (isCard) BalanceMeaning.AVAILABLE_CREDIT else BalanceMeaning.ACCOUNT_BALANCE,
        isCard = isCard,
    )
}

/**
 * Synthesizes a reusable pattern template when an unparsed message is manually recorded.
 *
 * It finds variable slots (amount, account token, date, time, payee, direction) in the raw message,
 * replaces them with named capture groups, and escapes the invariant sentence structure
 * into a regex. Future alerts following this sentence structure will match automatically
 * and book into the ledger without needing human intervention.
 *
 * ## What a template keeps, and what it lets go
 *
 * A template is only worth having if the bank's next message matches it, and the next
 * message differs in more than the obvious slots. So the sentence is kept and everything
 * that drifts is loosened:
 *
 *  - every run of digits left in the fixed text matches any digits - a helpline number, a
 *    "1800-APEX", a second amount nobody slotted;
 *  - whitespace either side of a slot is optional, so "Rs:1755" and "Rs: 1755" both read;
 *  - only the last four words before the first slot are kept, because greetings vary;
 *  - nothing after the last slot is kept at all, because that is where banks put the
 *    helpline, the dispute line and the "never share your OTP" line, and they change those
 *    without changing anything that matters.
 *
 * What it never does is fall back to something looser when the sentence will not fit. A
 * template that cannot reproduce the message it was learned from is not learned.
 */
object TemplateLearner {

    const val ID_PREFIX = "LEARNED."

    private const val AMOUNT_SLOT = """(?<amount>\d+(?:,\d+)*(?:\.\d{1,2})?)"""
    private const val ACCT_SLOT = """[Xx*.-]*(?<acct>\d{3,})"""
    private const val DATE_SLOT =
        """(?<date>\d{4}[-/.]\d{1,2}[-/.]\d{1,2}|\d{1,2}[-/ ]?[A-Za-z]{3}[-/ ]?\d{2,4}|\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4})"""
    private const val TIME_SLOT = """(?<time>\d{1,2}:\d{2}(?::\d{2})?(?:\s*[AaPp][Mm])?)"""
    private const val REF_SLOT = """(?<ref>[A-Z0-9]{6,22})"""
    private const val BAL_SLOT = """(?<bal>\d+(?:,\d+)*(?:\.\d{1,2})?)"""
    private const val PAYEE = "payee"

    /** Every word [DIRECTION_WORDS] understands, longest first so "debited" wins over "debit". */
    private val DIR_SLOT = "(?<dir>" + DIRECTION_WORDS.keys.sortedByDescending { it.length }.joinToString("|") + ")"

    /**
     * Where a direction slot may go: verbs only, never "debit" or "credit". Those are nearly
     * always nouns - "Debit Card", "Credit Alert!" - and a slot on "Debit Card" would read
     * the same bank's "Credit Card" alert as money coming in.
     */
    private val DEBIT_WORDS = listOf("debited", "spent", "paid", "withdrawn", "deducted", "sent", "dr")
    private val CREDIT_WORDS = listOf("credited", "received", "deposited", "cr")

    /** A number as printed. Not the tail of another one: "12.345" holds no "345". */
    private val NUMBER = Regex("""(?<![\d,]|\d\.)\d+(?:,\d+)*(?:\.\d{1,2})?(?!\d)""")
    private val CURRENCY_BEFORE = Regex("""(?:Rs|INR|₹)\.?:?\s*$""", RegexOption.IGNORE_CASE)
    private val BALANCE_BEFORE = Regex("""Bal(?:ance)?(?:\s+is)?[-:\s]*(?:Rs|INR|₹)?\.?:?\s*$""", RegexOption.IGNORE_CASE)

    private val STANDALONE_DIGITS = Regex("""(?<!\d)\d{3,}(?!\d)""")

    /** What stands right before an account number: a mask, or the word for one. */
    private val ACCOUNT_BEFORE = Regex(
        """(?:[Xx*]|\b(?:A/c|Acct|Acc|Account|AC|Card)\s*(?:no\.?\s*)?(?:ending\s+(?:with\s+)?)?[Xx*.]*)$""",
        RegexOption.IGNORE_CASE,
    )

    private val DATE_REGEX = Regex(
        """(?<!\d)(?:\d{4}[-/.]\d{1,2}[-/.]\d{1,2}|\d{1,2}[-/ ]?[A-Za-z]{3}[-/ ]?\d{2,4}|\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4})(?!\d)""",
    )

    private val TIME_REGEX = Regex("""(?<![\d:])\d{1,2}:\d{2}(?::\d{2})?(?:\s*[AaPp][Mm])?(?![\d:])""")

    private val REF_LABEL = Regex(
        """\b(?:UPI\s*Ref(?:erence)?\s*(?:No\.?)?|UPI|RRN|UTR|IMPS|NEFT|ref(?:erence)?\s*(?:no\.?|id|#)?|(?:txn|tran)\s*(?:id|no\.?)?)""" +
            """[:\s.#-]*(?=[A-Z]*\d)([A-Z0-9]{6,22})\b""",
        RegexOption.IGNORE_CASE,
    )

    private val BALANCE_REGEX = Regex(
        """\b(?:Avl|Avb|Avbl|Avlbl|Avail|Available|Clear|Clr|Current|Final|Total|New)?\.?\s*Bal(?:ance)?(?:\s+is)?[-:\s]*(?:Rs|INR|₹)?\.?:?\s*(\d+(?:,\d+)*(?:\.\d{1,2})?)""",
        RegexOption.IGNORE_CASE,
    )

    private val WHITESPACE = Regex("""\s+""")
    private val DIGIT_RUN = Regex("""\d+""")

    /** Kept from before the first slot. Enough to name the template, not the greeting. */
    private const val HEAD_WORDS = 4

    /** Below this a template is a shape any message could fall into, not a sentence. */
    private const val MIN_FIXED_LETTERS = 4

    private data class Span(val start: Int, val end: Int, val slot: String)

    /**
     * [bank] is who the template is filed under, and so whose messages it will be tried
     * against: the sending bank, not the account it was filed to. They differ whenever the
     * account was one you added by hand - its bank is "UNBOUND" until a message binds it -
     * and a template filed under that would never be tried against anything.
     */
    fun learn(
        rawMessage: String,
        amountPaise: Long,
        direction: Direction,
        account: Account,
        payee: String?,
        bank: String = account.bank,
    ): LearnedPattern? {
        val spans = mutableListOf<Span>()
        fun free(start: Int, end: Int) = spans.none { maxOf(start, it.start) < minOf(end, it.end) }
        fun claim(start: Int, end: Int, slot: String) {
            if (free(start, end)) spans += Span(start, end, slot)
        }

        val amount = findAmount(rawMessage, amountPaise) ?: return null
        claim(amount.first, amount.last + 1, AMOUNT_SLOT)

        val acct = findAccount(rawMessage, account.accountToken, ::free) ?: return null
        claim(acct.first, acct.last + 1, ACCT_SLOT)
        val observedToken = rawMessage.substring(acct).filter(Char::isDigit)

        directionWord(rawMessage, direction, ::free)?.let { claim(it.first, it.last + 1, DIR_SLOT) }

        DATE_REGEX.findAll(rawMessage)
            .firstOrNull { free(it.range.first, it.range.last + 1) && parseIndianDate(it.value) != null }
            ?.let { claim(it.range.first, it.range.last + 1, DATE_SLOT) }

        TIME_REGEX.findAll(rawMessage)
            .firstOrNull { free(it.range.first, it.range.last + 1) }
            ?.let { claim(it.range.first, it.range.last + 1, TIME_SLOT) }

        REF_LABEL.findAll(rawMessage)
            .mapNotNull { it.groups[1] }
            .firstOrNull { free(it.range.first, it.range.last + 1) }
            ?.let { claim(it.range.first, it.range.last + 1, REF_SLOT) }

        BALANCE_REGEX.findAll(rawMessage)
            .mapNotNull { it.groups[1] }
            .firstOrNull { free(it.range.first, it.range.last + 1) }
            ?.let { claim(it.range.first, it.range.last + 1, BAL_SLOT) }

        payeeSpan(rawMessage, payee, direction, ::free)?.let { claim(it.first, it.last + 1, PAYEE) }

        spans.sortBy { it.start }
        val (patternString, fixedLetters) = build(rawMessage, spans)
        if (fixedLetters < MIN_FIXED_LETTERS) return null
        runCatching { Regex(patternString, RegexOption.IGNORE_CASE) }.getOrNull() ?: return null

        val learned = LearnedPattern(
            id = "$ID_PREFIX$bank.${Math.abs(patternString.hashCode())}",
            bank = bank,
            regex = patternString,
            direction = direction,
            accountToken = observedToken,
            isCard = account.kind == AccountKind.CREDIT_CARD,
            createdAt = System.currentTimeMillis(),
        )

        // The template has to read back the message it came from, exactly as it was filed.
        // If it cannot, it is wrong about this bank's sentence, and a wrong template books
        // wrong rows unattended - so nothing is learned rather than something approximate.
        val parsed = applySpec(learned.toPatternSpec(), rawMessage, System.currentTimeMillis())
        val faithful = parsed != null &&
            parsed.amountPaise == amountPaise &&
            parsed.direction == direction &&
            parsed.accountToken == observedToken
        return learned.takeIf { faithful }
    }

    /**
     * Where the amount stands. Of several numbers with the same value, the one with a
     * currency in front of it and no balance label wins: "Credited Rs 1000 ... Avl Bal Rs
     * 1000" must slot the first, or the template reads the balance as the amount forever.
     */
    private fun findAmount(raw: String, paise: Long): IntRange? {
        val hits = NUMBER.findAll(raw).filter { parseAmountToPaise(it.value) == paise }.toList()
        fun before(m: MatchResult, n: Int) = raw.substring(maxOf(0, m.range.first - n), m.range.first)
        val notBalance = hits.filterNot { BALANCE_BEFORE.containsMatchIn(before(it, 25)) }
        return (notBalance.firstOrNull { CURRENCY_BEFORE.containsMatchIn(before(it, 8)) }
            ?: notBalance.firstOrNull()
            ?: hits.firstOrNull())?.range
    }

    /**
     * Where the account number stands, mask included.
     *
     * The account's own digits are preferred, but only where something marks them as an
     * account: Union ends its debits "SMS BLOCK 2209 to ...", and the first "2209" in a
     * message is not always the one after "A/c". Failing that - an account you added by
     * hand has no digits yet, and a debit card prints different ones from the account it
     * draws on - whatever the message itself marks as an account is used.
     */
    private fun findAccount(raw: String, token: String, free: (Int, Int) -> Boolean): IntRange? {
        fun marked(r: IntRange) =
            free(r.first, r.last + 1) &&
                ACCOUNT_BEFORE.containsMatchIn(raw.substring(maxOf(0, r.first - 20), r.first))

        val digits = token.filter(Char::isDigit)
        val own = if (digits.length >= 3) {
            Regex("""(?<!\d)$digits(?!\d)""").findAll(raw).map { it.range }.firstOrNull(::marked)
        } else {
            null
        }
        val found = own ?: STANDALONE_DIGITS.findAll(raw).map { it.range }.firstOrNull(::marked) ?: return null

        var start = found.first
        while (start > 0 && raw[start - 1] in "Xx*.-" && free(start - 1, found.last + 1)) start--
        return start..found.last
    }

    private fun directionWord(raw: String, direction: Direction, free: (Int, Int) -> Boolean): IntRange? {
        val words = if (direction == Direction.DEBIT) DEBIT_WORDS else CREDIT_WORDS
        for (word in words) {
            Regex("""\b${Regex.escape(word)}\b""", RegexOption.IGNORE_CASE).findAll(raw)
                .map { it.range }
                .firstOrNull { free(it.first, it.last + 1) }
                ?.let { return it }
        }
        return null
    }

    /**
     * The payee you typed, if the message prints it; otherwise whoever the message names.
     *
     * The fallback matters. A payee corrected by hand - "AONE TRA" typed as "Aone Traders" -
     * is not in the message, and leaving the printed name as fixed text tied the template
     * to that one shop, so the next payment anywhere else came back to the tray.
     */
    private fun payeeSpan(raw: String, typed: String?, direction: Direction, free: (Int, Int) -> Boolean): IntRange? {
        val candidates = listOfNotNull(
            typed?.trim()?.takeIf { it.isNotEmpty() },
            extractGenericPayee(raw, direction),
        )
        for (candidate in candidates) {
            var index = raw.indexOf(candidate, ignoreCase = true)
            while (index >= 0) {
                if (free(index, index + candidate.length)) return index until index + candidate.length
                index = raw.indexOf(candidate, index + 1, ignoreCase = true)
            }
        }
        return null
    }

    /** The regex, and how many letters of fixed text it holds. */
    private fun build(raw: String, spans: List<Span>): Pair<String, Int> {
        val sb = StringBuilder()
        var letters = 0
        fun fixed(text: String) {
            letters += text.count(Char::isLetter)
            sb.append(literal(text))
        }

        fixed(raw.substring(0, spans.first().start).split(WHITESPACE).filter { it.isNotEmpty() }
            .takeLast(HEAD_WORDS).joinToString(" "))
        spans.forEachIndexed { i, span ->
            if (i > 0) fixed(raw.substring(spans[i - 1].end, span.start))
            sb.append(
                if (span.slot != PAYEE) {
                    span.slot
                } else {
                    // Bounded by whatever fixed text follows when there is some, so a payee
                    // may hold dots ("MR. A.B. SHARMA & CO."); otherwise it runs to the
                    // first punctuation, since nothing after the last slot is kept.
                    val next = spans.getOrNull(i + 1)?.let { raw.substring(span.end, it.start) }
                    if (next != null && next.any(Char::isLetter)) """(?<payee>.+?)""" else """(?<payee>[^.;,\n\r]+)"""
                },
            )
        }
        return sb.toString() to letters
    }

    /** Fixed text as a regex: words exact, digits any, whitespace optional at the edges. */
    private fun literal(text: String): String {
        val words = text.split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return if (text.isEmpty()) "" else """\s*"""
        return """\s*""" + words.joinToString("""\s+""", transform = ::word) + """\s*"""
    }

    private fun word(word: String): String {
        val sb = StringBuilder()
        var cursor = 0
        for (m in DIGIT_RUN.findAll(word)) {
            if (m.range.first > cursor) sb.append(Regex.escape(word.substring(cursor, m.range.first)))
            sb.append("""\d+""")
            cursor = m.range.last + 1
        }
        if (cursor < word.length) sb.append(Regex.escape(word.substring(cursor)))
        return sb.toString()
    }
}
