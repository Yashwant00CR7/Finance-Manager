package com.yk.finance.parser

private val ATM_HINTS = listOf(
    Regex("""\bATM\b""", RegexOption.IGNORE_CASE),
    Regex("""CASH\s*WDL""", RegexOption.IGNORE_CASE),
    Regex("""\bWDL\b""", RegexOption.IGNORE_CASE),
    Regex("""CASH\s+WITHDRAWAL""", RegexOption.IGNORE_CASE),
    Regex("""\bATW\b""", RegexOption.IGNORE_CASE),
    Regex("""Cash\s+Acceptor\s+Machine""", RegexOption.IGNORE_CASE),
    Regex("""cash\s+deposit\s+machine""", RegexOption.IGNORE_CASE),
)

/**
 * Channel is detected from keywords anywhere in the body, deliberately decoupled
 * from the sentence layout. We have no real ATM sample, so the ATM *layout* is a
 * guess - but "ATM" or "WDL" appearing in a withdrawal message is not. Getting the
 * channel right is what matters, because ATM debits become transfers, not spending.
 */
fun detectChannel(body: String): Channel {
    // ATM first: a withdrawal message often also mentions the card.
    if (ATM_HINTS.any { it.containsMatchIn(body) }) return Channel.ATM
    return when {
        Regex("""(\bUPI\b|\bVPA\b|\bRRN\b)""", RegexOption.IGNORE_CASE).containsMatchIn(body) -> Channel.UPI
        Regex("""(Mob\s*Bk|IMPS)""", RegexOption.IGNORE_CASE).containsMatchIn(body) -> Channel.MOBILE_BANKING
        Regex("""(Net\s*Bk|\bINB\b|Internet Bank|NetBanking|\bNEFT\b|\bRTGS\b|FEDNET)""", RegexOption.IGNORE_CASE).containsMatchIn(body) -> Channel.NET_BANKING
        Regex("""(\bPOS\b|swipe|card ending|ending with|Credit Card|Debit Card|Card no)""", RegexOption.IGNORE_CASE).containsMatchIn(body) -> Channel.POS
        else -> Channel.UNKNOWN
    }
}

/**
 * Phrases that mean "no money has moved yet" even when the sentence is otherwise
 * shaped exactly like a completed transaction.
 *
 * Deliberately short. We do NOT blocklist "OTP" - real Union Bank messages end with
 * "Never Share OTP/PIN/CVV", so that would reject every genuine Union transaction.
 * Nor "If not you, Call ..." - that trailer is present in real Union debits.
 * Junk is excluded by the structural allowlist in the bank rules instead: an OTP
 * message simply never matches "A/c *NNNN Debited for Rs:...".
 *
 * ## On "cashback"
 *
 * This list used to reject any message containing the word, which silently dropped real
 * money: "Cashback of Rs.50.00 has been sent to your Kotak Bank A/c x5555. Credited on
 * 14-10-25." is a genuine credit, and it never reached the ledger or even the tray. The
 * entries below are anchored to the *offer* grammar - "get Rs N cashback", "earn cashback" -
 * so a cashback that has already been paid is booked and one that is merely dangled is not.
 */
private val NOT_YET_HAPPENED = listOf(
    Regex("""will\s+be\s+(debited|credited|deducted)""", RegexOption.IGNORE_CASE),
    Regex("""will\s+get\s+(debited|credited)""", RegexOption.IGNORE_CASE),
    Regex("""to\s+be\s+(debited|credited|deducted)""", RegexOption.IGNORE_CASE),
    Regex("""(standing instruction|e-?mandate|auto\s*pay)\s+.{0,30}(registered|set up|scheduled)""", RegexOption.IGNORE_CASE),
    Regex("""(spend|shop)\s+(Rs|INR|₹)""", RegexOption.IGNORE_CASE),

    // Offers, not payments. Anchored so a paid cashback still books - see the note above.
    Regex("""(get|earn|win|upto|up\s+to|flat)\s+[^.]{0,20}cashback""", RegexOption.IGNORE_CASE),
    Regex("""cashback\s+(offer|of\s+up)""", RegexOption.IGNORE_CASE),

    Regex("""payment\s+request\s+of""", RegexOption.IGNORE_CASE),

    // A mandate being created names a ceiling, not a charge: "for a maximum amount of Rs 89.00".
    Regex("""created\s+a\s+mandate""", RegexOption.IGNORE_CASE),
    Regex("""maximum\s+amount\s+of""", RegexOption.IGNORE_CASE),
    Regex("""mandate\s+.{0,40}received\s+today\s+for\s+processing""", RegexOption.IGNORE_CASE),

    // Money set aside for a share application is blocked, not spent.
    Regex("""is\s+blocked\s+in\s+your""", RegexOption.IGNORE_CASE),
    Regex("""\bASBA\b""", RegexOption.IGNORE_CASE),

    // Nothing moved.
    Regex("""(was|is)\s+declined""", RegexOption.IGNORE_CASE),
    Regex("""(txn|transaction)\s+.{0,40}failed""", RegexOption.IGNORE_CASE),
    Regex("""insufficient\s+funds""", RegexOption.IGNORE_CASE),

    // An OTP that quotes the amount it is authorising. RBL sends one ~25 seconds before the
    // matching spend alert, so booking it would double every card purchase.
    Regex("""\bis\s+(the\s+)?OTP\s+for""", RegexOption.IGNORE_CASE),
    Regex("""\bOTP\s+for\s+(txn|transaction|purchase)""", RegexOption.IGNORE_CASE),
    Regex("""(?:is\s+)?(?:the\s+)?one\s+time\s+password\s*\(?OTP\)?\s+for""", RegexOption.IGNORE_CASE),

    // Phishing / smishing impersonating Indian bank reward-points cash redemption
    Regex("""points\s+worth\s+(?:Rs|INR|₹)[^.]{0,30}will\s+expire""", RegexOption.IGNORE_CASE),
    Regex("""redeem\s+(?:your\s+)?points\s+in\s+cash""", RegexOption.IGNORE_CASE),

    // Inquiries and applications, not transactions
    Regex("""received\s+your\s+(?:personal\s+)?loan\s+request""", RegexOption.IGNORE_CASE),

    // The counterparty's copy of a transfer we have already recorded from our own side.
    // IDFC and Federal both send one minutes after the debit, carrying the same reference.
    Regex("""beneficiary\s+has\s+received""", RegexOption.IGNORE_CASE),
    Regex("""credited\s+to\s+the\s+beneficiary""", RegexOption.IGNORE_CASE),
    Regex("""has\s+received\s+(Rs|INR|₹)[^.]{0,20}from\s+your\s+A/c""", RegexOption.IGNORE_CASE),
)

/**
 * Phrases that announce something still to come, but which banks also tack onto the end of
 * a message reporting money that has already moved:
 *
 *   "Rs 5000 debited from A/c *2209 towards EMI on 05-10-2026. Next EMI is due on 05-11-2026."
 *
 * These used to sit in [NOT_YET_HAPPENED] and took the debit down with the reminder - a
 * real payment, dropped without reaching even the tray. They now reject a message only when
 * it does not also report a completed movement against an account.
 */
private val REMINDERS = listOf(
    Regex("""(is|are)\s+due\s+(on|by)""", RegexOption.IGNORE_CASE),
    Regex("""due\s+for\s+payment""", RegexOption.IGNORE_CASE),
    Regex("""scheduled\s+(on|for)""", RegexOption.IGNORE_CASE),
    Regex("""(EMI|instal?ment)\s+of\s+[^.]{0,30}\s+is\s+due""", RegexOption.IGNORE_CASE),
    Regex("""(minimum|min)\s+(amount\s+)?due""", RegexOption.IGNORE_CASE),
    Regex("""total\s+amount\s+due""", RegexOption.IGNORE_CASE),
    Regex("""payment\s+(is\s+)?overdue""", RegexOption.IGNORE_CASE),
    Regex("""bill\s+(alert|generated|of\s+[^.]{0,20}is\s+due)""", RegexOption.IGNORE_CASE),
    Regex("""statement\s+(has\s+been\s+)?generated""", RegexOption.IGNORE_CASE),
)

private const val ACCOUNT_WORD = """(?:A/c|Acct|Acc|Account|Card)"""

/**
 * Money that has already moved, said against an account rather than about one.
 *
 * Both halves bind a past-tense verb to the account clause - "debited from A/c", "A/c *2209
 * Debited" - which is what no reminder, mandate or offer does. "will be debited from your
 * A/c" is in [NOT_YET_HAPPENED] and is rejected before this is ever consulted.
 */
private val COMPLETED_MOVEMENT = listOf(
    Regex(
        """\b(?:debited|credited|withdrawn|deducted|deposited|spent)\s+(?:(?:from|to|in|into|on|using)\s+)?""" +
            """(?:your\s+|ur\s+)?(?:[A-Za-z]+\s+){0,4}?$ACCOUNT_WORD""",
        RegexOption.IGNORE_CASE,
    ),
    Regex(
        """\b$ACCOUNT_WORD\s*(?:no\.?\s*)?[Xx*.]*\d{3,}[Xx*]*\s+(?:is\s+|has\s+been\s+|was\s+)?(?:debited|credited)\b""",
        RegexOption.IGNORE_CASE,
    ),
)

fun describesFutureOrOffer(body: String): String? {
    NOT_YET_HAPPENED.firstOrNull { it.containsMatchIn(body) }
        ?.let { return "future/promotional: ${it.pattern}" }
    if (COMPLETED_MOVEMENT.any { it.containsMatchIn(body) }) return null
    return REMINDERS.firstOrNull { it.containsMatchIn(body) }?.let { "future/promotional: ${it.pattern}" }
}

private val AMOUNT_ANYWHERE = Regex("""(?:Rs|INR|₹)\.?:?\s*[0-9][0-9,]*(?:\.\d{1,2})?""", RegexOption.IGNORE_CASE)

/**
 * An amount with no currency in front of it, which is how SBI prints every UPI alert:
 * "A/C X1234 debited by 50.0". Accepted only straight after the verb, where nothing but
 * an amount ever stands.
 */
private val BARE_AMOUNT = Regex("""\b(?:debited|credited)\s+(?:by|for|with)\s+[0-9][0-9,]*(?:\.\d{1,2})?\b""", RegexOption.IGNORE_CASE)

private val DIRECTION_ANYWHERE = Regex(
    """(debit|credit|withdraw|spent|paid|sent|deposited|transferred|received|deducted|charged|purchase|refund|\bDr\b|\bCr\b)""",
    RegexOption.IGNORE_CASE,
)

/**
 * A masked account or card number: "A/c *2209", "Acct XX742", "A/cXX5468", "Card ending
 * 1234", a bare "XXXXXX1234". The digits are the first non-null group.
 */
private val ACCOUNT_MASK = Regex(
    """\b(?:A/c|Acct|Acc|Account|AC|Card)\s*(?:no\.?\s*)?(?:ending\s+(?:with\s+)?)?[Xx*.]*(\d{3,})|[Xx*]{2,}(\d{3,})""",
    RegexOption.IGNORE_CASE,
)

/**
 * Gate for the review tray. A message reaches it when it looks financial and either names
 * a bank we know or names an account of yours.
 *
 * The second half is new, and it is the one that matters for anyone banking somewhere this
 * app has no header for. Requiring a known bank used to drop every co-operative and regional
 * bank alert without trace - not a wrong row, no row, and no tray entry to learn from. An
 * OTP or a delivery notice carries no masked account number, so this still keeps those out.
 */
fun looksFinancial(body: String, bankKnown: Boolean): Boolean {
    val hasAmount = AMOUNT_ANYWHERE.containsMatchIn(body) || BARE_AMOUNT.containsMatchIn(body)
    if (!hasAmount || !DIRECTION_ANYWHERE.containsMatchIn(body)) return false
    return bankKnown || ACCOUNT_MASK.containsMatchIn(body)
}

/** The masked account digits a message prints, in the order it prints them. */
fun accountTokensIn(body: String): List<String> =
    ACCOUNT_MASK.findAll(body)
        .mapNotNull { m -> m.groups[1]?.value ?: m.groups[2]?.value }
        .distinct()
        .toList()

data class PreFillValues(
    val amountRupees: String = "",
    val isDebit: Boolean = true,
    val payee: String = "",
)

private val CREDIT_SIGNAL = Regex("""\b(credited|credit|received|refund|deposited|Cr)\b""", RegexOption.IGNORE_CASE)
private val DEBIT_SIGNAL = Regex("""\b(debited|debit|spent|paid|withdrawn|sent|deducted|Dr)\b""", RegexOption.IGNORE_CASE)
private val CURRENCY_AMOUNT = Regex("""(?:Rs|INR|₹)\.?:?\s*([0-9][0-9,]*(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
private val BARE_AMOUNT_VALUE = Regex("""\b(?:debited|credited)\s+(?:by|for|with)\s+([0-9][0-9,]*(?:\.\d{1,2})?)\b""", RegexOption.IGNORE_CASE)
private val BAL_PREFIX = Regex("""(?:Avl|Avb|Avbl|Available|Clear|Current|Final|Total)?\s*Bal(?:ance)?[:\s-]*$""", RegexOption.IGNORE_CASE)

fun extractPreFill(body: String): PreFillValues {
    val isCredit = CREDIT_SIGNAL.containsMatchIn(body)
    val isDebit = DEBIT_SIGNAL.containsMatchIn(body)
    val debitFlag = if (isCredit && !isDebit) false else true

    var amount = ""
    for (m in CURRENCY_AMOUNT.findAll(body)) {
        val textBefore = body.substring(0, m.range.first)
        if (!BAL_PREFIX.containsMatchIn(textBefore.takeLast(25))) {
            amount = m.groupValues[1].replace(",", "")
            break
        }
    }
    if (amount.isEmpty()) {
        amount = BARE_AMOUNT_VALUE.find(body)?.groupValues?.get(1)?.replace(",", "").orEmpty()
    }

    val payee = extractGenericPayee(body, if (debitFlag) Direction.DEBIT else Direction.CREDIT) ?: ""

    return PreFillValues(
        amountRupees = amount,
        isDebit = debitFlag,
        payee = payee,
    )
}

