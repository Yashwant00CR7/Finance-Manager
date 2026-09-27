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
import com.yk.finance.widget.WidgetRoute

/**
 * Notifies the user whenever an SMS alert requires their attention (such as an unparsed
 * bank message or a researched pattern awaiting first confirmation).
 *
 * Tapping the notification takes the user directly to the Inbox to review and act.
 */
object AttentionPrompt {

    const val CHANNEL_ID = "attention_prompts"
    private const val NOTIFICATION_ID = 4202

    fun notify(context: Context, pendingCount: Int, sender: String? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        ensureChannel(context)

        val title = if (pendingCount > 1) {
            "$pendingCount messages need your attention"
        } else {
            "Message needs your attention"
        }
        val text = if (!sender.isNullOrBlank()) {
            "New unrecognised message from $sender. Tap to review."
        } else {
            "Tap to review and record in Inbox."
        }

        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?.putExtra(WidgetRoute.EXTRA, WidgetRoute.INBOX)
        val intent = open?.let {
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .apply { intent?.let(::setContentIntent) }
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    fun dismiss(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Needs attention",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Alerts you when a bank message needs your review or confirmation"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
