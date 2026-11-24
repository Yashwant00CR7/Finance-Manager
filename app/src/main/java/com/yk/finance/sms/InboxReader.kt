package com.yk.finance.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.yk.finance.domain.InboxSms
import com.yk.finance.domain.InboxSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun canReadInbox(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
        PackageManager.PERMISSION_GRANTED

/**
 * The phone's SMS inbox, read through the system provider.
 *
 * Only the inbox, never sent messages or drafts, and only four columns. Multipart messages
 * are already joined here, which matches what the live receiver builds from its PDUs.
 */
class InboxReader(private val context: Context) : InboxSource {

    override suspend fun messagesSince(fromMillis: Long): List<InboxSms> = withContext(Dispatchers.IO) {
        if (!canReadInbox(context)) return@withContext emptyList()
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
        )
        val out = mutableListOf<InboxSms>()
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                projection,
                "${Telephony.Sms.DATE} > ?",
                arrayOf(fromMillis.toString()),
                "${Telephony.Sms.DATE} ASC",
            )?.use { c ->
                val address = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val body = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val date = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val sent = c.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)
                while (c.moveToNext()) {
                    out += InboxSms(
                        sender = c.getString(address).orEmpty(),
                        body = c.getString(body).orEmpty(),
                        sentAt = c.getLong(sent),
                        receivedAt = c.getLong(date),
                    )
                }
            }
        }
        out
    }
}
