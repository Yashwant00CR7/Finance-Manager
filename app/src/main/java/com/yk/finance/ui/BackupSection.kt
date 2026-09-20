package com.yk.finance.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yk.finance.backup.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Backup, export and restore.
 *
 * Two different files, on purpose. The snapshot is the whole database and is what
 * actually restores; the CSV is readable but cannot carry balances, budgets or cycle
 * state. Offering only the readable one would be the more comfortable lie.
 */
@Composable
fun BackupSection(vm: FinanceViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<RestoreSource?>(null) }
    var snapshots by remember { mutableStateOf(Snapshot.automaticSnapshots(context)) }

    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: error("could not open that location")
                    stream.use { Snapshot.export(context, it) }
                }.fold(
                    { bytes -> "Backup saved, " + (bytes / 1024) + " KB." },
                    { failure -> "Could not save the backup: " + failure.message },
                )
            }
        }
    }

    val saveCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val csv = vm.ledgerCsv()
            status = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: error("could not open that location")
                    stream.use { it.write(csv.toByteArray()) }
                }.fold(
                    { "Transactions exported." },
                    { failure -> "Could not export: " + failure.message },
                )
            }
        }
    }

    val pickBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> if (uri != null) confirming = RestoreSource.Picked(uri) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Backup", fontWeight = FontWeight.Medium)
            Text(
                "A backup is the entire database. The CSV is a readable copy of your " +
                    "transactions only - it cannot restore balances, budgets or your " +
                    "salary cycle, so keep a backup as well.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { saveBackup.launch(Snapshot.suggestedFileName()) }) {
                    Text("Save a backup")
                }
                OutlinedButton(onClick = { saveCsv.launch("finance-transactions.csv") }) {
                    Text("Export CSV")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { pickBackup.launch(arrayOf("*/*")) }) {
                Text("Restore from a backup")
            }
            Text(
                "Restoring replaces everything currently in the app and restarts it.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (snapshots.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Automatic snapshots", fontWeight = FontWeight.Medium)
                Text(
                    "Taken by the app before it upgrades its own database. Kept in case " +
                        "an upgrade goes wrong.",
                    style = MaterialTheme.typography.bodySmall,
                )
                snapshots.forEach { file ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            file.name + "  (" + (file.length() / 1024) + " KB)",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(0.65f),
                        )
                        TextButton(onClick = { confirming = RestoreSource.Automatic(file) }) {
                            Text("Restore")
                        }
                    }
                }
            }

            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    confirming?.let { source ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Replace everything?") },
            text = {
                Text(
                    "Every account, transaction and budget in the app right now will be " +
                        "replaced by the contents of that file, and the app will restart. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = source
                    confirming = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { restore(context, chosen) }
                        when (result) {
                            is Snapshot.RestoreResult.Ok -> restartApp(context)
                            is Snapshot.RestoreResult.Rejected -> {
                                status = "Not restored: " + result.reason
                                snapshots = Snapshot.automaticSnapshots(context)
                            }
                        }
                    }
                }) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("Cancel") }
            },
        )
    }
}

private sealed interface RestoreSource {
    data class Picked(val uri: Uri) : RestoreSource
    data class Automatic(val file: File) : RestoreSource
}

private fun restore(context: Context, source: RestoreSource): Snapshot.RestoreResult =
    runCatching {
        when (source) {
            is RestoreSource.Picked -> {
                val stream = context.contentResolver.openInputStream(source.uri)
                    ?: return Snapshot.RestoreResult.Rejected("that file could not be opened")
                stream.use { Snapshot.restore(context, it) }
            }
            is RestoreSource.Automatic -> source.file.inputStream().use {
                Snapshot.restore(context, it)
            }
        }
    }.getOrElse { Snapshot.RestoreResult.Rejected(it.message ?: "unknown error") }

/**
 * Every open database handle points at a file that has just been replaced, so the
 * process has to go. Relaunching first keeps it feeling like a restart rather than
 * a crash.
 */
private fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
    launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    launch?.let { context.startActivity(it) }
    Runtime.getRuntime().exit(0)
}
