package com.yk.finance.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yk.finance.data.Account
import com.yk.finance.data.PendingReview
import com.yk.finance.data.Txn
import com.yk.finance.domain.AttentionPrompt
import com.yk.finance.domain.formatRupees
import com.yk.finance.parser.Direction
import com.yk.finance.parser.ParsedSms
import com.yk.finance.parser.ParseResult
import com.yk.finance.parser.PREVIEW_PARSER
import com.yk.finance.ui.theme.LocalMoneyColors

/**
 * Everything the app cannot decide on its own, in one queue.
 *
 * These lived on the old Home tab, which the five-tab layout has no room for. A queue
 * is the better home anyway: each item is a question with an answer, and once answered
 * it leaves - which a dashboard section never did.
 */
@Composable
fun InboxScreen(vm: FinanceViewModel, state: UiState, onClose: () -> Unit) {
    BackHandler(onBack = onClose)

    val reconcile by vm.reconcileNeeded.collectAsState()
    val needsCategory by vm.needsCategory.collectAsState()
    val inboxCount by vm.inboxCount.collectAsState()
    val smsGranted = rememberSmsPermissionGranted()

    // Dismiss the shade notification once the user has entered the Inbox
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        AttentionPrompt.dismiss(context)
    }

    // Auto-close when the inbox becomes empty after user takes action
    var initialCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(inboxCount) {
        if (initialCount == null) {
            initialCount = inboxCount
        } else if (initialCount!! > 0 && inboxCount == 0) {
            onClose()
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(LocalMoneyColors.current.header)
                    .windowInsetsPadding(topBarInset)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("✕", modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
                Spacer(Modifier.width(8.dp))
                Text("Inbox", style = MaterialTheme.typography.titleLarge)
            }

            LazyColumn(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // First, and before anything else: an empty inbox means nothing until
                // you know the app is even listening to messages.
                if (!smsGranted) item { MissingSmsPermissionCard() }

                if (state.unconfirmed.isNotEmpty()) {
                    item { SectionTitle("New account seen in a message") }
                    items(state.unconfirmed, key = { "unconfirmed-${it.id}" }) { auto ->
                        UnconfirmedAccountCard(vm, auto, state.bankAccounts.filter { !it.needsConfirmation })
                    }
                }

                if (reconcile.isNotEmpty()) {
                    item { SectionTitle("Confirm your balance") }
                    items(reconcile, key = { "reconcile-${it.id}" }) { account ->
                        ReconcileCard(vm, account)
                    }
                }

                if (needsCategory.isNotEmpty()) {
                    item { SectionTitle("Needs a category (${needsCategory.size})") }
                    items(needsCategory, key = { "category-${it.id}" }) { txn ->
                        NeedsCategoryCard(vm, state, txn)
                    }
                }

                if (state.reviews.isNotEmpty()) {
                    item { SectionTitle("Messages the parser could not read (${state.reviews.size})") }
                    items(state.reviews, key = { "review-${it.id}" }) { review ->
                        ReviewCard(vm, state, review)
                    }
                }

                if (smsGranted && state.unconfirmed.isEmpty() && reconcile.isEmpty() &&
                    needsCategory.isEmpty() && state.reviews.isEmpty()
                ) {
                    item {
                        Text(
                            "Nothing waiting. Unreadable messages, unnamed accounts and unlabelled " +
                                "spending all surface here.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }

                item { Spacer(Modifier.height(48.dp)) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun NeedsCategoryCard(vm: FinanceViewModel, state: UiState, txn: Txn) {
    var menuOpen by remember { mutableStateOf(false) }
    var splitWith by remember { mutableStateOf<Long?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(txn.payee ?: txn.note ?: "(no payee)", fontWeight = FontWeight.Medium)
                    Text(
                        state.accountById(txn.accountId)?.displayName.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalMoneyColors.current.muted,
                    )
                }
                Text(
                    "-" + formatRupees(txn.amountPaise),
                    color = LocalMoneyColors.current.expense,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Box {
                OutlinedButton(onClick = { menuOpen = true }) { Text("Choose a category") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    state.expenseCategories.forEach { category ->
                        DropdownMenuItem(
                            text = { Text(category.name) },
                            leadingIcon = { IconBubble(category.iconKey, category.colourHex, size = 24.dp) },
                            onClick = {
                                menuOpen = false
                                // Same rule as Records: a category meaning somebody
                                // else's money asks how much of it was theirs.
                                if (state.splitsOnPick(category.id)) {
                                    splitWith = category.id
                                } else {
                                    vm.categorise(txn, category.id)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    splitWith?.let { categoryId ->
        SplitSheet(
            state = state,
            anchor = txn,
            totalPaise = txn.amountPaise,
            initialCategoryId = categoryId,
            existingParts = listOf(txn),
            onApply = { parts ->
                vm.applySplit(txn, parts)
                splitWith = null
            },
            onDismiss = { splitWith = null },
        )
    }
}

@Composable
private fun UnconfirmedAccountCard(vm: FinanceViewModel, auto: Account, declared: List<Account>) {
    var name by remember(auto.id) { mutableStateOf(auto.displayName) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Messages from ${auto.bank} ..${auto.accountToken}", fontWeight = FontWeight.Medium)
            Text(
                "Transactions are already being recorded. Name it, or attach it to an account you set up.",
                style = MaterialTheme.typography.bodySmall,
                color = LocalMoneyColors.current.muted,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
            Button(onClick = { vm.rename(auto, name) }) { Text("Save name") }
            declared.forEach { target ->
                OutlinedButton(onClick = { vm.bind(auto, target) }) {
                    Text("This is ${target.displayName}")
                }
            }
        }
    }
}

@Composable
private fun ReconcileCard(vm: FinanceViewModel, account: Account) {
    var input by remember(account.id) { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("${account.displayName}: check the real balance", fontWeight = FontWeight.Medium)
            Text(
                "This bank does not send a balance, so the figure here is calculated and drifts. " +
                    "Enter the real balance and the gap is recorded as a visible correction.",
                style = MaterialTheme.typography.bodySmall,
                color = LocalMoneyColors.current.muted,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Actual balance (₹)") },
            )
            Button(
                onClick = {
                    rupeesToPaise(input)?.let { vm.reconcile(account, it) }
                    input = ""
                },
                enabled = rupeesToPaise(input) != null,
            ) { Text("Confirm") }
        }
    }
}

/**
 * A message that looked financial but matched no rule. Two exits: record it by hand,
 * or mark it as not a transaction. Either way it leaves the tray - and the raw text
 * stays visible so it can be turned into a parser rule later.
 */
@Composable
private fun ReviewCard(vm: FinanceViewModel, state: UiState, review: PendingReview) {
    // A tray entry now arrives in one of two conditions. Either nothing could read the
    // message, which is what this tray has always been for, or a pattern read it perfectly
    // well but has not yet earned the right to act on its own. The second is a question with
    // a suggested answer, and showing it as a blank form would waste the work and invite a
    // typo into a row the app could already have filled correctly.
    val preview = remember(review.id, review.rawMessage) {
        if (review.patternId == null) null
        else (PREVIEW_PARSER.parse(review.sender, review.rawMessage, review.receivedAt)
            as? ParseResult.Parsed)?.sms
    }
    if (preview != null) {
        ConfirmCard(vm, review, preview)
        return
    }

    val prefill = remember(review.id, review.rawMessage) {
        com.yk.finance.parser.extractPreFill(review.rawMessage)
    }

    var amount by remember(review.id) { mutableStateOf(prefill.amountRupees) }
    var payee by remember(review.id) { mutableStateOf(prefill.payee) }
    val detectedAccountId = remember(review.id, state.bankAccounts) {
        val tokenMatch = state.bankAccounts.firstOrNull { acc ->
            acc.accountToken.length >= 3 && review.rawMessage.contains(acc.accountToken, ignoreCase = true)
        }
        if (tokenMatch != null) return@remember tokenMatch.id

        val bankFromSender = com.yk.finance.parser.bankForSender(review.sender)
            ?: com.yk.finance.parser.bankMentionedIn(review.rawMessage)
        if (bankFromSender != null) {
            state.bankAccounts.firstOrNull { it.bank.equals(bankFromSender, ignoreCase = true) }?.id
        } else {
            null
        }
    }
    var accountId by remember(review.id) { mutableStateOf(detectedAccountId) }
    var accountMenu by remember { mutableStateOf(false) }
    var categoryId by remember(review.id) { mutableStateOf<Long?>(null) }
    var categoryMenu by remember { mutableStateOf(false) }
    var isDebit by remember(review.id) { mutableStateOf(prefill.isDebit) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(review.sender, style = MaterialTheme.typography.labelMedium)
            Text(review.rawMessage, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Not recorded: ${review.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = LocalMoneyColors.current.muted,
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount (₹)") })
            OutlinedTextField(value = payee, onValueChange = { payee = it }, label = { Text("Payee") })

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    val selected = state.accountById(accountId)
                    OutlinedButton(onClick = { accountMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = selected?.displayName ?: "Choose account *",
                            color = if (selected == null) LocalMoneyColors.current.expense else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                    DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                        state.accounts.forEach { account ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        account.displayName + if (account.kind == com.yk.finance.data.AccountKind.CASH) " (Cash)" else "",
                                    )
                                },
                                onClick = { accountId = account.id; accountMenu = false },
                            )
                        }
                    }
                }

                Box(Modifier.weight(1f)) {
                    val selectedCat = categoryId?.let { state.categoryById(it) }
                    OutlinedButton(onClick = { categoryMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = selectedCat?.name ?: "Category (opt)",
                            maxLines = 1,
                        )
                    }
                    DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("None") },
                            onClick = { categoryId = null; categoryMenu = false },
                        )
                        state.expenseCategories.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat.name) },
                                leadingIcon = { IconBubble(cat.iconKey, cat.colourHex, size = 20.dp) },
                                onClick = { categoryId = cat.id; categoryMenu = false },
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { isDebit = true }) {
                    Text(if (isDebit) "Money out ✓" else "Money out")
                }
                OutlinedButton(onClick = { isDebit = false }) {
                    Text(if (!isDebit) "Money in ✓" else "Money in")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = rupeesToPaise(amount) != null && accountId != null,
                    onClick = {
                        val paise = rupeesToPaise(amount)
                        val target = accountId
                        if (paise != null && target != null) {
                            vm.recordFromReview(
                                reviewId = review.id,
                                accountId = target,
                                amountPaise = paise,
                                direction = if (isDebit) Direction.DEBIT else Direction.CREDIT,
                                payee = payee.ifBlank { null },
                                categoryId = categoryId,
                            )
                        }
                    },
                ) { Text("Record it") }
                OutlinedButton(onClick = { vm.dismissReview(review.id) }) { Text("Not a transaction") }
            }
        }
    }
}


/**
 * A parse waiting to be believed.
 *
 * Read-only on purpose. The choice being offered is not "what were the numbers" - the pattern
 * already answered that, and the original message is right there to check it against - but
 * "is this pattern right about my bank". Editable fields would blur those two questions, and
 * the second is the one whose answer the app keeps.
 *
 * Accepting files the payment and remembers the pattern, so this bank's messages of the same
 * shape stop asking. Declining files nothing and teaches nothing, which is the correct
 * outcome for a pattern that read the message wrongly.
 */
@Composable
private fun ConfirmCard(vm: FinanceViewModel, review: PendingReview, sms: ParsedSms) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(review.sender, style = MaterialTheme.typography.labelMedium)
            Text(review.rawMessage, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))

            Text(
                if (sms.direction == Direction.DEBIT) "Money out" else "Money in",
                style = MaterialTheme.typography.labelMedium,
                color = LocalMoneyColors.current.muted,
            )
            Text(
                formatRupees(sms.amountPaise),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                buildString {
                    append(sms.payee ?: "No payee named")
                    append("  ·  ")
                    append(sms.bank)
                    append(" ..")
                    append(sms.accountToken)
                    if (sms.isCard) append(" (card)")
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "First time reading this kind of message from ${sms.bank}. " +
                    "Confirm once and the app will file them itself from now on.",
                style = MaterialTheme.typography.bodySmall,
                color = LocalMoneyColors.current.muted,
            )
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.confirmReview(review.id) }) { Text("That is right") }
                OutlinedButton(onClick = { vm.dismissReview(review.id) }) { Text("No, discard") }
            }
        }
    }
}
