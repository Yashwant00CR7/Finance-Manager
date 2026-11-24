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
 * One notification for everything a catch-up scan recovered.
 *
 * A scan after a long power-saving stretch can file a dozen payments at once. Announcing
 * each the way a live message is announced would bury the shade, so the scan says it once:
 * how many came back, how many went straight in, how many are waiting in the tray.
 */
object CatchUpAlert {

    const val CHANNEL_ID = "catch_up"
    private const val NOTIFICATION_ID = 4203

    /** Null when there is nothing worth saying. Pure, so the wording is testable. */
    fun compose(report: CatchUpReport): Announcement? {
        if (report.recovered == 0) return null
        val n = report.recovered
        val title = "Recovered $n bank message${if (n == 1) "" else "s"}"
        val parts = buildList {
            if (report.recorded > 0) add("${report.recorded} recorded")
            if (report.transfers > 0) add("${report.transfers} transfer${if (report.transfers == 1) "" else "s"}")
            if (report.needReview > 0) add("${report.needReview} need review")
            if (report.needCategory > 0) add("${report.needCategory} need a category")
        }
        return Announcement(
            title = title,
            body = "Missed while the phone was saving power. " + parts.joinToString(" · "),
        )
    }

    fun notify(context: Context, report: CatchUpReport) {
        val said = compose(report) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        ensureChannel(context)

        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?.apply { if (report.needReview > 0) putExtra(WidgetRoute.EXTRA, WidgetRoute.INBOX) }
        val intent = open?.let {
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(said.title)
            .setContentText(said.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(said.body))
            .setAutoCancel(true)
            .apply { intent?.let(::setContentIntent) }
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Recovered messages",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Bank messages found in the inbox after power saving blocked them"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
