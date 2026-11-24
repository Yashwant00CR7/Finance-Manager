package com.yk.finance.domain

import com.yk.finance.data.FinanceDao
import com.yk.finance.data.SeenMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/**
 * One SMS as either path delivers it.
 *
 * [sentAt] is the sender-side timestamp: the PDU's timestamp live, the inbox's date_sent
 * on a scan. Both come from the same field the network delivered, which is what lets the
 * two sightings of one message produce the same key. [receivedAt] is when the phone got
 * it, and is what the ledger and the scan window use.
 */
data class InboxSms(
    val sender: String,
    val body: String,
    val sentAt: Long,
    val receivedAt: Long,
)

/** The phone's SMS inbox, newest last. An interface so the scan is testable on the JVM. */
fun interface InboxSource {
    suspend fun messagesSince(fromMillis: Long): List<InboxSms>
}

/** Where the scan keeps its place between runs. Backed by Prefs on the device. */
interface CatchUpState {
    var lastScanAt: Long?
}

/**
 * Identifies a message independent of the path it arrived by.
 *
 * Hashed rather than stored as text: the table exists only to answer "seen before?", and
 * it would otherwise be a second copy of every SMS on the phone, personal ones included.
 */
object MessageKey {
    fun of(sender: String, body: String): String {
        val normalized = sender.trim().uppercase().replace(" ", "") + "\u0000" + body.trim()
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Whole seconds, because the inbox and the radio agree on the second but not always below it. */
    fun sentSeconds(sentAt: Long): Long = if (sentAt > 0) sentAt / 1000 else 0
}

/**
 * Hands each message to the ingestor at most once, whichever path saw it first.
 *
 * The claim is an INSERT that either lands or finds the row already there, so a live
 * broadcast and a scan racing for the same message cannot both win. A claim whose ingest
 * throws is released, so the message is retried rather than lost.
 */
class MessageGate(
    private val dao: FinanceDao,
    private val ingestor: SmsIngestor,
) {
    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }

    /**
     * Null when the message was already handled.
     *
     * [checkLedger] also treats a message as handled when its exact text is already on a
     * ledger row or in the tray. The scan sets it because messages processed before this
     * table existed have no row in it, and the first scan reaches back over some of them.
     */
    suspend fun ingestOnce(msg: InboxSms, checkLedger: Boolean): IngestOutcome? {
        val key = MessageKey.of(msg.sender, msg.body)
        val sent = MessageKey.sentSeconds(msg.sentAt)
        val claim = SeenMessage(bodyKey = key, sentAt = sent, receivedAt = msg.receivedAt)

        // No sender timestamp: the text alone decides, so any earlier sighting counts.
        if (sent == 0L && dao.hasSeenBody(key)) return null

        // The scan also accepts the same text from the same sender within a day as the
        // same message. The exact key relies on the inbox's date_sent equalling the radio's
        // timestamp, and a phone that stores it differently would otherwise send every
        // live message through a second time on the next scan.
        if (checkLedger && dao.hasSeenBodyNear(key, msg.receivedAt - DAY, msg.receivedAt + DAY)) {
            return null
        }

        if (checkLedger && (dao.txnHasRawMessage(msg.body) || dao.reviewHasRawMessage(msg.body))) {
            dao.markSeen(claim)
            return null
        }

        if (dao.markSeen(claim) == -1L) return null

        return try {
            ingestor.ingest(msg.sender, msg.body, msg.receivedAt)
        } catch (t: Throwable) {
            dao.unmarkSeen(key, sent)
            throw t
        }
    }
}

/** What one scan found, for the single summary notification. */
data class CatchUpReport(
    val scanned: Int = 0,
    val alreadySeen: Int = 0,
    val recorded: Int = 0,
    val needCategory: Int = 0,
    val transfers: Int = 0,
    val needReview: Int = 0,
    val duplicates: Int = 0,
    val ignored: Int = 0,
    val failed: Int = 0,
) {
    /** Messages that changed what the app shows: the ones worth telling anyone about. */
    val recovered: Int get() = recorded + transfers + needReview

    val changedLedger: Boolean get() = recorded + transfers > 0
}

/**
 * Reads the inbox for bank messages the live receiver never got.
 *
 * Power-saving modes on several phones stop delivering SMS_RECEIVED to apps that are not
 * on their allowed list. The message still lands in the inbox; only the broadcast is lost.
 * This scan is the recovery: everything in the inbox since the last scan is put through
 * the same [MessageGate] the live receiver uses, so nothing is processed twice.
 *
 * The window: from the last scan, less a small overlap for messages the inbox wrote a
 * moment late. The first scan ever starts at the newest message the app had already
 * processed - its latest SMS transaction or tray entry - less a day, because a ledger time
 * can be a midday guess. It never reaches further back than [firstRunCapMillis].
 */
class CatchUp(
    private val dao: FinanceDao,
    private val gate: MessageGate,
    private val state: CatchUpState,
    private val overlapMillis: Long = 10 * MINUTE,
    private val firstRunCapMillis: Long = 30 * DAY,
    private val firstRunMarginMillis: Long = DAY,
    private val keepSeenMillis: Long = 60 * DAY,
) {
    /** App-open and power-saver-off can fire together; one scan at a time is enough. */
    private val mutex = Mutex()

    suspend fun run(inbox: InboxSource, now: Long): CatchUpReport = mutex.withLock {
        val from = windowStart(now)
        val messages = inbox.messagesSince(from)
        var report = CatchUpReport(scanned = messages.size)
        var earliestFailure: Long? = null

        for (msg in messages) {
            val outcome = try {
                gate.ingestOnce(msg, checkLedger = true)
            } catch (t: Throwable) {
                report = report.copy(failed = report.failed + 1)
                if (earliestFailure == null) earliestFailure = msg.receivedAt
                continue
            }
            report = when (outcome) {
                null -> report.copy(alreadySeen = report.alreadySeen + 1)
                is IngestOutcome.Recorded -> report.copy(
                    recorded = report.recorded + 1,
                    needCategory = report.needCategory + if (outcome.needsCategory) 1 else 0,
                )
                is IngestOutcome.Transfer -> report.copy(transfers = report.transfers + 1)
                is IngestOutcome.DroppedDuplicate -> report.copy(duplicates = report.duplicates + 1)
                IngestOutcome.Queued, is IngestOutcome.AwaitingConfirmation ->
                    report.copy(needReview = report.needReview + 1)
                is IngestOutcome.Ignored -> report.copy(ignored = report.ignored + 1)
            }
        }

        // A message that failed was released by the gate; starting the next scan just
        // before it is what gives it another chance.
        state.lastScanAt = earliestFailure?.let { it - 1 } ?: now
        dao.pruneSeen(now - keepSeenMillis)
        report
    }

    internal suspend fun windowStartForTest(now: Long): Long = windowStart(now)

    private suspend fun windowStart(now: Long): Long {
        state.lastScanAt?.let { return minOf(it, now) - overlapMillis }
        val floor = now - firstRunCapMillis
        val newest = listOfNotNull(dao.latestSmsTxnAt(), dao.latestReviewAt()).maxOrNull()
            ?: return floor
        return maxOf(floor, newest - firstRunMarginMillis)
    }

    companion object {
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
    }
}
