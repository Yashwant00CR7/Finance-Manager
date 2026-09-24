package com.yk.finance.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.yk.finance.data.GateMode
import com.yk.finance.data.SenderEntry
import com.yk.finance.data.SenderState
import com.yk.finance.parser.SenderHeader
import com.yk.finance.parser.ruleForHeader
import com.yk.finance.ui.theme.LocalMoneyColors
import com.yk.finance.ui.theme.MoneyColors

/**
 * Which SMS conversations may reach the ledger.
 *
 * Apps get a permission each. SMS conversations share one between all of them, so this
 * is the app supplying the missing granularity: an allowlist of senders, and a switch
 * deciding whether it is advisory or binding.
 *
 * The seen list exists because the app holds RECEIVE_SMS and not READ_SMS and so
 * cannot enumerate the conversations already on the phone - a sender can only appear
 * here after it has sent something. That is also why the switch starts on "observing".
 */
@Composable
fun SendersCard(vm: FinanceViewModel) {
    val senders by vm.senders.collectAsState()
    val mode by vm.gateMode.collectAsState()
    val money = LocalMoneyColors.current
    var typed by remember { mutableStateOf("") }
    val normalizedTyped = SenderHeader.normalize(typed)

    val enrolled = senders.filter { it.state == SenderState.ENROLLED }
    val unknown = senders.filter { it.state == SenderState.UNKNOWN }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Only these senders", fontWeight = FontWeight.Bold)
                    Text(
                        if (mode == GateMode.ACTIVE) {
                            "On. Messages from anyone not on this list are dropped unread - the " +
                                "app records only that they arrived, never what they said. " +
                                "Nothing dropped can be recovered later, so check the list below " +
                                "covers every bank that messages you before relying on it."
                        } else {
                            "Observing. The list is being built but nothing is being dropped yet: " +
                                "a money-shaped message from a sender you have not added still " +
                                "reaches the review tray. Turn this on once the list below looks " +
                                "right."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = money.muted,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = mode == GateMode.ACTIVE,
                    onCheckedChange = { vm.setGateMode(if (it) GateMode.ACTIVE else GateMode.OBSERVE) },
                )
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))

            Text("Tracked", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            if (enrolled.isEmpty()) {
                Text(
                    "Nothing is tracked. No bank message can be recorded until you add one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = money.muted,
                )
            }
            enrolled.forEach { entry ->
                SenderRow(
                    entry = entry,
                    subtitle = entry.bankKey?.let { "$it - ${entry.messageCount} messages" }
                        ?: "no parser yet - goes to the review tray",
                    money = money,
                ) {
                    TextButton(onClick = { vm.unenrollSender(entry.header) }) { Text("Stop") }
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))

            Text("Seen but not tracked", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Counted, never stored. The app knows how many of these looked like money; " +
                    "it does not keep what any of them said.",
                style = MaterialTheme.typography.bodySmall,
                color = money.muted,
            )
            Spacer(Modifier.height(6.dp))
            if (unknown.isEmpty()) {
                Text(
                    "Nothing new. Senders appear here as they message you - there is no way to " +
                        "list the conversations already on the phone without READ_SMS, which this " +
                        "app does not ask for.",
                    style = MaterialTheme.typography.bodySmall,
                    color = money.muted,
                )
            }
            unknown.take(15).forEach { entry ->
                val guess = ruleForHeader(entry.header)
                SenderRow(
                    entry = entry,
                    subtitle = buildString {
                        append("${entry.transactionalCount} of ${entry.messageCount} looked like money")
                        if (guess != null) append(" - looks like ${guess.bank}")
                    },
                    money = money,
                ) {
                    TextButton(onClick = { vm.enrollSender(entry.header, guess?.bank) }) { Text("Track") }
                    TextButton(onClick = { vm.dismissSender(entry.header) }) { Text("Not a bank") }
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))

            Text("Add by hand", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Paste the sender exactly as your messaging app shows it, or type just the middle " +
                    "part. AD-ICICIT-S, AX-ICICIT-S and ICICIT are all the same entry: the operator " +
                    "prefix and the trailing category letter are discarded, because neither of them " +
                    "says who sent the message.",
                style = MaterialTheme.typography.bodySmall,
                color = money.muted,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Sender") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    enabled = normalizedTyped.isNotEmpty(),
                    onClick = {
                        vm.enrollSender(normalizedTyped, ruleForHeader(normalizedTyped)?.bank)
                        typed = ""
                    },
                ) { Text("Track") }
            }
            // Shown while typing rather than after adding: the whole point of dropping
            // two thirds of what you pasted is that you can see it happen and disagree.
            if (typed.isNotBlank()) {
                val guess = ruleForHeader(normalizedTyped)
                Text(
                    when {
                        normalizedTyped.isEmpty() -> "Nothing to track in that."
                        guess != null -> "Will track $normalizedTyped - parsed as ${guess.bank}."
                        else -> "Will track $normalizedTyped - no parser for it, so its messages " +
                            "go to the review tray."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = money.muted,
                )
            }
        }
    }
}

@Composable
private fun SenderRow(
    entry: SenderEntry,
    subtitle: String,
    money: MoneyColors,
    actions: @Composable () -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(entry.header, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = money.muted)
        }
        Row(horizontalArrangement = Arrangement.End) { actions() }
    }
}
