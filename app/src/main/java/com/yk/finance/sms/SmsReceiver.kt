package com.yk.finance.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.yk.finance.FinanceApplication
import com.yk.finance.domain.CategoryPrompt
import com.yk.finance.domain.IngestOutcome
import com.yk.finance.domain.RecordedAlert
import com.yk.finance.widget.WidgetRefresh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Deliberately thin. All judgement lives in SmsIngestor so it can be tested on the
 * JVM without a device.
 *
 * Multipart messages arrive as several PDUs; bank alerts frequently exceed 160 chars,
 * so the parts must be concatenated before parsing or long messages would be truncated
 * mid-sentence and fail to match.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages.first().originatingAddress.orEmpty()
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        val receivedAt = System.currentTimeMillis()

        val app = context.applicationContext as FinanceApplication
        val pending = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val outcome = app.ingestor.ingest(sender, body, receivedAt)
                // Anything that reached the ledger moves the widget's figures. Asked
                // for unconditionally rather than per-outcome: a transfer leg changes
                // no total but a dropped duplicate proves the widget is already right,
                // and a redraw that changes nothing costs nothing.
                WidgetRefresh.request(context)
                when (outcome) {
                    is IngestOutcome.Recorded -> {
                        app.budgets.evaluateAndNotify(context)
                        // Either/or, never both: a payment with no category is a
                        // question and a payment with one is news, and the same
                        // payment is never two notifications.
                        if (outcome.needsCategory) {
                            promptForCategory(app, context, outcome)
                        } else {
                            announce(app, context, outcome)
                        }
                    }
                    is IngestOutcome.Transfer -> Unit // transfers never affect budgets
                    is IngestOutcome.DroppedDuplicate ->
                        Log.d(TAG, "duplicate of txn ${outcome.existingId}, dropped")
                    IngestOutcome.Queued -> Log.d(TAG, "queued for review")
                    is IngestOutcome.AwaitingConfirmation ->
                        // Silent by design, for now, and consistent with Queued: the figures
                        // are in the tray and nothing has been booked. Worth knowing that the
                        // tray carries no badge yet, so this waits until the Inbox is opened.
                        Log.d(TAG, "awaiting confirmation of ${outcome.patternId}")
                    is IngestOutcome.Ignored -> Log.d(TAG, "ignored: ${outcome.reason}")
                }
            } catch (t: Throwable) {
                // A crash here would lose the message silently, which is the one
                // outcome the whole design is built to avoid.
                Log.e(TAG, "ingest failed for sender=$sender", t)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Asked in the moment, because a payment is only easy to label while you still
     * remember making it. A week later it is an amount and a date.
     */
    private suspend fun promptForCategory(
        app: FinanceApplication,
        context: Context,
        outcome: IngestOutcome.Recorded,
    ) {
        val txn = app.dao.txnById(outcome.txnId) ?: return
        CategoryPrompt.notify(
            context = context,
            amountPaise = txn.amountPaise,
            accountName = app.dao.accountById(outcome.accountId)?.displayName,
            pendingCount = app.dao.needsCategoryCount(),
        )
    }

    /**
     * Says what was recorded, while the payment is still the thing you just did.
     *
     * Only the rows the app filed itself reach here. Transfers stay silent on purpose -
     * an ATM withdrawal and the cash leg the app mints to match it are one movement of
     * your own money, and announcing them would report spending that never happened.
     */
    private suspend fun announce(
        app: FinanceApplication,
        context: Context,
        outcome: IngestOutcome.Recorded,
    ) {
        if (!app.prefs.notifyOnRecord) return
        val txn = app.dao.txnById(outcome.txnId) ?: return
        RecordedAlert.notify(
            context = context,
            txn = txn,
            categoryName = txn.categoryId?.let { app.dao.categoryById(it)?.name },
            accountName = app.dao.accountById(outcome.accountId)?.displayName,
        )
    }

    private companion object {
        const val TAG = "SmsReceiver"
    }
}
