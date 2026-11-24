package com.yk.finance.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

fun isSmsPermissionGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
        PackageManager.PERMISSION_GRANTED

fun isInboxReadGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
        PackageManager.PERMISSION_GRANTED

fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/**
 * Re-checks on every resume, so returning from the settings screen updates the banner
 * without a restart - which is the whole journey this exists to support.
 */
@Composable
fun rememberSmsPermissionGranted(): Boolean = rememberPermissionState(::isSmsPermissionGranted)

/** Same as [rememberSmsPermissionGranted], for the inbox catch-up's READ_SMS. */
@Composable
fun rememberInboxReadGranted(): Boolean = rememberPermissionState(::isInboxReadGranted)

@Composable
private fun rememberPermissionState(check: (Context) -> Boolean): Boolean {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(check(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = check(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return granted
}

/**
 * Shown when SMS access is missing.
 *
 * Without this the app is indistinguishable from one that simply has no transactions
 * yet: a balance of zero and no explanation. The Restricted Settings instructions are
 * spelled out because on Android 13+ a sideloaded app cannot be granted SMS access
 * from the permission screen at all until that block is lifted, and nothing on screen
 * says so.
 */
@Composable
fun MissingSmsPermissionCard() {
    val context = LocalContext.current
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Not reading your messages", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "SMS access is off, so no bank transaction can be recorded. Anything " +
                    "that arrives while it is off is missed until access is back.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "If the SMS toggle is greyed out, this app was installed outside the " +
                    "Play Store and Android blocks it by default. On the page below tap " +
                    "the three dots at the top right, choose \"Allow restricted settings\", " +
                    "and then open Permissions.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { openAppSettings(context) }) { Text("Open app settings") }
        }
    }
}

/**
 * Shown when the live receiver works but the inbox catch-up cannot.
 *
 * Quieter than [MissingSmsPermissionCard] because nothing is broken day to day. It only
 * matters on phones whose power-saving mode blocks incoming SMS broadcasts, and on those
 * it is the difference between a missed payment and a recovered one.
 */
@Composable
fun MissingInboxReadCard() {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Catch-up after power saving is off", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Some power-saving modes stop bank messages reaching the app. With " +
                    "permission to read SMS, the app looks in your inbox for anything it " +
                    "missed each time you open it. Nothing it reads leaves the phone.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "In app settings open Permissions, then SMS. If it is greyed out, tap the " +
                    "three dots at the top right and choose \"Allow restricted settings\" first.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { openAppSettings(context) }) { Text("Open app settings") }
        }
    }
}
