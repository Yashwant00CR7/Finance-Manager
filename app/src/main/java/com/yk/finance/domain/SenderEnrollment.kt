package com.yk.finance.domain

import com.yk.finance.data.FinanceDao
import com.yk.finance.data.GateMode
import com.yk.finance.data.GateState
import com.yk.finance.data.SenderEntry
import com.yk.finance.data.SenderState
import com.yk.finance.parser.SenderHeader
import com.yk.finance.parser.ruleForHeader

/**
 * Who is allowed to reach the ledger, and how that list gets its first entries.
 *
 * The bootstrapping problem this solves is created by a decision made elsewhere: the
 * app holds RECEIVE_SMS and not READ_SMS, so it cannot enumerate the conversations
 * already on the phone. There is no picker to build. A sender can only become known by
 * sending something *after* this feature exists, which means the list starts out
 * almost empty and has to be safe while it fills.
 *
 * Hence [seed] plus OBSERVE mode rather than seed alone: the two headers below are the
 * ones the parser was verified against, but whether they are the headers *this* phone
 * actually receives is not checkable from inside the app.
 */
object SenderEnrollment {

    /**
     * The headers confirmed to be on this phone, normalised.
     *
     * Read off the real senders - AD-ICICIT-S, AX-ICICIT-S, JK-UNIONB-S, JX-UNIONB-T -
     * rather than from the parser's test fixtures, which carried VM-ICICIB and turned
     * out to be a placeholder nobody had checked. Seeding the wrong header does not
     * fail loudly: it enrols a sender that never writes, which looks exactly like
     * working right up until you notice the ledger stopped filling.
     *
     * Deliberately only these two. Every other header is a guess, and the seen list is
     * the honest way to learn the rest.
     */
    private val SEED = mapOf(
        "ICICIT" to "ICICI",
        "UNIONB" to "UNION",
    )

    /**
     * Idempotent, and runs on every launch beside CategorySync rather than inside a
     * migration - so it covers fresh installs too.
     *
     * The load-bearing line is the null check: a header already in the table is left
     * exactly as it is. Without it, un-enrolling ICICIB would be undone by the next
     * launch and there would be no way to turn a seeded sender off.
     */
    suspend fun seed(dao: FinanceDao, now: Long = System.currentTimeMillis()) {
        if (dao.gateState() == null) dao.upsertGate(GateState(mode = GateMode.OBSERVE))
        SEED.forEach { (header, bank) ->
            if (dao.senderByHeader(header) != null) return@forEach
            dao.upsertSender(
                SenderEntry(
                    header = header,
                    state = SenderState.ENROLLED,
                    bankKey = bank,
                    firstSeenAt = now,
                    lastSeenAt = now,
                ),
            )
        }
    }

    /**
     * Opt a conversation in.
     *
     * [bankKey] null is a real and useful answer: it enrols a sender whose bank has no
     * rule written yet, whose messages then reach the review tray instead of being
     * dropped. That is how a new bank gets tracked before anyone writes a parser for it.
     *
     * Typed headers are normalised on the way in, so pasting "VM-ICICIB" out of the
     * messaging app produces the same entry as typing "ICICIB" - which is the whole
     * point of normalising at all.
     */
    suspend fun enroll(
        dao: FinanceDao,
        rawHeader: String,
        bankKey: String?,
        now: Long = System.currentTimeMillis(),
    ): String? {
        val header = SenderHeader.normalize(rawHeader)
        if (header.isEmpty()) return null
        val existing = dao.senderByHeader(header)
        if (existing == null) {
            dao.upsertSender(
                SenderEntry(
                    header = header,
                    state = SenderState.ENROLLED,
                    bankKey = bankKey ?: ruleForHeader(header)?.bank,
                    firstSeenAt = now,
                    lastSeenAt = now,
                ),
            )
        } else {
            dao.setSenderState(header, SenderState.ENROLLED, bankKey ?: existing.bankKey ?: ruleForHeader(header)?.bank)
        }
        return header
    }

    /**
     * Stop ingesting a sender, and keep it visible.
     *
     * Back to UNKNOWN rather than DISMISSED on purpose: un-enrolling a bank is usually
     * a mistake, and a mistake you can see in the list is one you can undo. Sending it
     * to DISMISSED would hide it, and the only symptom would be transactions quietly
     * ceasing to appear.
     *
     * History is never touched. The rows it already produced stay in the ledger.
     */
    suspend fun unenroll(dao: FinanceDao, header: String) =
        dao.setSenderState(header, SenderState.UNKNOWN, dao.senderByHeader(header)?.bankKey)

    /** "This is not a bank." Stays counted, never offered again. */
    suspend fun dismiss(dao: FinanceDao, header: String) =
        dao.setSenderState(header, SenderState.DISMISSED, null)

    suspend fun setMode(dao: FinanceDao, mode: GateMode) =
        dao.upsertGate(GateState(mode = mode))
}
