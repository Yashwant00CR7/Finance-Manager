package com.yk.finance.domain

import com.yk.finance.data.Account
import com.yk.finance.data.AccountKind
import com.yk.finance.data.LearnedPattern
import com.yk.finance.parser.Direction
import com.yk.finance.parser.PatternSpec
import com.yk.finance.parser.Tier
import com.yk.finance.parser.applySpec
import com.yk.finance.parser.parseAmountToPaise

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
 */
object TemplateLearner {

    private val DATE_REGEX = Regex(
        """\b(?:\d{1,2}[-/.][A-Za-z0-9]{2,3}[-/.]\d{2,4}|\d{1,2}[A-Za-z]{3}\d{2,4})\b""",
    )

    private val TIME_REGEX = Regex(
        """\b\d{1,2}:\d{2}(?::\d{2})?(?:\s*[AaPp][Mm])?\b""",
    )

    private val REF_PATTERNS = listOf(
        Regex("""\b(?:UPI\s*Ref(?:erence)?\s*(?:No\.?)?|UPI|RRN|UTR|ref(?:erence)?\s*(?:no\.?|id|#)?|(?:txn|tran)\s*(?:id|no\.?)?)[:\s.-]*([A-Z0-9]{6,22})\b""", RegexOption.IGNORE_CASE),
    )

    private val BALANCE_REGEX = Regex(
        """\b(?:Avl|Avb|Avbl|Avlbl|Avail|Available|Clear|Clr|Current|Final|Total|New)?\s*Bal(?:ance)?(?:\s+is)?[-:\s]*(?:Rs|INR|₹)?\.?:?\s*(\d+(?:,\d+)*(?:\.\d{1,2})?)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val DIRECTION_KEYWORDS_DEBIT = listOf("debited", "debit", "spent", "paid", "withdrawn", "deducted", "sent", "dr")
    private val DIRECTION_KEYWORDS_CREDIT = listOf("credited", "credit", "received", "deposited", "cr", "refund")

    private val DISCLAIMER_REGEX = Regex(
        """(?i)(?:[.,;\s]+(?:Not\s+you\??|If\s+not\s+you|Never\s+Share|Call\s+\d+|SMS\s+BLOCK|Helpline|Report|For\s+dispute|Dial\b).*)$"""
    )

    private data class Span(val start: Int, val end: Int, val replacement: String)

    fun learn(
        rawMessage: String,
        amountPaise: Long,
        direction: Direction,
        account: Account,
        payee: String?,
    ): LearnedPattern? {
        val spans = mutableListOf<Span>()

        // 1. Locate Amount (Required)
        val amountSpan = findAmountSpan(rawMessage, amountPaise) ?: return null
        spans.add(Span(amountSpan.first, amountSpan.second, """(?<amount>\d+(?:,\d+)*(?:\.\d{1,2})?)"""))

        // 2. Locate Direction slot if keyword is present
        val dirKeywords = if (direction == Direction.DEBIT) DIRECTION_KEYWORDS_DEBIT else DIRECTION_KEYWORDS_CREDIT
        for (kw in dirKeywords) {
            val kwRegex = Regex("""\b${Regex.escape(kw)}\b""", RegexOption.IGNORE_CASE)
            val m = kwRegex.find(rawMessage)
            if (m != null && !overlaps(m.range.first, m.range.last + 1, spans)) {
                spans.add(
                    Span(
                        m.range.first,
                        m.range.last + 1,
                        """(?<dir>debited|credited|debit|credit|spent|received|withdrawn|deposited|deducted|paid|sent)""",
                    ),
                )
                break
            }
        }

        // 3. Locate Account token digits (Required)
        val acctSpan = findAccountSpan(rawMessage, account.accountToken)
        if (acctSpan != null) {
            spans.add(acctSpan)
        }

        // 4. Locate Date
        val dateMatch = DATE_REGEX.find(rawMessage)
        if (dateMatch != null && !overlaps(dateMatch.range.first, dateMatch.range.last + 1, spans)) {
            spans.add(Span(dateMatch.range.first, dateMatch.range.last + 1, """(?<date>\d{1,2}[-/.A-Za-z0-9]{2,7}\d{2,4})"""))
        }

        // 5. Locate Time
        val timeMatch = TIME_REGEX.find(rawMessage)
        if (timeMatch != null && !overlaps(timeMatch.range.first, timeMatch.range.last + 1, spans)) {
            spans.add(Span(timeMatch.range.first, timeMatch.range.last + 1, """(?<time>\d{1,2}:\d{2}(?::\d{2})?(?:\s*[AaPp][Mm])?)"""))
        }

        // 6. Locate Reference / UTR / Txn ID
        for (pattern in REF_PATTERNS) {
            val match = pattern.find(rawMessage)
            if (match != null && match.groups.size > 1) {
                val group = match.groups[1]
                if (group != null && !overlaps(group.range.first, group.range.last + 1, spans)) {
                    spans.add(Span(group.range.first, group.range.last + 1, """(?<ref>[A-Z0-9]{6,22})"""))
                    break
                }
            }
        }

        // 7. Locate Balance
        val balMatch = BALANCE_REGEX.find(rawMessage)
        if (balMatch != null && balMatch.groups.size > 1) {
            val group = balMatch.groups[1]
            if (group != null && !overlaps(group.range.first, group.range.last + 1, spans)) {
                spans.add(Span(group.range.first, group.range.last + 1, """(?<bal>\d+(?:,\d+)*(?:\.\d{1,2})?)"""))
            }
        }

        // 8. Locate Payee if provided and present in the message
        if (!payee.isNullOrBlank()) {
            val payeeTrim = payee.trim()
            val payeeIdx = rawMessage.indexOf(payeeTrim, ignoreCase = true)
            if (payeeIdx != -1 && !overlaps(payeeIdx, payeeIdx + payeeTrim.length, spans)) {
                spans.add(Span(payeeIdx, payeeIdx + payeeTrim.length, """(?<payee>[^.;,\n\r]+)"""))
            }
        }

        // Sort spans by start index
        spans.sortBy { it.start }

        val sb = StringBuilder()
        var cursor = 0
        for (span in spans) {
            if (span.start > cursor) {
                val literal = rawMessage.substring(cursor, span.start)
                sb.append(escapeLiteralToRegex(literal))
            }
            sb.append(span.replacement)
            cursor = span.end
        }
        if (cursor < rawMessage.length) {
            val literal = rawMessage.substring(cursor)
            val disclaimerMatch = DISCLAIMER_REGEX.find(literal)
            if (disclaimerMatch != null) {
                val cleanLiteral = literal.substring(0, disclaimerMatch.range.first)
                sb.append(escapeLiteralToRegex(cleanLiteral))
                sb.append("""(?:\s*.*)?""")
            } else {
                sb.append(escapeLiteralToRegex(literal))
            }
        }

        val patternString = sb.toString()
        runCatching { Regex(patternString, RegexOption.IGNORE_CASE) }.getOrNull() ?: return null

        val patternId = "LEARNED.${account.bank}.${Math.abs(patternString.hashCode())}"
        val learnedPattern = LearnedPattern(
            id = patternId,
            bank = account.bank,
            regex = patternString,
            direction = direction,
            accountToken = account.accountToken,
            isCard = account.kind == AccountKind.CREDIT_CARD,
            createdAt = System.currentTimeMillis(),
        )

        // Validate that this pattern successfully matches and parses the original message
        val spec = learnedPattern.toPatternSpec()
        val parsed = applySpec(spec, rawMessage, System.currentTimeMillis(), bank = account.bank)
        if (parsed == null || parsed.amountPaise != amountPaise) {
            return buildRelaxedPattern(rawMessage, amountPaise, direction, account)
        }

        return learnedPattern
    }

    private fun overlaps(start: Int, end: Int, existing: List<Span>): Boolean {
        return existing.any { span -> maxOf(start, span.start) < minOf(end, span.end) }
    }

    private fun findAmountSpan(rawMessage: String, amountPaise: Long): Pair<Int, Int>? {
        val numRegex = Regex("""\b\d+(?:,\d+)*(?:\.\d{1,2})?\b""")
        for (m in numRegex.findAll(rawMessage)) {
            val paise = parseAmountToPaise(m.value)
            if (paise == amountPaise) {
                return Pair(m.range.first, m.range.last + 1)
            }
        }
        return null
    }

    private fun findAccountSpan(rawMessage: String, accountToken: String): Span? {
        val cleanToken = accountToken.filter { it.isDigit() }
        if (cleanToken.length < 3) return null
        val idx = rawMessage.indexOf(cleanToken)
        if (idx == -1) return null
        var start = idx
        while (start > 0 && rawMessage[start - 1] in setOf('X', 'x', '*', '.', '-')) {
            start--
        }
        return Span(start, idx + cleanToken.length, """[Xx*.-]*(?<acct>\d{3,6})""")
    }

    private fun escapeLiteralToRegex(literal: String): String {
        val words = literal.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.isEmpty()) {
            return if (literal.any { it.isWhitespace() }) """\s+""" else ""
        }
        val escaped = words.joinToString("""\s+""") { Regex.escape(it) }
        val leading = if (literal.firstOrNull()?.isWhitespace() == true) """\s+""" else ""
        val trailing = if (literal.lastOrNull()?.isWhitespace() == true) """\s+""" else ""
        return leading + escaped + trailing
    }

    private fun buildRelaxedPattern(
        rawMessage: String,
        amountPaise: Long,
        direction: Direction,
        account: Account,
    ): LearnedPattern? {
        val cleanToken = account.accountToken.filter { it.isDigit() }
        val acctClause = if (cleanToken.length >= 3 && rawMessage.contains(cleanToken)) {
            """[Xx*.-]*(?<acct>\d{3,6})"""
        } else {
            """(?<acct>${Regex.escape(account.accountToken)})"""
        }
        val words = rawMessage.split(Regex("""\s+"""))
            .filter { it.length > 3 && it.all { c -> c.isLetter() } }
        if (words.size < 2) return null
        val anchors = words.take(3).joinToString("""\s+""") { Regex.escape(it) }

        val amountSpan = findAmountSpan(rawMessage, amountPaise)
        val acctSpan = findAccountSpan(rawMessage, account.accountToken)
        val patternString = if (amountSpan != null && acctSpan != null && amountSpan.first < acctSpan.start) {
            """(?i)$anchors.*?(?<amount>\d+(?:,\d+)*(?:\.\d{1,2})?).*?$acctClause"""
        } else {
            """(?i)$anchors.*?$acctClause.*?(?<amount>\d+(?:,\d+)*(?:\.\d{1,2})?)"""
        }

        val patternId = "LEARNED.${account.bank}.${Math.abs(patternString.hashCode())}"
        val pattern = LearnedPattern(
            id = patternId,
            bank = account.bank,
            regex = patternString,
            direction = direction,
            accountToken = account.accountToken,
            isCard = account.kind == AccountKind.CREDIT_CARD,
            createdAt = System.currentTimeMillis(),
        )
        val spec = pattern.toPatternSpec()
        val parsed = applySpec(spec, rawMessage, System.currentTimeMillis(), bank = account.bank)
        return if (parsed != null && parsed.amountPaise == amountPaise) pattern else null
    }
}
