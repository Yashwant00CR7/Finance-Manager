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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.yk.finance.data.Category
import com.yk.finance.domain.Ledger
import com.yk.finance.domain.Looks
import com.yk.finance.domain.formatRupees
import com.yk.finance.ui.theme.LocalMoneyColors

/**
 * The category list, and the only place the learned payee rules are ever visible.
 *
 * Those rules are written silently on every correction and have never had a screen, so
 * a mapping learned from one odd payee keeps filing spending forever with nothing to
 * show for it. Here they are listed under the category they feed, and removable.
 */
@Composable
fun CategoriesScreen(vm: FinanceViewModel, state: UiState) {
    val rows by vm.periodTransactions.collectAsState()
    var showIncome by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Category?>(null) }
    var adding by remember { mutableStateOf(false) }
    val money = LocalMoneyColors.current

    val totals = remember(rows, showIncome) {
        rows.filter { if (showIncome) Ledger.isIncome(it) else Ledger.isExpense(it) }
            .groupBy { it.categoryId }
            .mapValues { (_, list) -> list.sumOf { it.amountPaise } }
    }
    val categories = if (showIncome) state.incomeCategories else state.expenseCategories

    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    SegmentedButton(
                        selected = !showIncome,
                        onClick = { showIncome = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("EXPENSE") }
                    SegmentedButton(
                        selected = showIncome,
                        onClick = { showIncome = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("INCOME") }
                }
                IconButton(onClick = { adding = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add category")
                }
            }
            HorizontalDivider()
        }

        items(categories, key = { it.id }) { category ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { editing = category }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBubble(
                    category.iconKey ?: Looks.categoryIcon(category.name),
                    category.colourHex ?: Looks.categoryColour(category.name),
                )
                Spacer(Modifier.width(14.dp))
                Text(category.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                val spent = totals[category.id] ?: 0L
                Text(
                    (if (showIncome || spent == 0L) "" else "-") + formatRupees(spent),
                    color = when {
                        spent == 0L -> money.muted
                        showIncome -> money.income
                        else -> money.expense
                    },
                )
                Icon(Icons.Default.ChevronRight, null, tint = money.muted)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        item { Spacer(Modifier.height(96.dp)) }
    }

    editing?.let { category ->
        CategoryDetail(
            vm = vm,
            state = state,
            category = category,
            onDismiss = { editing = null },
        )
    }

    if (adding) {
        AddCategoryDialog(
            isIncome = showIncome,
            onDismiss = { adding = false },
            onAdd = { name, income -> vm.createCategory(name, income); adding = false },
        )
    }
}

@Composable
private fun CategoryDetail(
    vm: FinanceViewModel,
    state: UiState,
    category: Category,
    onDismiss: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    val rules = state.rules.filter { it.categoryId == category.id }

    LookEditor(
        title = category.name,
        initialName = category.name,
        initialIcon = category.iconKey ?: Looks.categoryIcon(category.name),
        initialColour = category.colourHex ?: Looks.categoryColour(category.name),
        extraContent = {
            Column {
                Text("Learned from", style = MaterialTheme.typography.labelLarge)
                if (rules.isEmpty()) {
                    Text(
                        "Nothing yet. Correcting a transaction's category teaches the payee, and " +
                            "it shows up here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalMoneyColors.current.muted,
                    )
                }
                rules.forEach { rule ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(rule.payeeKey, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { vm.deleteRule(rule.id) }) {
                            Icon(Icons.Default.Close, contentDescription = "Forget this rule")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { confirmingDelete = true }) {
                    Text("Delete category")
                }
            }
        },
        onDismiss = onDismiss,
        onSave = { name, icon, colour ->
            vm.updateCategory(category, name, icon, colour)
            onDismiss()
        },
    )

    if (confirmingDelete) {
        DeleteCategoryDialog(
            state = state,
            category = category,
            onDismiss = { confirmingDelete = false },
            onDelete = { moveTo ->
                vm.deleteCategory(category, moveTo)
                confirmingDelete = false
                onDismiss()
            },
        )
    }
}

/**
 * Deleting a category demands somewhere for its history to go.
 *
 * Not optional: rows left holding a deleted id read as spending that lost its label for
 * no reason, and the learned rules would keep filing new spending into a category that
 * no longer exists.
 */
@Composable
private fun DeleteCategoryDialog(
    state: UiState,
    category: Category,
    onDismiss: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    val options = state.categories.filter { it.id != category.id && it.isIncome == category.isIncome }
    var target by remember { mutableStateOf(options.firstOrNull()?.id) }
    var menuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${category.name}") },
        text = {
            Column {
                Text(
                    "Its transactions and learned rules move to the category you pick. Nothing is " +
                        "deleted except the category itself.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Box {
                    OutlinedButton(onClick = { menuOpen = true }) {
                        Text(target?.let { state.categoryName(it) } ?: "Nothing to move to")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        options.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.name) },
                                onClick = { target = option.id; menuOpen = false },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { target?.let(onDelete) },
                enabled = target != null,
            ) { Text("Move and delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddCategoryDialog(
    isIncome: Boolean,
    onDismiss: () -> Unit,
    onAdd: (String, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isIncome) "New income category" else "New expense category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                )
                Text(
                    "Its icon and colour are picked from the name and can be changed afterwards.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(name, isIncome) }, enabled = name.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
