package com.yk.finance.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yk.finance.domain.CycleCalculator
import com.yk.finance.ui.theme.LocalMoneyColors

/**
 * Settings, and the one place the salary machinery explains itself.
 *
 * The cycle list describes your payroll, not the app's periods - every screen now
 * counts by calendar month. It is kept because knowing when salary actually landed,
 * and which of those dates the app had to guess, is worth reading on its own.
 */
@Composable
fun SettingsSection(vm: FinanceViewModel, state: UiState) {
    val cycles by vm.salaryCycles.collectAsState()
    val money = LocalMoneyColors.current
    val smsGranted = rememberSmsPermissionGranted()
    val autoFile by vm.autoFile.collectAsState()
    val guessStats by vm.guessStats.collectAsState()
    val guessCount by vm.guessCount.collectAsState()
    val notifyOnRecord by vm.notifyOnRecord.collectAsState()
    val ignored by vm.ignoredAccounts.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!smsGranted) MissingSmsPermissionCard()

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Announce every payment", fontWeight = FontWeight.Bold)
                        Text(
                            "Posts a notification the moment a bank message is filed, saying " +
                                "what was recorded and where it went. A payment the app cannot " +
                                "categorise asks you a question instead, so nothing arrives " +
                                "twice. Transfers between your own accounts stay quiet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = money.muted,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = notifyOnRecord, onCheckedChange = vm::setNotifyOnRecord)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Guess categories", fontWeight = FontWeight.Bold)
                        Text(
                            "Union sends no payee, so about a quarter of spending has nothing to " +
                                "match a rule against. When a payment resembles ones you have " +
                                "already filed, the app files it the same way and marks it as a " +
                                "guess. Turning this off still puts the likely category first in " +
                                "the picker - it only stops the app filing anything itself.",
                            style = MaterialTheme.typography.bodySmall,
                            color = money.muted,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = autoFile, onCheckedChange = vm::setAutoFile)
                }
                Spacer(Modifier.height(10.dp))
                // The honest number, or an honest refusal to quote one. Five is not a
                // sample; reporting "100% correct" off two guesses would be worse than
                // saying nothing, because it invites trust the app has not earned.
                Text(
                    when {
                        guessStats.hasEnoughToReport ->
                            "Right ${guessStats.confirmed} of ${guessStats.total} times you have checked."
                        guessStats.total > 0 ->
                            "You have checked ${guessStats.total} so far - too few to quote a figure."
                        else ->
                            "No guesses checked yet."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = money.muted,
                )
                if (guessCount > 0) {
                    Text(
                        "$guessCount waiting to be checked - filter Records by \"Guessed categories " +
                            "only\" to sweep them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = money.muted,
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Salary detection", fontWeight = FontWeight.Bold)
                Text(
                    "Totals everywhere in the app are per calendar month. This is separate: it is " +
                        "when the app believes salary landed, one credit to the next. A cycle marked " +
                        "estimated had no salary credit to read, so its start came from the " +
                        "last-working-day rule and may be a day or two out.",
                    style = MaterialTheme.typography.bodySmall,
                    color = money.muted,
                )
                Spacer(Modifier.height(10.dp))
                cycles.reversed().take(14).forEach { period ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(period.label, Modifier.weight(1f))
                        Text(
                            periodRangeText(period),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (period.inferred) money.expense else money.muted,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                state.cycle?.let { cycle ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        buildString {
                            append("Current cycle began ")
                            append(CycleCalculator.toLocalDate(cycle.cycleStartMillis).format(FULL_DATE))
                            if (cycle.salaryAmountPaise == null) {
                                append(" · salary not tagged yet, so rolls use the date rule")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = money.muted,
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Rename accounts", fontWeight = FontWeight.Bold)
                Text(
                    "The bank and masked digits stay as the bank prints them; only the name you " +
                        "read changes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = money.muted,
                )
                Spacer(Modifier.height(8.dp))
                state.accounts.forEach { account ->
                    var name by remember(account.id) { mutableStateOf(account.displayName) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("${account.bank} ..${account.accountToken}") },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { vm.rename(account, name) }) { Text("Save") }
                    }
                }
            }
        }

        // Only when there is something to undo. Every entry here is a bank account whose
        // payments the app is deliberately not recording, and that has to stay findable -
        // otherwise a box ticked while deleting one account looks, months later, exactly
        // like a parser that has stopped working.
        if (ignored.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Ignored accounts", fontWeight = FontWeight.Bold)
                    Text(
                        "Messages for these are dropped as they arrive. Tracking one again picks " +
                            "up from its next message; nothing missed in between comes back.",
                        style = MaterialTheme.typography.bodySmall,
                        color = money.muted,
                    )
                    Spacer(Modifier.height(8.dp))
                    ignored.forEach { account ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(account.displayName)
                                Text(
                                    "${account.bank} ..${account.accountToken}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = money.muted,
                                )
                            }
                            TextButton(onClick = { vm.trackAgain(account) }) { Text("Track again") }
                        }
                    }
                }
            }
        }
    }
}
