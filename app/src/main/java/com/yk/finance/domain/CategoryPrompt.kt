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

/**
 * The "ask" half of guess-or-ask.
 *
 * The app guesses a category whenever a learned rule or a seeded keyword matches, and
 * asks otherwise. Asking matters most for Union, whose messages carry no payee at all,
 * so there is nothing to guess from and roughly a quarter of spending would otherwise
 * settle silently into Uncategorised - present in the totals, absent from every budget.
 *
 * One notification, replaced rather than stacked: the point is to bring you back to
 * the app while you still remember what the payment was, not to keep a queue in the
 * shade. The queue is the "Needs a category" section on Home.
 */
object CategoryPrompt {

    const val CHANNEL_ID = "category_prompts"

    /** Fixed id: a second unlabelled payment replaces the first rather than piling up. */
    private const val NOTIFICATION_ID = 4201

    fun notify(context: Context, amountPaise: Long, accountName: String?, pendingCount: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        ensureChannel(context)

        val where = accountName?.let { " on $it" }.orEmpty()
        val text = if (pendingCount > 1) {
            "$pendingCount payments are waiting for a category"
        } else {
            "Tap to say what it was for"
        }

        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val intent = open?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(formatRupees(amountPaise) + where)
            .setContentText(text)
            .setAutoCancel(true)
            .apply { intent?.let(::setContentIntent) }
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Needs a category",
            // Low: this is a nudge, not news. A payment you already know about should
            // not make a sound.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Asks what a payment was for when the app cannot tell"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
