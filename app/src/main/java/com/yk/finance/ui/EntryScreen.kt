package com.yk.finance.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yk.finance.data.Txn
import com.yk.finance.data.TxnSource
import com.yk.finance.domain.Calculator
import com.yk.finance.domain.IST
import com.yk.finance.domain.Looks
import com.yk.finance.domain.formatRupees
import kotlinx.coroutines.launch
import com.yk.finance.parser.Direction
import com.yk.finance.ui.theme.LocalMoneyColors
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Hand entry and editing, on one screen.
 *
 * The keypad is a calculator because that is how a split bill gets entered without
 * reaching for another app - and every key it presses runs through BigDecimal, so
 * "700/3" books 233.33 exactly and says it rounded rather than quietly inventing a
 * fraction of a paisa.
 *
 * Editing an SMS-sourced row is allowed but not casual: the amount and direction the
 * bank reported are locked until you say otherwise, and the message stays on screen
 * underneath. Everything else - category, note, date - edits freely, because those are
 * the app's guesses rather than the bank's facts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryScreen(
    vm: FinanceViewModel,
    state: UiState,
    txnId: Long?,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)

    val money = LocalMoneyColors.current
    val chips by vm.quickAdd.collectAsState()
    val existing = remember(txnId, state.transactions) {
        txnId?.let { id -> state.transactions.firstOrNull { it.id == id } }
    }
    val isTransferLeg = existing?.transferGroupId != null ||
        existing?.source == TxnSource.TRANSFER_LEG
    val fromSms = existing?.source == TxnSource.SMS

    // Every row of this bill, so the sheet reopens holding the whole split rather than
    // the one part you happened to tap.
    val siblings = remember(existing, state.transactions) {
        existing?.splitGroupId?.let { group ->
            state.transactions.filter { it.splitGroupId == group && it.source != TxnSource.SETTLEMENT }
                .sortedBy { it.id }
        }.orEmpty()
    }
    val isSplitPart = siblings.size > 1
    var splitting by remember(existing) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var type by remember(existing) {
        mutableStateOf(
            when {
                isTransferLeg -> TypeFilter.TRANSFER
                existing?.direction == Direction.CREDIT -> TypeFilter.INCOME
                else -> TypeFilter.EXPENSE
            },
        )
    }
    var accountId by remember(existing) {
        mutableStateOf(existing?.accountId ?: state.cashAccount?.id ?: state.accounts.firstOrNull()?.id)
    }
    var toAccountId by remember(existing) { mutableStateOf<Long?>(null) }
    var categoryId by remember(existing) { mutableStateOf(existing?.categoryId) }
    var note by remember(existing) { mutableStateOf(existing?.note ?: existing?.payee.orEmpty()) }
    var expression by remember(existing) {
        mutableStateOf(
            existing?.let {
                val whole = it.amountPaise / 100
                val fraction = it.amountPaise % 100
                if (fraction == 0L) whole.toString() else "$whole.${fraction.toString().padStart(2, '0')}"
            } ?: "",
        )
    }
    // Stored millis are an instant; the ledger thinks in IST, and so must the picker -
    // a record entered at 12:30 am must not show as the previous day.
    var moment by remember(existing) {
        mutableStateOf(
            java.time.Instant.ofEpochMilli(existing?.occurredAt ?: System.currentTimeMillis())
                .atZone(IST)
                .toLocalDateTime(),
        )
    }
    var amountUnlocked by remember(existing) { mutableStateOf(!fromSms) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmUnlock by remember { mutableStateOf(false) }

    val evaluated = remember(expression) { Calculator.evaluate(expression) }
    val canSave = evaluated != null && evaluated.paise > 0 && accountId != null &&
        (type != TypeFilter.TRANSFER || (toAccountId != null && toAccountId != accountId))

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(money.header).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose) {
                    Icon(Icons.Default.Close, null)
                    Spacer(Modifier.width(6.dp))
                    Text("CANCEL")
                }
                Spacer(Modifier.weight(1f))
                // Offered on any expense, not just the two sharing categories: this is
                // the deliberate way in, for the bill you want divided between two of
                // your own categories rather than with another person.
                //
                // Enabled while recording a new one too, which matters most for cash:
                // cash sends no message, so there is nothing to come back to later. A
                // cash lunch you cannot divide here is one you never divide at all.
                val canSplit = when {
                    existing != null -> !isTransferLeg && existing.direction == Direction.DEBIT
                    else -> type == TypeFilter.EXPENSE && evaluated?.paise != null && accountId != null
                }
                if (canSplit) {
                    IconButton(onClick = { splitting = true }) {
                        Icon(Icons.Default.CallSplit, contentDescription = "Split this record")
                    }
                }
                if (existing != null) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete record")
                    }
                }
                TextButton(
                    enabled = canSave,
                    onClick = {
                        val paise = evaluated?.paise ?: return@TextButton
                        val account = accountId ?: return@TextButton
                        val occurredAt = moment.atZone(IST).toInstant().toEpochMilli()
                        if (existing == null) {
                            vm.saveEntry(
                                type = type,
                                accountId = account,
                                toAccountId = toAccountId,
                                amountPaise = paise,
                                categoryId = categoryId,
                                note = note.ifBlank { null },
                                occurredAt = occurredAt,
                            )
                        } else {
                            vm.updateEntry(
                                id = existing.id,
                                accountId = account,
                                direction = if (type == TypeFilter.INCOME) Direction.CREDIT else Direction.DEBIT,
                                amountPaise = if (amountUnlocked) paise else existing.amountPaise,
                                categoryId = categoryId,
                                note = note.ifBlank { null },
                                payee = existing.payee ?: note.ifBlank { null },
                                occurredAt = occurredAt,
                            )
                        }
                        onClose()
                    },
                ) {
                    Icon(Icons.Default.Check, null)
                    Spacer(Modifier.width(6.dp))
                    Text("SAVE")
                }
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                listOf(TypeFilter.INCOME, TypeFilter.EXPENSE, TypeFilter.TRANSFER).forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = type == option,
                        // A transfer leg cannot become an expense by tapping a chip:
                        // the other leg would be orphaned and the balances would drift.
                        enabled = existing == null || (isTransferLeg == (option == TypeFilter.TRANSFER)),
                        onClick = { type = option },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                    ) { Text(option.label, maxLines = 1, softWrap = false) }
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (existing == null && type == TypeFilter.EXPENSE && chips.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        chips.forEach { chip ->
                            SuggestionChip(
                                onClick = {
                                    // Fills the form rather than recording outright: the
                                    // amount is usually right, but not always, and the
                                    // keypad is right there to change it.
                                    expression = (chip.amountPaise / 100).toString() +
                                        if (chip.amountPaise % 100 == 0L) {
                                            ""
                                        } else {
                                            ".${(chip.amountPaise % 100).toString().padStart(2, '0')}"
                                        }
                                    categoryId = chip.categoryId
                                    accountId = chip.accountId
                                    note = chip.label
                                },
                                label = { Text(chipLabel(chip)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    PickerButton(
                        label = if (type == TypeFilter.TRANSFER) "From" else "Account",
                        value = state.accountById(accountId)?.displayName ?: "Account",
                        iconKey = state.accountById(accountId)?.iconKey ?: "wallet",
                        colourHex = state.accountById(accountId)?.colourHex ?: "#546E7A",
                        enabled = !isTransferLeg,
                        modifier = Modifier.weight(1f),
                    ) { dismiss ->
                        state.accounts.forEach { account ->
                            DropdownMenuItem(
                                text = { Text(account.displayName) },
                                leadingIcon = {
                                    IconBubble(
                                        account.iconKey ?: Looks.accountIcon(account.displayName, account.kind),
                                        account.colourHex ?: Looks.accountColour(account.displayName, account.kind),
                                        size = 24.dp,
                                    )
                                },
                                onClick = { accountId = account.id; dismiss() },
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    if (type == TypeFilter.TRANSFER) {
                        PickerButton(
                            label = "To",
                            value = state.accountById(toAccountId)?.displayName ?: "Account",
                            iconKey = state.accountById(toAccountId)?.iconKey ?: "wallet",
                            colourHex = state.accountById(toAccountId)?.colourHex ?: "#546E7A",
                            enabled = !isTransferLeg,
                            modifier = Modifier.weight(1f),
                        ) { dismiss ->
                            state.accounts.filter { it.id != accountId }.forEach { account ->
                                DropdownMenuItem(
                                    text = { Text(account.displayName) },
                                    onClick = { toAccountId = account.id; dismiss() },
                                )
                            }
                        }
                    } else {
                        // Most-used first: the picker is long, and the same handful of
                        // categories account for nearly every entry.
                        val categories = if (type == TypeFilter.INCOME) {
                            state.incomeCategories
                        } else {
                            rankedCategories(state)
                        }
                        PickerButton(
                            label = "Category",
                            value = categoryId?.let { state.categoryName(it) } ?: "Category",
                            iconKey = state.iconKeyFor(categoryId),
                            colourHex = state.colourFor(categoryId),
                            modifier = Modifier.weight(1f),
                        ) { dismiss ->
                            categories.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(category.name) },
                                    leadingIcon = {
                                        IconBubble(category.iconKey, category.colourHex, size = 24.dp)
                                    },
                                    onClick = { categoryId = category.id; dismiss() },
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Add notes") },
                    modifier = Modifier.fillMaxWidth().padding(12.dp).heightIn(min = 90.dp),
                )

                if (isTransferLeg) {
                    Text(
                        "This is one leg of a transfer. Its amount and accounts are fixed here so the " +
                            "two legs cannot drift apart; deleting it removes both.",
                        style = MaterialTheme.typography.bodySmall,
                        color = money.muted,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                existing?.rawMessage?.let { raw ->
                    Card(Modifier.fillMaxWidth().padding(12.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lock, null, Modifier.width(16.dp), tint = money.muted)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (amountUnlocked) "from SMS - amount unlocked" else "from SMS - amount locked",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = money.muted,
                                    modifier = Modifier.weight(1f),
                                )
                                if (!amountUnlocked) {
                                    TextButton(onClick = { confirmUnlock = true }) { Text("Override") }
                                }
                            }
                            Text(raw, style = MaterialTheme.typography.bodySmall, color = money.muted)
                        }
                    }
                }
            }

            AmountDisplay(
                expression = expression,
                evaluated = evaluated,
                locked = !amountUnlocked,
                onBackspace = { if (expression.isNotEmpty()) expression = expression.dropLast(1) },
            )

            Keypad(
                enabled = amountUnlocked,
                onKey = { key ->
                    expression = when (key) {
                        "=" -> Calculator.evaluate(expression)?.let { result ->
                            result.value.stripTrailingZeros().toPlainString()
                        } ?: expression
                        else -> expression + key
                    }
                },
            )

            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    moment.toLocalDate().format(FULL_DATE),
                    Modifier.weight(1f).clickable { showDate = true },
                    textAlign = TextAlign.Center,
                )
                Text("|", color = money.muted)
                Text(
                    moment.toLocalTime().format(TIME),
                    Modifier.weight(1f).clickable { showTime = true },
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    if (showDate) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = moment.toLocalDate()
                .atStartOfDay(IST).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { millis ->
                        val date = java.time.Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC).toLocalDate()
                        moment = LocalDateTime.of(date, moment.toLocalTime())
                    }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = picker) }
    }

    if (showTime) {
        val picker = rememberTimePickerState(
            initialHour = moment.hour,
            initialMinute = moment.minute,
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text("Time") },
            text = { TimePicker(state = picker) },
            confirmButton = {
                TextButton(onClick = {
                    moment = LocalDateTime.of(
                        moment.toLocalDate(),
                        LocalTime.of(picker.hour, picker.minute),
                    )
                    showTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } },
        )
    }

    if (confirmUnlock) {
        AlertDialog(
            onDismissRequest = { confirmUnlock = false },
            title = { Text("Change what the bank reported?") },
            text = {
                Text(
                    "This amount came from a bank message. Editing it means the ledger no longer " +
                        "matches what your bank said. The message itself stays on this screen.",
                )
            },
            confirmButton = {
                TextButton(onClick = { amountUnlocked = true; confirmUnlock = false }) {
                    Text("Change it anyway")
                }
            },
            dismissButton = { TextButton(onClick = { confirmUnlock = false }) { Text("Leave it") } },
        )
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (isSplitPart) "This is part of a split" else "Delete this record?") },
            text = {
                Text(
                    when {
                        // Deleting one part alone would leave the rest summing to less
                        // than the bank took, with nothing on screen to say so.
                        isSplitPart -> "Deleting one part on its own would leave the " +
                            "rest adding up to less than the " +
                            formatRupees(siblings.sumOf { it.amountPaise }) +
                            " that actually left your account. Un-split puts it back " +
                            "to one record."
                        isTransferLeg ->
                            "Both legs of the transfer are removed and both balances are repaired."
                        else ->
                            "The amount is added back to ${state.accountById(existing.accountId)?.displayName ?: "the account"}."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isSplitPart) {
                        val group = existing.splitGroupId
                        scope.launch {
                            // False when a repayment is attached: that credit is real
                            // money that arrived, and quietly unhooking it would either
                            // lose it or turn it back into income never earned.
                            if (group != null && vm.unsplit(group)) onClose()
                        }
                        confirmDelete = false
                    } else {
                        vm.deleteTransaction(existing.id)
                        confirmDelete = false
                        onClose()
                    }
                }) { Text(if (isSplitPart) "Un-split" else "Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    if (splitting) {
        val draftPaise = evaluated?.paise
        val draftAccount = accountId
        when {
            existing != null -> {
                val parts = if (siblings.isEmpty()) listOf(existing) else siblings
                SplitSheet(
                    state = state,
                    anchor = parts.first(),
                    totalPaise = parts.sumOf { it.amountPaise },
                    initialCategoryId = existing.categoryId,
                    existingParts = parts,
                    onApply = { drafted ->
                        vm.applySplit(parts.first(), drafted)
                        splitting = false
                        onClose()
                    },
                    onDismiss = { splitting = false },
                )
            }
            draftPaise != null && draftAccount != null -> {
                // Nothing is written until the sheet is saved, so the anchor here is a
                // description of what is about to be recorded rather than a row that
                // exists. Cancelling leaves the keypad exactly as you left it.
                val draft = Txn(
                    accountId = draftAccount,
                    direction = Direction.DEBIT,
                    amountPaise = draftPaise,
                    occurredAt = moment.atZone(IST).toInstant().toEpochMilli(),
                    payee = note.ifBlank { null },
                    reference = null,
                    countsAsSpending = true,
                    source = TxnSource.MANUAL,
                )
                SplitSheet(
                    state = state,
                    anchor = draft,
                    totalPaise = draftPaise,
                    initialCategoryId = categoryId,
                    existingParts = listOf(draft),
                    onApply = { drafted ->
                        vm.saveSplitEntry(
                            accountId = draftAccount,
                            amountPaise = draftPaise,
                            payee = note.ifBlank { null },
                            note = note.ifBlank { null },
                            occurredAt = draft.occurredAt,
                            parts = drafted,
                        )
                        splitting = false
                        onClose()
                    },
                    onDismiss = { splitting = false },
                )
            }
            else -> splitting = false
        }
    }
}

@Composable
private fun PickerButton(
    label: String,
    value: String,
    iconKey: String?,
    colourHex: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    menuContent: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = LocalMoneyColors.current.muted,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Box {
            OutlinedButton(
                onClick = { open = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconBubble(iconKey, colourHex, size = 22.dp)
                Spacer(Modifier.width(8.dp))
                Text(value, maxLines = 1)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                menuContent { open = false }
            }
        }
    }
}

@Composable
private fun AmountDisplay(
    expression: String,
    evaluated: Calculator.Result?,
    locked: Boolean,
    onBackspace: () -> Unit,
) {
    val money = LocalMoneyColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                expression.ifEmpty { "0" },
                style = MaterialTheme.typography.displaySmall,
                fontSize = 38.sp,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                maxLines = 1,
            )
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = onBackspace, enabled = !locked) {
                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Backspace")
            }
        }
        // Two things worth saying out loud: what an unfinished expression comes to,
        // and that a division did not land exactly on a paisa.
        if (evaluated != null && expression.any { it in "+-*/×÷" }) {
            Text(
                "= " + formatRupees(evaluated.paise),
                style = MaterialTheme.typography.bodyMedium,
                color = money.muted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
            )
        }
        if (evaluated?.rounded == true) {
            Text(
                "rounded to the nearest paisa",
                style = MaterialTheme.typography.bodySmall,
                color = money.expense,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun Keypad(enabled: Boolean, onKey: (String) -> Unit) {
    val rows = listOf(
        listOf("+", "7", "8", "9"),
        listOf("-", "4", "5", "6"),
        listOf("×", "1", "2", "3"),
        listOf("÷", "0", ".", "="),
    )
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { key ->
                    val isOperator = key in listOf("+", "-", "×", "÷", "=")
                    Surface(
                        color = if (isOperator) {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1.6f)
                            .padding(vertical = 3.dp)
                            .clickable(enabled = enabled) { onKey(key) },
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                key,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = if (isOperator) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}
