package com.yk.finance.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!smsGranted) MissingSmsPermissionCard()

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
    }
}
