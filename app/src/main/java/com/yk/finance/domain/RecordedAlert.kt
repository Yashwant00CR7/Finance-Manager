package com.yk.finance.domain

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yk.finance.data.Txn
import com.yk.finance.parser.Direction

/** What the shade says about one recorded payment. Pure, so the wording is testable. */
data class Announcement(val title: String, val body: String)

/**
 * Says a payment landed, the moment the app files it.
 *
 * Until now the app only ever spoke up when something was wrong - a category it could
 * not work out, a budget going over. The payments it handled perfectly were the silent
 * ones, which meant the only way to know what had been recorded was to open the app and
 * scroll. A bank alert you can read in the shade and a ledger row you have to go looking
 * for are not the same product.
 *
 * This is [CategoryPrompt]'s counterpart, not a second copy of it. Exactly one of the
 * two fires per recorded transaction: CategoryPrompt asks a question, this one reports
 * an answer. Nothing is ever announced twice.
 */
object RecordedAlert {

    const val CHANNEL_ID = "recorded_payments"

    /**
     * Each payment gets its own notification rather than replacing the last - these are
     * separate events, and a day's spending read back as a list in the shade is the
     * whole point. The span bounds the id range so it can never collide with the budget
     * alerts (small ids) or the category prompt (4201); a wrap only bites after 400
     * transactions, by which time the notification it lands on is long dismissed.
     */
    private const val ID_BASE = 5_000
    private const val ID_SPAN = 400

    /**
     * Money first, because the amount is the thing you are checking against your own
     * memory of the last few minutes. Where it went comes second, and a guessed category
     * says so - the app marks its guesses everywhere else, and a notification that
     * quietly presented one as fact would be the one place it did not.
     */
    fun compose(
        direction: Direction,
        amountPaise: Long,
        categoryName: String?,
        accountName: String?,
        payee: String?,
        categoryWasGuessed: Boolean,
    ): Announcement {
        val verb = if (direction == Direction.DEBIT) "Spent" else "Received"
        val category = categoryName?.takeIf { it.isNotBlank() }?.let {
            if (categoryWasGuessed) "$it (guess)" else it
        }
        val parts = listOfNotNull(
            category,
            accountName?.takeIf { it.isNotBlank() },
            payee?.takeIf { it.isNotBlank() },
        )
        return Announcement(
            title = "$verb ${formatRupees(amountPaise)}",
            // Union sends no payee and an uncategorised credit has no category either,
            // so every part of the body is genuinely optional. Saying "Recorded" beats
            // posting a notification with an empty second line.
            body = if (parts.isEmpty()) "Recorded" else parts.joinToString(" · "),
        )
    }

    fun notify(context: Context, txn: Txn, categoryName: String?, accountName: String?) {
        // Without POST_NOTIFICATIONS on Android 13+ this silently does nothing, which
        // looks exactly like a broken feature - so check rather than assume.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        ensureChannel(context)

        val said = compose(
            direction = txn.direction,
            amountPaise = txn.amountPaise,
            categoryName = categoryName,
            accountName = accountName,
            payee = txn.payee,
            categoryWasGuessed = txn.categoryWasInferred,
        )

        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val intent = open?.let {
            PendingIntent.getActivity(
                context,
                // Distinct per transaction, or FLAG_UPDATE_CURRENT would rewrite the
                // one PendingIntent every notification shares.
                (txn.id % ID_SPAN).toInt(),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(said.title)
            .setContentText(said.body)
            .setWhen(txn.occurredAt)
            .setShowWhen(true)
            .setAutoCancel(true)
            .apply { intent?.let(::setContentIntent) }
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(ID_BASE + (txn.id % ID_SPAN).toInt(), notification)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Recorded payments",
            // Default rather than low, and the choice only goes one way: a channel's
            // importance is fixed the first time it is created and the app can never
            // raise it afterwards, but the phone's settings can always lower it. Start
            // where someone who wants to hear these can, and let anyone tired of the
            // bank's SMS chime and this one arriving together turn it down.
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Announces every payment the app files from a bank message"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
