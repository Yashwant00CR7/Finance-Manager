package com.yk.finance.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yk.finance.data.Account
import com.yk.finance.data.ImportBatch
import com.yk.finance.data.Txn
import com.yk.finance.domain.CommitResult
import com.yk.finance.domain.CycleCalculator
import com.yk.finance.domain.formatRupees
import com.yk.finance.importer.DiffReport
import com.yk.finance.importer.DryRunResult
import com.yk.finance.importer.ImportPlan
import com.yk.finance.importer.RowKind
import com.yk.finance.importer.StagedTxn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

private fun day(millis: Long) = CycleCalculator.toLocalDate(millis).format(DAY)

/**
 * The rehearsal, and now the import itself.
 *
 * Reads an export, runs the real importer through stage 6, compares the result with
 * what this app captured for itself, and shows exactly what writing it would do. Only
 * the button at the bottom writes, and it is the same plan that was on screen when you
 * pressed it - nothing is re-read in between.
 *
 * Everything above that button still writes nothing at all, so a file can be checked
 * as often as you like. That was the point of shipping the rehearsal first, and it does
 * not stop being true now that a commit exists.
 */
@Composable
fun ImportSection(vm: FinanceViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val result by vm.dryRun.collectAsState()
    val running by vm.dryRunning.collectAsState()
    val commit by vm.commitResult.collectAsState()
    val batch by vm.lastBatch.collectAsState()
    var readError by remember { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: throw IllegalStateException("that file could not be opened")
                }
            }
            read.fold(
                { text ->
                    readError = null
                    vm.runDryRun(fileName(uri), text)
                },
                { failure -> readError = "Could not read that file: " + failure.message },
            )
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Check or import an export", fontWeight = FontWeight.Medium)
                Text(
                    "Reads a transaction export from another app, reports what each one has " +
                        "that the other does not, and shows exactly what importing it would " +
                        "write. Reading and comparing change nothing; only the import button " +
                        "at the end does, and it can be undone.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { pick.launch(arrayOf("text/*", "text/csv", "text/comma-separated-values", "*/*")) },
                        enabled = !running,
                    ) { Text(if (running) "Reading…" else "Pick an export") }
                    if (result != null) {
                        OutlinedButton(onClick = { vm.clearDryRun() }) { Text("Clear") }
                    }
                }
                readError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        when (val r = result) {
            null -> Unit
            is DryRunResult.Refused -> {
                Spacer(Modifier.height(12.dp))
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("That file was refused", fontWeight = FontWeight.Medium)
                        Text(r.reason, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Refused before anything was interpreted - a file the importer " +
                                "cannot read is better rejected whole than half-understood.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            is DryRunResult.Ready -> {
                Spacer(Modifier.height(12.dp))
                FileCard(r.plan)
                Spacer(Modifier.height(12.dp))
                BindingCard(vm, r)
                Spacer(Modifier.height(12.dp))
                DiffCard(r.diff)
                if (r.plan.rejected.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    RejectCard(r.plan)
                }
                Spacer(Modifier.height(12.dp))
                CommitCard(vm, r)
            }
        }

        commit?.let {
            Spacer(Modifier.height(12.dp))
            CommitResultCard(vm, it)
        }
        batch?.let {
            Spacer(Modifier.height(12.dp))
            LastImportCard(vm, it)
        }
    }
}

/**
 * The one button in the app that writes an import.
 *
 * Two taps, and the second one names what it is about to do. The preconditions are
 * refused rather than warned about: an unbound account means rows would be dropped, and
 * a conservation failure means the file was read wrongly and none of these numbers are
 * real. Neither is something to let through with a caution.
 */
@Composable
private fun CommitCard(vm: FinanceViewModel, ready: DryRunResult.Ready) {
    val committing by vm.committing.collectAsState()
    var armed by remember(ready.commit.batchId) { mutableStateOf(false) }
    val commit = ready.commit
    // Computed from the commit plan, not from the diff. The diff only ever looked at
    // rows inside the comparison window, so a file that starts before the app did could
    // hold every one of its rows back and still leave this button green, offering to
    // import nothing. The plan sees all of them.
    val unmatchedNames = commit.unmatchedNameCount
    val blocked = unmatchedNames > 0 || !commit.conserved

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Import this file", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))

            Text("${commit.insertedRows} rows written as new transactions")
            if (commit.txnsWritten != commit.insertedRows) {
                Text(
                    "  (${commit.txnsWritten} transactions - a transfer is written as two legs)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("${commit.merges.size} folded into transactions already captured here")
            if (commit.queuedRows > 0) {
                Spacer(Modifier.height(8.dp))
                Text("${commit.queuedRows} rows can't be placed yet", fontWeight = FontWeight.Medium)
                commit.queuedByReason().forEach { (reason, count) ->
                    Text(
                        "  ${plural(count, "row")} - $reason",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (unmatchedNames > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "These are the names your old app used. Match each one to an " +
                            "account in \"Accounts in the file\" above, and this list clears.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            if (commit.skippedRows > 0) {
                Text("${commit.skippedRows} already imported by an earlier run")
            }
            Text("${commit.rowsRejected} not understood, kept with their reason")

            Spacer(Modifier.height(8.dp))
            Text(
                "${commit.rowsImported} + ${commit.rowsQueued} + ${commit.rowsRejected} = " +
                    "${commit.rowsTotal} rows",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )

            if (commit.flaggedNoSms > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${commit.flaggedNoSms} of them are bank payments this app never saw an " +
                        "SMS for. They are marked as such permanently, so what the capture " +
                        "missed stays countable after the two ledgers become one.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (commit.newCategories.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Creates ${commit.newCategories.size} categories: " +
                        commit.newCategories.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text("Merging only fills blanks", fontWeight = FontWeight.Medium)
            Text(
                "Where both ledgers have the same payment, the note and category from the " +
                    "file are copied onto the transaction already here - but only into " +
                    "fields that are empty. An import never overwrites something you typed.",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(12.dp))
            when {
                blocked -> Text(
                    if (!commit.conserved) {
                        "Refused: the rows do not add up. That should be impossible, and " +
                            "until it is explained nothing here can be trusted."
                    } else {
                        "Refused until all ${plural(unmatchedNames, "name")} are matched. " +
                            "Importing now would leave those rows out."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                !armed -> Button(onClick = { armed = true }, enabled = !committing) {
                    Text("Import ${commit.rowsImported} rows")
                }

                else -> Column {
                    Text(
                        "This writes to the ledger. It can be undone completely, from the " +
                            "card that appears afterwards.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.commitImport() }, enabled = !committing) {
                            Text(if (committing) "Importing…" else "Yes, import")
                        }
                        OutlinedButton(onClick = { armed = false }, enabled = !committing) {
                            Text("Not yet")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitResultCard(vm: FinanceViewModel, result: CommitResult) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            when (result) {
                is CommitResult.Refused -> {
                    Text("Nothing was imported", fontWeight = FontWeight.Medium)
                    Text(result.reason, style = MaterialTheme.typography.bodySmall)
                }

                is CommitResult.Done -> {
                    Text("Imported", fontWeight = FontWeight.Medium)
                    Text(
                        "${result.inserted} written · ${result.merged} merged · " +
                            "${result.queued} left to decide · ${result.skipped} already here · " +
                            "${result.rejected} not understood",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (result.flaggedNoSms > 0) {
                        Text(
                            "${result.flaggedNoSms} marked as having no SMS counterpart.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { vm.dismissCommitResult() }) { Text("Dismiss") }
        }
    }
}

/**
 * Decision 14 and invariant I5, on screen.
 *
 * The balance after an import is the file's movement applied to whatever the account
 * already held, which is almost never the real figure - the file covers a period, not
 * all of history. Typing the real balance books the difference as a transaction you can
 * see rather than correcting the number behind your back.
 */
@Composable
private fun LastImportCard(vm: FinanceViewModel, batch: ImportBatch) {
    val state by vm.state.collectAsState()
    var confirming by remember(batch.id) { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Last import", fontWeight = FontWeight.Medium)
            Text(
                "${batch.fileName} · ${day(batch.importedAt)} · ${batch.rowsImported} rows in, " +
                    "${batch.rowsQueued} left to decide, ${batch.rowsRejected} not understood",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("Set the real balances", fontWeight = FontWeight.Medium)
            Text(
                "The imported history does not reach today's balance on its own. Enter what " +
                    "each account actually holds and the difference is booked as a visible " +
                    "adjustment, not hidden in the number.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            state.accounts.forEach { account ->
                AnchorRow(account) { paise -> vm.anchorBalance(batch.id, account.id, paise) }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            if (!confirming) {
                TextButton(onClick = { confirming = true }) { Text("Undo this import") }
            } else {
                Text(
                    "Removes every transaction this import wrote, puts the balances back, " +
                        "and clears the notes and categories it filled in on transactions " +
                        "that were already here.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.undoImport(batch.id) }) { Text("Undo it") }
                    OutlinedButton(onClick = { confirming = false }) { Text("Keep it") }
                }
            }
        }
    }
}

@Composable
private fun AnchorRow(account: Account, onSet: (Long) -> Unit) {
    var text by remember(account.id) { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(account.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                "now " + formatRupees(account.currentBalancePaise),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = { entry -> text = entry.filter { it.isDigit() || it == '.' } },
            label = { Text("Actual") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1.2f),
        )
        TextButton(
            onClick = {
                rupeesToPaise(text)?.let {
                    onSet(it)
                    text = ""
                }
            },
            enabled = rupeesToPaise(text) != null,
        ) { Text("Set") }
    }
}


@Composable
private fun FileCard(plan: ImportPlan) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(plan.fileName, fontWeight = FontWeight.Medium)
            Text(
                "Read as ${plan.profileName} · ${plan.dialect.description}",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))

            // Invariant I1, stated on screen rather than assumed. If these ever fail
            // to add up, the file was read wrongly and nothing else here is reliable.
            val conservation = "${plan.dataRows} rows = ${plan.staged.size} understood + " +
                "${plan.rejected.size} rejected"
            Text(conservation, fontWeight = FontWeight.Medium)
            if (!plan.conserved) {
                Text(
                    "These do not add up, which should be impossible. Do not trust this report.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "${plan.countOf(RowKind.EXPENSE)} expenses " +
                    formatRupees(plan.totalPaise(RowKind.EXPENSE)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "${plan.countOf(RowKind.INCOME)} income " +
                    formatRupees(plan.totalPaise(RowKind.INCOME)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "${plan.countOf(RowKind.TRANSFER)} transfers " +
                    formatRupees(plan.totalPaise(RowKind.TRANSFER)),
                style = MaterialTheme.typography.bodySmall,
            )
            plan.firstAt?.let { first ->
                Text(
                    "Covers ${day(first)} to ${day(plan.lastAt ?: first)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text("Columns", style = MaterialTheme.typography.labelMedium)
            plan.mapping.byField.keys.forEach { field ->
                Text(
                    "${plan.mapping.sourceColumn(field)} → ${field.name.lowercase()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (plan.mapping.unmapped.isNotEmpty()) {
                Text(
                    "Kept but not used: ${plan.mapping.unmapped.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (plan.categoryNames.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${plan.categoryNames.size} categories: ${plan.categoryNames.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * The one mapping the importer cannot work out for itself. Binding is saved onto the
 * account as an alias, so it holds for every future run rather than this one.
 */
@Composable
private fun BindingCard(vm: FinanceViewModel, ready: DryRunResult.Ready) {
    val state by vm.state.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Accounts in the file", fontWeight = FontWeight.Medium)
            Text(
                "A name that doesn't match an account here is left out of the comparison " +
                    "below, and its rows cannot be imported. Match all of them.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            ready.bindings.forEach { binding ->
                var open by remember(binding.name) { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(binding.name, Modifier.fillMaxWidth(0.4f))
                    TextButton(onClick = { open = true }) {
                        Text(binding.boundTo?.displayName ?: "Not matched - tap to pick")
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        state.accounts.forEach { account ->
                            DropdownMenuItem(
                                text = { Text(account.displayName) },
                                onClick = {
                                    vm.bindAlias(account.id, binding.name)
                                    open = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffCard(diff: DiffReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("What each side has", fontWeight = FontWeight.Medium)
            Text(
                "Compared over ${day(diff.windowStart)} to ${day(diff.windowEnd)} - the days " +
                    "when both were recording. Cash is left out because this app can never " +
                    "see cash, so a missing cash row proves nothing.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))

            if (!diff.trustworthy) {
                Text(
                    "${diff.unboundRows} rows were skipped because their account name has " +
                        "no match here: ${diff.unmappedAccounts.joinToString(", ")}. The " +
                        "figure below is not a measurement until that is fixed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
            }

            Text(
                "${diff.capturePercent}% captured",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${diff.matched.size} matched · ${diff.ambiguous.size} ambiguous · " +
                    "${diff.fileOnly.size} missed by this app · ${diff.appOnly.size} only here",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Excluded: ${diff.cashRowsExcluded} cash · ${diff.transfersExcluded} transfers · " +
                    "${diff.outOfWindowRows} outside the window",
                style = MaterialTheme.typography.bodySmall,
            )

            if (diff.fileOnly.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Missed by this app (${formatRupees(diff.missedPaise)})",
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Bank payments the other app recorded and no SMS produced here. This " +
                        "is the number the migration is gated on.",
                    style = MaterialTheme.typography.bodySmall,
                )
                diff.fileOnly.take(25).forEach { StagedRow(it) }
                if (diff.fileOnly.size > 25) {
                    Text(
                        "…and ${diff.fileOnly.size - 25} more",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (diff.appOnly.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Only in this app", fontWeight = FontWeight.Medium)
                Text(
                    "Captured from SMS but never entered in the other app - the direction " +
                        "the automatic capture is supposed to win in.",
                    style = MaterialTheme.typography.bodySmall,
                )
                diff.appOnly.take(25).forEach { LedgerRow(it) }
                if (diff.appOnly.size > 25) {
                    Text(
                        "…and ${diff.appOnly.size - 25} more",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (diff.ambiguous.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Ambiguous", fontWeight = FontWeight.Medium)
                Text(
                    "More than one transaction here could be this row. Never merged on a " +
                        "guess; on import day these are yours to decide.",
                    style = MaterialTheme.typography.bodySmall,
                )
                diff.ambiguous.take(15).forEach { StagedRow(it) }
            }
        }
    }
}

@Composable
private fun StagedRow(row: StagedTxn) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            buildString {
                append(CycleCalculator.toLocalDate(row.occurredAt).format(DAY))
                append(" · ").append(row.accountName)
                row.note?.let { append(" · ").append(it) }
                row.categoryName?.let { append(" · ").append(it) }
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(0.7f),
        )
        Text(formatRupees(row.amountPaise), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LedgerRow(txn: Txn) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            CycleCalculator.toLocalDate(txn.occurredAt).format(DAY) + " · " +
                (txn.payee ?: txn.note ?: "no payee"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(0.7f),
        )
        Text(formatRupees(txn.amountPaise), style = MaterialTheme.typography.bodySmall)
    }
}

/** Invariant I6: every rejected row says why, and where to find it. */
@Composable
private fun RejectCard(plan: ImportPlan) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("${plan.rejected.size} rows not understood", fontWeight = FontWeight.Medium)
            Text(
                "Each one says why and which line it is on. On import day these go to the " +
                    "review tray rather than being dropped.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            plan.rejected.take(20).forEach { reject ->
                Text("Line ${reject.lineNumber}: ${reject.reason}", style = MaterialTheme.typography.bodySmall)
                Text(reject.rawLine.take(120), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** SAF gives an opaque uri; the last path segment is the closest thing to a name. */
private fun fileName(uri: Uri): String =
    uri.lastPathSegment?.substringAfterLast('/') ?: "export"

/** "1 row" / "3 rows". The breakdown reads as a sentence, so it has to agree. */
private fun plural(count: Int, noun: String): String =
    if (count == 1) "1 $noun" else "$count ${noun}s"
