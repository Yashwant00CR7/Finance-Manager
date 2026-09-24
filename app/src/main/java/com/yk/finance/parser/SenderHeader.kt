package com.yk.finance.parser

/**
 * A personal 10-digit mobile number as sender. Banks in India send via DLT headers
 * ("VM-ICICIB", "AD-UNIONB"), never from a personal number - so a transaction-shaped
 * message from a bare mobile number is a scam, not a transaction.
 */
private val PERSONAL_NUMBER = Regex("""^\+?\d{10,13}$""")

/**
 * Could this sender be a bank at all?
 *
 * A shape test, not an identity test - it knows one sender from no sender, never one
 * sender from another. Two callers rely on it and for different reasons: the parser
 * rejects unvouched senders with it, and the sender registry uses it to decide what is
 * even allowed into the seen list, so that list never becomes a log of everyone who
 * texts you.
 */
fun isSenderHeader(sender: String): Boolean {
    val s = sender.trim()
    if (s.isEmpty()) return false
    if (PERSONAL_NUMBER.matches(s.replace(" ", ""))) return false
    return s.any { it.isLetter() }
}

/**
 * The identity of an SMS conversation, as far as this app is concerned.
 *
 * SMS has no per-conversation permission the way apps have per-app ones: every message
 * arrives at one receiver carrying one string. This object turns that string into a
 * stable name, which is the whole basis of the sender registry.
 *
 * Indian bank SMS arrives through DLT shaped as `PREFIX-HEADER-SUFFIX`, and only the
 * middle is the bank:
 *
 *   AD-ICICIT-S     AX-ICICIT-S     JK-UNIONB-S     JX-UNIONB-T
 *
 *  - PREFIX is operator and circle, stamped on by whichever network routed the
 *    message. The same alert arrives as AD-ICICIT-S one day and AX-ICICIT-S the next,
 *    and Android files each as its own conversation in the messaging app.
 *  - SUFFIX is the DLT content category - S, T, P, G - a single letter saying what
 *    kind of traffic the header carries, not who sent it.
 *  - HEADER is the registered entity. It alone is stable, and it alone is stored.
 *
 * Both ends must go. Taking everything after the final hyphen - which is what this
 * did first - collapses every sender on the phone onto "S" or "T", so ICICI and Union
 * share one entry and the allowlist becomes meaningless while still looking correct.
 * Taking a fixed two characters off the front is equally wrong: UNIB- is four.
 *
 * So the rule is positional rather than measured. Drop a trailing single character,
 * drop a leading segment if anything remains, keep the middle. A bare header that was
 * typed by hand is already normal and survives untouched, which is what lets the manual
 * field accept either "AD-ICICIT-S" or "ICICIT" and mean the same thing.
 */
object SenderHeader {

    fun normalize(sender: String?): String {
        val segments = sender?.trim().orEmpty()
            .split('-')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (segments.isEmpty()) return ""

        // The content-category suffix. Length rather than a letter list, so a category
        // this app has never seen is still recognised as one.
        val withoutSuffix =
            if (segments.size > 1 && segments.last().length == 1) segments.dropLast(1)
            else segments

        // The operator prefix, whatever its length - but only when something is left
        // after it, so a header typed on its own is never eaten.
        val header = if (withoutSuffix.size > 1) withoutSuffix.drop(1) else withoutSuffix

        return header.joinToString("-").uppercase()
    }
}
