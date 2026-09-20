package com.yk.finance.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.yk.finance.data.Sharing
import com.yk.finance.data.Txn
import com.yk.finance.domain.SplitPart
import com.yk.finance.domain.Splitter
import com.yk.finance.domain.formatRupees
import com.yk.finance.ui.theme.LocalMoneyColors

/**
 * One line of the sheet while you are still typing it.
 *
 * The amount is held as the text you typed rather than as paise, so a half-finished
 * "12." is a state the sheet can be in. Converting on every keystroke would either
 * reject it or silently read it as 12.00, and the second is worse.
 */
private data class DraftPart(
    val categoryId: Long?,
    val amountText: String,
    val owedBy: String,
) {
    val paise: Long get() = rupeesToPaise(amountText) ?: 0L
}

/**
 * Divides one payment into the several things it actually was.
 *
 * Opens already holding the whole amount on the category you just picked, so the
 * common case - the entire 320 really was for your friend - is still a single tap on
 * Save. You only type a number when the bill was genuinely shared.
 *
 * The running remainder is the point of the screen. A split that does not add up to
 * what the bank took leaves a ledger disagreeing with the account it describes, and
 * every part would still look like a perfectly ordinary transaction, so nothing
 * downstream would ever surface the discrepancy. Save stays disabled until it is zero.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitSheet(
    state: UiState,
    anchor: Txn,
    totalPaise: Long,
    initialCategoryId: Long?,
    existingParts: List<Txn>,
    onApply: (List<SplitPart>) -> Unit,
    onDismiss: () -> Unit,
) {
    val money = LocalMoneyColors.current

    var parts by remember {
        mutableStateOf(
            if (existingParts.size > 1) {
                existingParts.map {
                    DraftPart(it.categoryId, paiseToPlain(it.amountPaise), it.owedBy.orEmpty())
                }
            } else {
                listOf(
                    DraftPart(
                        categoryId = initialCategoryId,
                        amountText = paiseToPlain(totalPaise),
                        owedBy = existingParts.firstOrNull()?.owedBy.orEmpty(),
                    ),
                )
            },
        )
    }

    val drafted = parts.map { SplitPart(it.categoryId, it.paise, it.owedBy) }
    val remainder = Splitter.remainder(totalPaise, drafted)
    // Every part needs a category as well as an amount. An unlabelled part is money
    // that left your account and landed nowhere you can see - which is the state this
    // whole screen exists to get you out of.
    val named = drafted.all { it.categoryId != null }
    val balanced = Splitter.isBalanced(totalPaise, drafted) && named

    /**
     * Rebalances the first line against everything below it.
     *
     * Typing 120 for your friend drops your own share to 200 without you touching it,
     * which is the arithmetic you would otherwise be doing in your head at a restaurant
     * table. Only the *first* line is rebalanced, and only in response to editing one
     * of the others - so a number you are actively typing is never rewritten under
     * your fingers, and the remainder is left to go red if you overshoot the bill
     * rather than being quietly absorbed.
     */
    fun rebalance(next: List<DraftPart>): List<DraftPart> {
        if (next.isEmpty()) return next
        // Down to one line, that line is the whole bill by definition - removing the
        // other part must not leave a survivor still holding only its own share.
        if (next.size == 1) return listOf(next.first().copy(amountText = paiseToPlain(totalPaise)))
        val others = next.drop(1).sumOf { it.paise }
        val head = totalPaise - others
        if (head < 0) return next
        return listOf(next.first().copy(amountText = paiseToPlain(head))) + next.drop(1)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Split ${formatRupees(totalPaise)}", style = MaterialTheme.typography.titleLarge)
            anchor.payee?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = money.muted)
            }

            parts.forEachIndexed { index, part ->
                PartRow(
                    state = state,
                    part = part,
                    canRemove = parts.size > 1,
                    onChange = { updated ->
                        val next = parts.toMutableList().also { it[index] = updated }
                        // Editing the top line is you overriding your own share, so it
                        // stands and the remainder reports the consequence. Editing any
                        // other line is you stating somebody else's, so yours gives way.
                        parts = if (index == 0) next else rebalance(next)
                    },
                    onRemove = {
                        // The removed line's money goes back to the first part instead
                        // of vanishing, so deleting a line never leaves the sheet
                        // quietly unbalanced.
                        parts = rebalance(parts.toMutableList().also { it.removeAt(index) })
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            TextButton(onClick = {
                // Starts empty rather than pre-filled: the number is about to be typed,
                // and a guess sitting in the field is a guess you have to clear first.
                parts = parts + DraftPart(categoryId = null, amountText = "", owedBy = "")
            }) {
                Icon(Icons.Default.Add, null, Modifier.width(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add part")
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Left to assign",
                    style = MaterialTheme.typography.bodyMedium,
                    color = money.muted,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatRupees(remainder),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (remainder == 0L) money.income else money.expense,
                )
            }

            if (!balanced && remainder == 0L) {
                Text(
                    if (!named) {
                        "Every part needs a category."
                    } else {
                        "Every part needs an amount above zero."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = money.expense,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    enabled = balanced,
                    onClick = { onApply(drafted) },
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun PartRow(
    state: UiState,
    part: DraftPart,
    canRemove: Boolean,
    onChange: (DraftPart) -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var peopleOpen by remember { mutableStateOf(false) }
    val category = state.categoryById(part.categoryId)
    val lent = state.sharingOf(part.categoryId) == Sharing.LENT

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth().clickable { menuOpen = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBubble(
                        iconKey = category?.iconKey ?: state.iconKeyFor(part.categoryId),
                        colourHex = category?.colourHex ?: state.colourFor(part.categoryId),
                        size = 28.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        category?.name ?: "Choose a category",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (category == null) {
                            LocalMoneyColors.current.muted
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    state.expenseCategories.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.name) },
                            leadingIcon = { IconBubble(option.iconKey, option.colourHex, size = 24.dp) },
                            onClick = {
                                onChange(part.copy(categoryId = option.id))
                                menuOpen = false
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = part.amountText,
                onValueChange = { onChange(part.copy(amountText = it)) },
                label = { Text("Amount") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(140.dp),
            )

            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Close, contentDescription = "Remove this part")
                }
            }
        }

        // Only asked for where it means something. A name on a part nobody owes you is
        // a fact the app would keep and never be able to use.
        if (lent) {
            Box {
                OutlinedTextField(
                    value = part.owedBy,
                    onValueChange = { onChange(part.copy(owedBy = it)) },
                    label = { Text("Owed by") },
                    singleLine = true,
                    trailingIcon = {
                        if (state.knownPeople.isNotEmpty()) {
                            IconButton(onClick = { peopleOpen = true }) {
                                Icon(Icons.Default.Add, contentDescription = "Someone you've named before")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                DropdownMenu(expanded = peopleOpen, onDismissRequest = { peopleOpen = false }) {
                    state.knownPeople.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                onChange(part.copy(owedBy = name))
                                peopleOpen = false
                            },
                        )
                    }
                }
            }
        }
    }
}
