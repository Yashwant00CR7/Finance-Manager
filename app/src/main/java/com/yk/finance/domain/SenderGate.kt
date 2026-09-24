package com.yk.finance.domain

import com.yk.finance.data.GateMode
import com.yk.finance.data.SenderEntry
import com.yk.finance.data.SenderState
import com.yk.finance.parser.SenderHeader
import com.yk.finance.parser.SenderIdentity
import com.yk.finance.parser.describesFutureOrOffer
import com.yk.finance.parser.isSenderHeader
import com.yk.finance.parser.looksTransactional

/**
 * Whether a message is allowed to reach the ledger at all, and on what evidence.
 *
 * Apps are addressable one at a time - a notification carries the package that posted
 * it, so "only this app" is a thing the platform can express. SMS conversations are
 * not: every message on the phone arrives through one receiver carrying one sender
 * string, and the platform has no notion of granting one conversation and refusing
 * another. This is the app supplying that missing granularity.
 *
 * Pure on purpose. The decision is the part worth being certain about, and a decision
 * with no database in it can be exercised exhaustively on the JVM - which matters more
 * here than anywhere else in the app, because the ACTIVE branch destroys messages.
 */
object SenderGate {

    sealed interface Decision {
        /** Enrolled. The only path on which a message is ever parsed or booked. */
        data class Parse(val identity: SenderIdentity) : Decision

        /** Not parseable, but plainly about money. Kept for you to look at. */
        data class Review(val reason: String) : Decision

        /** Dropped. In ACTIVE this is where an unenrolled sender's message ends. */
        data class Drop(val reason: String) : Decision
    }

    /**
     * What the app is allowed to learn about this message, before deciding anything.
     *
     * [listable] is a shape test on the sender, and it is what stops the seen list
     * becoming a record of everyone who texts you. [transactional] is the single
     * content peek performed on a sender you have not enrolled: it reads the body in
     * memory and yields one boolean, which becomes a counter. No text survives it.
     */
    data class Observation(
        val header: String,
        val listable: Boolean,
        val transactional: Boolean,
    )

    fun observe(sender: String, body: String): Observation {
        val header = SenderHeader.normalize(sender)
        return Observation(
            header = header,
            listable = header.isNotEmpty() && isSenderHeader(sender),
            transactional = looksTransactional(body),
        )
    }

    /**
     * [entry] is the registry row for this sender, or null when there is none.
     *
     * Identity is settled here and by the registry alone. What the body says has no
     * bearing on whose message it is - that separation is the bug this replaces, where
     * a bank rule claimed any message mentioning its name, whoever actually sent it.
     */
    fun decide(
        observation: Observation,
        body: String,
        entry: SenderEntry?,
        mode: GateMode,
    ): Decision {
        if (entry?.state == SenderState.ENROLLED) {
            return Decision.Parse(SenderIdentity(observation.header, entry.bankKey))
        }

        // ACTIVE: the whole decision. Nothing is read beyond the counters, nothing is
        // written, and nothing dropped here can be recovered afterwards.
        if (mode == GateMode.ACTIVE) {
            return Decision.Drop(
                if (observation.listable) "sender '${observation.header}' is not enrolled"
                else "sender is not a bank header",
            )
        }

        // OBSERVE: the gate decides but does not drop. This is what makes it safe to
        // ship an allowlist seeded with two headers that may not be this phone's - a
        // bank we guessed wrong about still surfaces instead of vanishing.
        if (!observation.listable || !observation.transactional) {
            return Decision.Drop("not transaction-shaped")
        }
        describesFutureOrOffer(body)?.let { return Decision.Drop(it) }
        return Decision.Review("sender '${observation.header}' is not enrolled yet - observing")
    }
}
