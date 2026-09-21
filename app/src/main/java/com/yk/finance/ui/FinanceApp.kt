package com.yk.finance.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yk.finance.FinanceApplication
import com.yk.finance.domain.formatRupees
import com.yk.finance.BuildConfig
import com.yk.finance.ui.theme.LocalMoneyColors
import kotlinx.coroutines.launch

private enum class Tab(val label: String) {
    RECORDS("Records"),
    ANALYSIS("Analysis"),
    BUDGETS("Budgets"),
    ACCOUNTS("Accounts"),
    CATEGORIES("Categories"),
}

/** Full-screen destinations that take over from the tabs. */
private sealed interface Route {
    data object Tabs : Route
    data class Entry(val txnId: Long?) : Route
    data object Search : Route
    data object Inbox : Route
    data object Import : Route
    data object Backup : Route
    data object Settings : Route
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceApp(app: FinanceApplication) {
    val vm: FinanceViewModel = viewModel(
        factory = FinanceViewModel.Factory(app.repository, app.budgets),
    )
    val state by vm.state.collectAsState()
    val inbox by vm.inboxCount.collectAsState()

    // RECEIVE_SMS is the whole product; POST_NOTIFICATIONS only gates budget alerts,
    // which on Android 13+ fail silently without it.
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    LaunchedEffect(Unit) {
        val wanted = buildList {
            add(Manifest.permission.RECEIVE_SMS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissions.launch(wanted.toTypedArray())
        vm.refreshDerived()
    }

    var tab by remember { mutableStateOf(Tab.RECORDS) }
    var route by remember { mutableStateOf<Route>(Route.Tabs) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val money = LocalMoneyColors.current

    // A chip records with no confirmation step, which is the whole speed argument.
    // The undo is what makes that safe rather than reckless.
    val snackbar = remember { SnackbarHostState() }
    val lastAdded by vm.lastAdded.collectAsState()
    LaunchedEffect(lastAdded) {
        if (lastAdded == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(message = "Recorded", actionLabel = "Undo")
        if (result == SnackbarResult.ActionPerformed) vm.undoLastAdd() else vm.clearUndo()
    }

    // Learning a payee used to be silent and permanent - you nudged a category while
    // fixing a note and changed what the app does with that shop forever, with nothing
    // on screen to say so. Naming it costs a line; not naming it cost trust.
    val learned by vm.learned.collectAsState()
    LaunchedEffect(learned) {
        val it = learned ?: return@LaunchedEffect
        val filed = if (it.backfilledIds.isEmpty()) {
            ""
        } else {
            " · ${it.backfilledIds.size} filed"
        }
        val result = snackbar.showSnackbar(
            message = "${it.payeeKey} → ${state.categoryName(it.categoryId)}$filed",
            actionLabel = "Undo",
        )
        if (result == SnackbarResult.ActionPerformed) vm.undoLearned() else vm.dismissLearned()
    }

    when (val current = route) {
        is Route.Entry -> {
            EntryScreen(vm, state, txnId = current.txnId, onClose = { route = Route.Tabs })
            return
        }
        Route.Search -> {
            SearchScreen(vm, state, onOpen = { route = Route.Entry(it) }, onClose = { route = Route.Tabs })
            return
        }
        Route.Inbox -> {
            InboxScreen(vm, state, onClose = { route = Route.Tabs })
            return
        }
        Route.Import -> {
            DrawerPage("Import", onClose = { route = Route.Tabs }) { ImportSection(vm) }
            return
        }
        Route.Backup -> {
            DrawerPage("Backup", onClose = { route = Route.Tabs }) { BackupSection(vm) }
            return
        }
        Route.Settings -> {
            DrawerPage("Settings", onClose = { route = Route.Tabs }) { SettingsSection(vm, state) }
            return
        }
        Route.Tabs -> Unit
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = money.header) {
                // Name over version, the way the reference app titles its drawer. The
                // inset padding sits inside the sheet so the drawer's own colour still
                // reaches the top of the screen behind the status bar.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(start = 28.dp, end = 24.dp, top = 20.dp, bottom = 16.dp),
                ) {
                    Text(
                        "Finance Manager",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        BuildConfig.VERSION_NAME,
                        style = MaterialTheme.typography.bodyMedium,
                        color = money.muted,
                    )
                }
                HorizontalDivider()

                // Ungrouped and above the sections: the only entry that configures the
                // app rather than acting on your ledger.
                DrawerItem(Icons.Default.Settings, "Settings") {
                    scope.launch { drawer.close() }
                    route = Route.Settings
                }
                HorizontalDivider()

                DrawerSection("Management")
                DrawerItem(Icons.Default.Inbox, "Inbox", badgeCount = inbox) {
                    scope.launch { drawer.close() }
                    route = Route.Inbox
                }
                DrawerItem(Icons.Default.UploadFile, "Import") {
                    scope.launch { drawer.close() }
                    route = Route.Import
                }
                DrawerItem(Icons.Default.Backup, "Backup & restore") {
                    scope.launch { drawer.close() }
                    route = Route.Backup
                }
            }
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            // The header belongs in the topBar slot, not in content. In content the
            // Scaffold's own inset padding pushed it below the status bar and left
            // that strip unpainted; here its colour reaches the top edge.
            topBar = {
                AppHeader(
                    vm = vm,
                    tab = tab,
                    onMenu = { scope.launch { drawer.open() } },
                    onSearch = { route = Route.Search },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { route = Route.Entry(null) }) {
                    Icon(Icons.Default.Add, contentDescription = "Add a record")
                }
            },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t; vm.refreshDerived() },
                            icon = {
                                Icon(
                                    when (t) {
                                        Tab.RECORDS -> Icons.AutoMirrored.Filled.List
                                        Tab.ANALYSIS -> Icons.Default.PieChart
                                        Tab.BUDGETS -> Icons.Default.Warning
                                        Tab.ACCOUNTS -> Icons.Default.AccountBalanceWallet
                                        Tab.CATEGORIES -> Icons.Default.Sell
                                    },
                                    null,
                                )
                            },
                            label = { Text(t.label, maxLines = 1) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                when (tab) {
                    Tab.RECORDS -> RecordsScreen(
                        vm = vm,
                        state = state,
                        onOpenInbox = { route = Route.Inbox },
                        onOpenRecord = { route = Route.Entry(it) },
                    )
                    Tab.ANALYSIS -> AnalysisScreen(vm)
                    Tab.BUDGETS -> BudgetsScreen(vm, state)
                    Tab.ACCOUNTS -> AccountsScreen(vm, state)
                    Tab.CATEGORIES -> CategoriesScreen(vm, state)
                }
            }
        }
    }
}

/**
 * Title bar, month strip and the month's totals - one block, because they are read as
 * one: the figures mean nothing without the month name directly above them.
 *
 * On Records the figures follow the filter, and a line names what is applied. Analysis
 * and Budgets keep the whole month's figures, because their contents are unfiltered and
 * a header claiming otherwise would be describing a list that is not there.
 *
 * Accounts is the exception to all of it. Its numbers are all-time, so showing a month
 * over them would attach a period to figures that do not have one.
 */
@Composable
private fun AppHeader(
    vm: FinanceViewModel,
    tab: Tab,
    onMenu: () -> Unit,
    onSearch: () -> Unit,
) {
    val money = LocalMoneyColors.current
    val period by vm.period.collectAsState()
    val totals by vm.totals.collectAsState()
    val filteredTotals by vm.filteredTotals.collectAsState()
    val progress by vm.progress.collectAsState()
    val hasPrevious by vm.hasPreviousPeriod.collectAsState()
    val hasNext by vm.hasNextPeriod.collectAsState()
    val filter by vm.filter.collectAsState()
    var filterOpen by remember { mutableStateOf(false) }
    val state by vm.state.collectAsState()

    // Records is the only tab the filter narrows, so it is the only tab whose header
    // may show narrowed figures.
    val filtering = tab == Tab.RECORDS && filter.isActive

    // Background first, inset padding inside it: the header colour runs under the
    // status bar and the notch while the controls sit clear of them. Padding the
    // whole Column instead leaves a bare strip above the header.
    Column(
        Modifier
            .fillMaxWidth()
            .background(money.header)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, contentDescription = "Menu") }
            Text(
                "Finance",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = onSearch) { Icon(Icons.Default.Search, contentDescription = "Search") }
        }

        if (tab != Tab.ACCOUNTS) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.showPreviousPeriod() }, enabled = hasPrevious) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Previous period")
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        period?.label ?: "-",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    period?.let { MonthSubtitle(it) }
                    if (filtering) FilterLine(filter, state) { vm.clearFilter() }
                }
                IconButton(onClick = { vm.showNextPeriod() }, enabled = hasNext) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Next period")
                }
                if (tab == Tab.RECORDS) {
                    IconButton(onClick = { filterOpen = true }) {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = "Filter",
                            tint = if (filter.isActive) money.income else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        when (tab) {
            Tab.BUDGETS -> {
                val totalBudget = progress.filter { it.categoryId != null }.sumOf { it.limitPaise }
                val totalSpent = progress.filter { it.categoryId != null }.sumOf { it.spentPaise }
                Row(Modifier.fillMaxWidth().padding(12.dp)) {
                    HeaderFigure("TOTAL BUDGET", formatRupees(totalBudget), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                    HeaderFigure("TOTAL SPENT", formatRupees(totalSpent), money.expense, Modifier.weight(1f))
                }
            }
            Tab.ACCOUNTS -> Unit
            else -> {
                val shown = if (filtering) filteredTotals else totals
                Row(Modifier.fillMaxWidth().padding(12.dp)) {
                    HeaderFigure("EXPENSE", formatRupees(shown.expensePaise), money.expense, Modifier.weight(1f))
                    HeaderFigure("INCOME", formatRupees(shown.incomePaise), money.income, Modifier.weight(1f))
                    HeaderFigure(
                        "TOTAL",
                        formatRupees(shown.netPaise),
                        if (shown.netPaise < 0) money.expense else money.income,
                        Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (filterOpen) {
        FilterSheet(
            state = state,
            filter = filter,
            onApply = { vm.setFilter(it); filterOpen = false },
            onDismiss = { filterOpen = false },
        )
    }
}

@Composable
private fun MonthSubtitle(period: com.yk.finance.domain.Period) {
    val money = LocalMoneyColors.current
    val text = monthSubtitle(period, System.currentTimeMillis()) ?: return
    Text(text, style = MaterialTheme.typography.bodySmall, color = money.muted)
}

/**
 * Names the active filter directly under the month, with its own way out.
 *
 * This is what lets the filter survive a month change without becoming a trap. The
 * figures above it are the filtered figures, so something has to say out loud what
 * they have been narrowed to - and offer to undo it without a trip through the sheet.
 */
@Composable
private fun FilterLine(filter: RecordFilter, state: UiState, onClear: () -> Unit) {
    val money = LocalMoneyColors.current
    val parts = buildList {
        if (filter.type != TypeFilter.ALL) add(filter.type.label)
        filter.categoryId?.let { add(state.categoryName(it)) }
        filter.accountId?.let { add(state.accountById(it)?.displayName ?: "Unknown account") }
        if (filter.uncategorisedOnly) add("Uncategorised")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            parts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = money.income,
        )
        Text(
            "Clear",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = money.income,
            modifier = Modifier.clickable(onClick = onClear).padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
        )
    }
}

@Composable
private fun HeaderFigure(label: String, value: String, colour: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = LocalMoneyColors.current.muted)
        Text(value, style = MaterialTheme.typography.titleMedium, color = colour, fontWeight = FontWeight.Bold)
    }
}

/** A drawer destination wrapped in its own title bar and back gesture. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrawerPage(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onClose)
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(LocalMoneyColors.current.header)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("✕", modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { content() }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

/**
 * A muted group heading. The reference app splits its drawer into named blocks, which
 * is what stops a flat list reading as several unrelated things.
 */
@Composable
private fun DrawerSection(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = LocalMoneyColors.current.muted,
        modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** One drawer row, so every entry gets the same padding and badge treatment. */
@Composable
private fun DrawerItem(
    icon: ImageVector,
    label: String,
    badgeCount: Int = 0,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(label) },
        icon = { Icon(icon, null) },
        badge = { if (badgeCount > 0) Badge { Text("$badgeCount") } },
        selected = false,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}
