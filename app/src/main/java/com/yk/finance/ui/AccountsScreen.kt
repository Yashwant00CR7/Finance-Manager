package com.yk.finance.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yk.finance.data.Account
import com.yk.finance.data.AccountKind
import com.yk.finance.data.Txn
import com.yk.finance.domain.AccountRemoval
import com.yk.finance.domain.Looks
import com.yk.finance.domain.Splitter
import com.yk.finance.domain.formatRupees
import com.yk.finance.ui.theme.LocalMoneyColors

/**
 * Balances, and the all-time figures behind them.
 *
 * Expense and income are kept pure - money that actually left or arrived - so they do
 * not sum to the balance by themselves. The line underneath says why instead of folding
 * opening balances into income, which would inflate every income figure in the app with
 * money that was never earned.
 */
@Composable
fun AccountsScreen(
    vm: FinanceViewModel,
    state: UiState,
    onSelfTransfer: (accountId: Long?) -> Unit = {},
) {
    val overall by vm.overall.collectAsState()
    val owed by vm.owed.collectAsState()
    val aliases by vm.accountAliases.collectAsState()
    var settling by remember { mutableStateOf<Splitter.Owed?>(null) }
    val money = LocalMoneyColors.current
    var editing by remember { mutableStateOf<Account?>(null) }
    var reconciling by remember { mutableStateOf<Account?>(null) }
    var deleting by remember { mutableStateOf<Account?>(null) }
    var adding by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Overall",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp),
            )
        }

        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("EXPENSE SO FAR", style = MaterialTheme.typography.labelMedium, color = money.muted)
                            Text(
                                "-" + formatRupees(overall.expenseAllTimePaise),
                                style = MaterialTheme.typography.titleMedium,
                                color = money.expense,
                            )
                        }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("INCOME SO FAR", style = MaterialTheme.typography.labelMedium, color = money.muted)
                            Text(
                                formatRupees(overall.incomeAllTimePaise),
                                style = MaterialTheme.typography.titleMedium,
                                color = money.income,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "TOTAL BALANCE",
                        style = MaterialTheme.typography.labelMedium,
                        color = money.muted,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        formatRupees(overall.totalBalancePaise),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "opened with ${formatRupees(overall.openingPaise)} · " +
                            "corrections ${formatRupees(overall.correctionsPaise)} · transfers excluded",
                        style = MaterialTheme.typography.bodySmall,
                        color = money.muted,
                    )
                }
            }
        }

        // Beside the balances, never inside them: 570 you are owed is not 570 you have,
        // and the moment it is folded into a total, the total stops being the answer to
        // "what do I actually have".
        if (owed.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Owed to you",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                formatRupees(owed.sumOf { it.outstandingPaise }),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = money.income,
                            )
                        }
                        Text(
                            "not counted in the total above",
                            style = MaterialTheme.typography.bodySmall,
                            color = money.muted,
                        )
                        Spacer(Modifier.height(8.dp))
                        owed.forEach { person ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { settling = person }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(person.person, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        if (person.repaidPaise > 0) {
                                            "${formatRupees(person.repaidPaise)} of " +
                                                "${formatRupees(person.lentPaise)} back · " +
                                                "${person.rows.size} item${if (person.rows.size == 1) "" else "s"}"
                                        } else {
                                            "${person.rows.size} item${if (person.rows.size == 1) "" else "s"}"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = money.muted,
                                    )
                                }
                                Text(
                                    formatRupees(person.outstandingPaise),
                                    fontWeight = FontWeight.Bold,
                                    color = money.income,
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Accounts",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = { onSelfTransfer(null) }) {
                    Icon(
                        Icons.Default.SwapHoriz,
                        contentDescription = null,
                        tint = money.income,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Self transfer",
                        color = money.income,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        items(state.accounts, key = { it.id }) { account ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBubble(
                        account.iconKey ?: Looks.accountIcon(account.displayName, account.kind),
                        account.colourHex ?: Looks.accountColour(account.displayName, account.kind),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            account.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Balance: " + formatRupees(account.currentBalancePaise),
                            color = money.income,
                        )
                        // Whether the number is the bank's or the app's arithmetic is
                        // the single most useful thing to know about a balance.
                        Text(
                            when {
                                account.needsConfirmation -> "Seen in a message · not named yet"
                                account.kind == AccountKind.CASH -> "Cash wallet · you keep this one honest"
                                account.providesBalance -> "Confirmed by bank"
                                else -> "Estimated · reconcile to correct"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = money.muted,
                        )
                        // Said out loud because it decides where messages land: an account
                        // you merged another into quietly receives that one's messages too.
                        aliases[account.id]?.takeIf { it.isNotEmpty() }?.let { extra ->
                            Text(
                                "Also files " + extra.joinToString { "${it.bank} ..${it.accountToken}" },
                                style = MaterialTheme.typography.bodySmall,
                                color = money.muted,
                            )
                        }
                    }
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Account options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Self transfer") },
                                onClick = {
                                    menuOpen = false
                                    onSelfTransfer(account.id)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Rename, icon and colour") },
                                onClick = { menuOpen = false; editing = account },
                            )
                            if (account.kind != AccountKind.CASH) {
                                DropdownMenuItem(
                                    text = { Text("Reconcile balance") },
                                    onClick = { menuOpen = false; reconciling = account },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Delete account", color = MaterialTheme.colorScheme.error) },
                                onClick = { menuOpen = false; deleting = account },
                            )
                        }
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = { adding = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Default.AddCircleOutline, null)
                Spacer(Modifier.width(8.dp))
                Text("ADD NEW ACCOUNT")
            }
        }

        item { Spacer(Modifier.height(96.dp)) }
    }

    settling?.let { person ->
        OwedDialog(
            vm = vm,
            state = state,
            person = person,
            onDismiss = { settling = null },
        )
    }

    editing?.let { account ->
        LookEditor(
            title = "Edit account",
            initialName = account.displayName,
            initialIcon = account.iconKey ?: Looks.accountIcon(account.displayName, account.kind),
            initialColour = account.colourHex ?: Looks.accountColour(account.displayName, account.kind),
            onDismiss = { editing = null },
            onSave = { name, icon, colour ->
                vm.updateAccountLook(account, name, icon, colour)
                editing = null
            },
        )
    }

    reconciling?.let { account ->
        ReconcileDialog(
            account = account,
            onDismiss = { reconciling = null },
            onConfirm = { paise ->
                vm.reconcile(account, paise)
                reconciling = null
            },
        )
    }

    if (adding) {
        AddAccountDialog(
            onDismiss = { adding = false },
            onAdd = { name, openingPaise, isCash ->
                vm.addDeclaredAccount(name, openingPaise, isCash)
                adding = false
            },
        )
    }

    deleting?.let { account ->
        DeleteAccountDialog(
            account = account,
            txnCount = state.transactions.count { it.accountId == account.id },
            targets = state.accounts.filter { AccountRemoval.canMoveInto(account, it) },
            onDismiss = { deleting = null },
            onConfirm = { moveToId, stopTracking ->
                vm.deleteAccount(account, moveToId, stopTracking)
                deleting = null
            },
        )
    }
}

/**
 * Deleting an account, and the two honest things to do with what is recorded on it.
 *
 * The count is stated before anything is chosen, because "delete" on an account with three
 * hundred rows and on one with none are different decisions. Moving is offered beside
 * deleting rather than after it: an account created twice - once by hand, once by the
 * bank's first message - is the commonest reason to be here, and deleting either copy with
 * its history would throw away half of it.
 */
@Composable
private fun DeleteAccountDialog(
    account: Account,
    txnCount: Int,
    targets: List<Account>,
    onDismiss: () -> Unit,
    onConfirm: (moveToId: Long?, stopTracking: Boolean) -> Unit,
) {
    val money = LocalMoneyColors.current
    var moving by remember { mutableStateOf(false) }
    var moveTo by remember { mutableStateOf<Account?>(null) }
    var picking by remember { mutableStateOf(false) }
    var stopTracking by remember { mutableStateOf(false) }
    val linked = AccountRemoval.isMessageLinked(account)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${account.displayName}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (txnCount) {
                        0 -> "Nothing is recorded on this account."
                        1 -> "1 transaction is recorded on this account."
                        else -> "$txnCount transactions are recorded on this account."
                    },
                )
                if (txnCount > 0) {
                    ChoiceRow(
                        selected = !moving,
                        title = "Delete them too",
                        detail = "Its balance leaves your total. Transfers with your other " +
                            "accounts stay on those accounts.",
                        onClick = { moving = false },
                    )
                    if (targets.isNotEmpty()) {
                        ChoiceRow(
                            selected = moving,
                            title = "Move them to another account",
                            detail = "For two entries that are really one account. Its bank " +
                                "messages go there from now on.",
                            onClick = { moving = true },
                        )
                        if (moving) {
                            Box(Modifier.padding(start = 48.dp)) {
                                OutlinedButton(onClick = { picking = true }) {
                                    Text(moveTo?.displayName ?: "Choose account", maxLines = 1)
                                }
                                DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                                    targets.forEach { target ->
                                        DropdownMenuItem(
                                            text = { Text(target.displayName) },
                                            onClick = { moveTo = target; picking = false },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // Moving keeps the messages coming, into the other account, so switching
                // them off is only a question when they would otherwise recreate this one.
                if (linked && !moving) {
                    Row(
                        Modifier.fillMaxWidth().clickable { stopTracking = !stopTracking },
                        verticalAlignment = Alignment.Top,
                    ) {
                        Checkbox(checked = stopTracking, onCheckedChange = { stopTracking = it })
                        Column(Modifier.padding(top = 12.dp)) {
                            Text("Ignore its messages from now on", fontWeight = FontWeight.Medium)
                            Text(
                                "Leave this off and the next message for " +
                                    "${account.bank} ..${account.accountToken} adds the account back. " +
                                    "You can switch it back on in Settings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = money.muted,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(if (moving) moveTo?.id else null, stopTracking && !moving) },
                enabled = !moving || moveTo != null,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(if (moving) "Move and delete" else "Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.Top) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(top = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = LocalMoneyColors.current.muted)
        }
    }
}

@Composable
private fun ReconcileDialog(account: Account, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var input by remember { mutableStateOf("") }
    val paise = rupeesToPaise(input)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${account.displayName}: real balance") },
        text = {
            Column {
                Text(
                    "The gap between this and the computed balance is recorded as a visible " +
                        "correction rather than quietly rewriting history.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Actual balance (₹)") },
                    isError = input.isNotBlank() && paise == null,
                )
            }
        },
        confirmButton = {
            Button(onClick = { paise?.let(onConfirm) }, enabled = paise != null) { Text("Confirm") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddAccountDialog(onDismiss: () -> Unit, onAdd: (String, Long, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Just a name and what is in it now. The app attaches bank messages to it " +
                        "the first time one arrives.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                OutlinedTextField(
                    value = opening,
                    onValueChange = { opening = it },
                    label = { Text("Balance now (₹)") },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, rupeesToPaise(opening) ?: 0L, false) },
                enabled = name.isNotBlank(),
            ) { Text("Add bank account") }
        },
        dismissButton = {
            TextButton(onClick = { onAdd(name.ifBlank { "Cash" }, rupeesToPaise(opening) ?: 0L, true) }) {
                Text("Add cash")
            }
        },
    )
}

/**
 * One person's debts, and the two honest ways they end.
 *
 * Repayments are offered for you to pick rather than matched on amount. A 120 refund
 * from a shop and a 120 repayment from a friend are the same number on the same day,
 * and an app that chooses between them is wrong in a way nobody notices for months.
 */
@Composable
private fun OwedDialog(
    vm: FinanceViewModel,
    state: UiState,
    person: Splitter.Owed,
    onDismiss: () -> Unit,
) {
    val money = LocalMoneyColors.current
    var candidates by remember { mutableStateOf<List<Txn>>(emptyList()) }
    var picking by remember { mutableStateOf<Txn?>(null) }
    var writingOff by remember { mutableStateOf<Txn?>(null) }

    LaunchedEffect(person.person) { candidates = vm.settlementCandidates() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${person.person} owes ${formatRupees(person.outstandingPaise)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                person.rows.forEach { row ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    row.payee?.takeIf { it.isNotBlank() } ?: state.categoryName(row.categoryId),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    dayHeaderText(row.occurredAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = money.muted,
                                )
                            }
                            Text(formatRupees(row.amountPaise), fontWeight = FontWeight.Bold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { picking = row }) { Text("Paid me back") }
                            TextButton(onClick = { writingOff = row }) { Text("Write off") }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )

    picking?.let { row ->
        val group = row.splitGroupId
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text("How did ${person.person} pay you back?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Pick the credit that was the repayment. It stops counting as " +
                            "income - it is your own money coming back, not money you earned.",
                        style = MaterialTheme.typography.bodySmall,
                        color = money.muted,
                    )
                    Spacer(Modifier.height(4.dp))
                    candidates.take(8).forEach { credit ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    group?.let { vm.settleWithCredit(it, credit.id) }
                                    picking = null
                                    onDismiss()
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    credit.payee?.takeIf { it.isNotBlank() } ?: "Credit",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    dayHeaderText(credit.occurredAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = money.muted,
                                )
                            }
                            Text(formatRupees(credit.amountPaise), color = money.income)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // Cash leaves no message, so nothing in the list above can ever be
                    // it. Recording it raises the Cash balance, because it really did.
                    TextButton(onClick = {
                        group?.let {
                            vm.settleWithCash(it, row.amountPaise, person.person)
                        }
                        picking = null
                        onDismiss()
                    }) { Text("They gave me cash") }
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        )
    }

    writingOff?.let { row ->
        AlertDialog(
            onDismissRequest = { writingOff = null },
            title = { Text("Write this off?") },
            text = {
                Text(
                    "${formatRupees(row.amountPaise)} becomes a " +
                        "${state.givenCategory?.name ?: "For Others"} expense, still dated " +
                        "${dayHeaderText(row.occurredAt)}. That month's total goes up by " +
                        "${formatRupees(row.amountPaise)} - the money did leave your " +
                        "account that day, you just found out later it was not coming back.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.writeOff(row.id)
                    writingOff = null
                    onDismiss()
                }) { Text("Write off") }
            },
            dismissButton = { TextButton(onClick = { writingOff = null }) { Text("Cancel") } },
        )
    }
}
