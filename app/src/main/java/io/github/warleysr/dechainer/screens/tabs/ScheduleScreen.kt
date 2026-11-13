package io.github.warleysr.dechainer.screens.tabs

import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.models.AppGroup
import io.github.warleysr.dechainer.models.AppItem
import io.github.warleysr.dechainer.models.TimeWindow
import io.github.warleysr.dechainer.screens.common.RecoveryGateDialog
import io.github.warleysr.dechainer.screens.common.isLooserWindows
import io.github.warleysr.dechainer.screens.common.rememberRecoveryGate
import io.github.warleysr.dechainer.viewmodels.AppsViewModel
import io.github.warleysr.dechainer.viewmodels.NavigationViewModel
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** What a schedule is attached to: one app, or a whole group (the rule then applies to all its apps). */
private sealed interface ScheduleTarget {
    val windows: List<TimeWindow>

    data class App(val item: AppItem) : ScheduleTarget {
        override val windows get() = item.timeWindows
    }

    data class Group(val group: AppGroup) : ScheduleTarget {
        override val windows get() = group.timeWindows
    }
}

/** The Schedule tab: allowed hours (blocked outside them) and downtime (suspended during them). */
@Composable
fun ScheduleTab(navViewModel: NavigationViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Opening an app's schedule from the Apps screen lands on the allowed-hours editor.
    LaunchedEffect(navViewModel.pendingScheduleKey) {
        if (navViewModel.pendingScheduleKey != null) tab = 0
    }
    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.schedule_tab_hours)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.schedule_tab_downtime)) })
        }
        Box(modifier = Modifier.weight(1f)) {
            if (tab == 0) ScheduleScreen() else DowntimeScreen()
        }
    }
}

@Composable
fun ScheduleScreen(viewModel: AppsViewModel = viewModel(), navViewModel: NavigationViewModel = viewModel()) {
    val recoveryGate = rememberRecoveryGate()
    var picking by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(navViewModel.pendingScheduleKey) {
        navViewModel.pendingScheduleKey?.let {
            editingKey = it
            navViewModel.pendingScheduleKey = null
        }
    }

    // Re-resolved from the view model on every change, so the editor and the list never go stale.
    fun targetFor(key: String): ScheduleTarget? = when {
        key.startsWith("app:") -> viewModel.apps.firstOrNull { it.packageName == key.removePrefix("app:") }
            ?.let { ScheduleTarget.App(it) }
        key.startsWith("group:") -> viewModel.groups.firstOrNull { it.id == key.removePrefix("group:") }
            ?.let { ScheduleTarget.Group(it) }
        else -> null
    }

    fun save(target: ScheduleTarget, windows: List<TimeWindow>) {
        recoveryGate.change(isLooserWindows(target.windows, windows)) {
            when (target) {
                is ScheduleTarget.App -> viewModel.setAppTimeWindows(target.item.packageName, windows)
                is ScheduleTarget.Group -> viewModel.setGroupTimeWindows(target.group.id, windows)
            }
        }
    }

    val scheduledGroups = viewModel.groups.filter { it.timeWindows.isNotEmpty() }
    val scheduledApps = viewModel.apps.filter { it.timeWindows.isNotEmpty() }

    Box(modifier = Modifier.fillMaxSize()) {
        if (scheduledGroups.isEmpty() && scheduledApps.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.Schedule, null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.schedule_empty_title),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.schedule_empty_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(scheduledGroups, key = { "group:${it.id}" }) { group ->
                    ScheduleCard(
                        target = ScheduleTarget.Group(group),
                        onOpen = { editingKey = "group:${group.id}" },
                        onToggle = { index, enabled ->
                            save(ScheduleTarget.Group(group), group.timeWindows.toggled(index, enabled))
                        }
                    )
                }
                items(scheduledApps, key = { "app:${it.packageName}" }) { app ->
                    ScheduleCard(
                        target = ScheduleTarget.App(app),
                        onOpen = { editingKey = "app:${app.packageName}" },
                        onToggle = { index, enabled ->
                            save(ScheduleTarget.App(app), app.timeWindows.toggled(index, enabled))
                        }
                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { picking = true },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text(stringResource(R.string.schedule_add)) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        )
    }

    if (picking) {
        TargetPickerDialog(
            // Only apps with a launcher icon: the rest are background components nobody opens.
            apps = viewModel.apps.filter { it.isLaunchable },
            groups = viewModel.groups,
            onPick = { key -> picking = false; editingKey = key },
            onDismiss = { picking = false }
        )
    }

    editingKey?.let { key ->
        val target = targetFor(key)
        if (target == null) {
            editingKey = null
        } else {
            ScheduleEditor(
                target = target,
                onDismiss = { editingKey = null },
                onSave = { windows ->
                    save(target, windows)
                    editingKey = null
                }
            )
        }
    }

    RecoveryGateDialog(recoveryGate)
}

private fun List<TimeWindow>.toggled(index: Int, enabled: Boolean) =
    mapIndexed { i, w -> if (i == index) w.copy(enabled = enabled) else w }

@Composable
private fun ScheduleCard(
    target: ScheduleTarget,
    onOpen: () -> Unit,
    onToggle: (index: Int, enabled: Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TargetIcon(target)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        targetName(target),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (target is ScheduleTarget.Group) {
                        Text(
                            stringResource(R.string.schedule_group_apps, target.group.packageNames.size),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            target.windows.forEachIndexed { index, window ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            window.formatted(),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (window.enabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                        Text(daysSummary(window.days), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = window.enabled, onCheckedChange = { onToggle(index, it) })
                }
            }
        }
    }
}

@Composable
private fun targetName(target: ScheduleTarget) = when (target) {
    is ScheduleTarget.App -> target.item.name
    is ScheduleTarget.Group -> target.group.name
}

@Composable
private fun TargetIcon(target: ScheduleTarget) {
    when (target) {
        is ScheduleTarget.App -> AppIcon(target.item)
        is ScheduleTarget.Group -> Icon(
            Icons.Filled.Group, null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
internal fun AppIcon(app: AppItem) {
    val bitmap = remember(app.packageName) { app.icon.toBitmap(96, 96).asImageBitmap() }
    Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
}

@Composable
internal fun daysSummary(days: Set<Int>): String {
    val weekdays = setOf(1, 2, 3, 4, 5)
    val weekend = setOf(6, 7)
    return when {
        days.containsAll(TimeWindow.ALL_DAYS) -> stringResource(R.string.schedule_every_day)
        days == weekdays -> stringResource(R.string.schedule_weekdays)
        days == weekend -> stringResource(R.string.schedule_weekends)
        days.isEmpty() -> stringResource(R.string.schedule_no_days)
        else -> days.sorted().joinToString(", ") {
            DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault())
        }
    }
}

@Composable
private fun TargetPickerDialog(
    apps: List<AppItem>,
    groups: List<AppGroup>,
    onPick: (key: String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filteredGroups = remember(query, groups) {
        groups.filter { it.name.contains(query, ignoreCase = true) }
    }
    val filteredApps = remember(query, apps) {
        apps.filter { it.name.contains(query, ignoreCase = true) }.sortedBy { it.name.lowercase() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schedule_pick_target)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.search_apps)) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(filteredGroups, key = { "group:${it.id}" }) { group ->
                        PickerRow(
                            onClick = { onPick("group:${group.id}") },
                            icon = { TargetIcon(ScheduleTarget.Group(group)) },
                            title = group.name,
                            subtitle = stringResource(R.string.schedule_group_apps, group.packageNames.size)
                        )
                    }
                    items(filteredApps, key = { "app:${it.packageName}" }) { app ->
                        PickerRow(
                            onClick = { onPick("app:${app.packageName}") },
                            icon = { AppIcon(app) },
                            title = app.name,
                            subtitle = null
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun PickerRow(onClick: () -> Unit, icon: @Composable () -> Unit, title: String, subtitle: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal data class Preset(val labelRes: Int, val window: TimeWindow)

private val PRESETS = listOf(
    Preset(R.string.schedule_preset_evenings, TimeWindow(18 * 60, 22 * 60)),
    Preset(R.string.schedule_preset_after_work, TimeWindow(17 * 60, 22 * 60, setOf(1, 2, 3, 4, 5))),
    Preset(R.string.schedule_preset_weekends, TimeWindow(8 * 60, 22 * 60, setOf(6, 7)))
)

@Composable
private fun ScheduleEditor(
    target: ScheduleTarget,
    onDismiss: () -> Unit,
    onSave: (List<TimeWindow>) -> Unit
) {
    val windows = remember { mutableStateListOf(*target.windows.toTypedArray()) }
    var showErrors by remember { mutableStateOf(false) }

    fun isValid(w: TimeWindow) = w.startMinute != w.endMinute && w.days.isNotEmpty()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.safeDrawingPadding()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) }
                    Text(
                        targetName(target),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        if (windows.all(::isValid)) onSave(windows.toList()) else showErrors = true
                    }) { Text(stringResource(R.string.confirm)) }
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        stringResource(R.string.schedule_editor_hint),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Text(stringResource(R.string.schedule_presets), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PRESETS.forEach { preset ->
                            AssistChip(
                                onClick = { windows.add(preset.window) },
                                label = { Text(stringResource(preset.labelRes)) }
                            )
                        }
                    }

                    windows.forEachIndexed { index, window ->
                        SlotCard(
                            window = window,
                            showError = showErrors && !isValid(window),
                            onChange = { windows[index] = it },
                            onDelete = { windows.removeAt(index) }
                        )
                    }

                    OutlinedButton(
                        onClick = { windows.add(TimeWindow(18 * 60, 22 * 60)) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.schedule_add_slot))
                    }

                    if (windows.isEmpty()) {
                        Text(
                            stringResource(R.string.schedule_remove_all_hint),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SlotCard(
    window: TimeWindow,
    showError: Boolean,
    onChange: (TimeWindow) -> Unit,
    onDelete: () -> Unit
) {
    var pickingStart by remember { mutableStateOf(false) }
    var pickingEnd by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeButton(
                    label = stringResource(R.string.start_time),
                    minute = window.startMinute,
                    onClick = { pickingStart = true },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                TimeButton(
                    label = stringResource(R.string.end_time),
                    minute = window.endMinute,
                    onClick = { pickingEnd = true },
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.remove))
                }
            }

            if (window.endMinute < window.startMinute) {
                Text(stringResource(R.string.schedule_until_next_day), style = MaterialTheme.typography.bodySmall)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                (1..7).forEach { day ->
                    FilterChip(
                        selected = day in window.days,
                        onClick = {
                            val days = if (day in window.days) window.days - day else window.days + day
                            onChange(window.copy(days = days))
                        },
                        label = {
                            Text(
                                DayOfWeek.of(day).getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (showError) {
                Text(
                    stringResource(
                        if (window.days.isEmpty()) R.string.schedule_error_no_days
                        else R.string.invalid_time_window
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }

    if (pickingStart) {
        ClockDialog(
            initialMinute = window.startMinute,
            onDismiss = { pickingStart = false },
            onConfirm = { onChange(window.copy(startMinute = it)); pickingStart = false }
        )
    }
    if (pickingEnd) {
        ClockDialog(
            initialMinute = window.endMinute,
            onDismiss = { pickingEnd = false },
            onConfirm = { onChange(window.copy(endMinute = it)); pickingEnd = false }
        )
    }
}

@Composable
private fun TimeButton(label: String, minute: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(
                "%02d:%02d".format(minute / 60, minute % 60),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClockDialog(initialMinute: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60 % 24,
        initialMinute = initialMinute % 60,
        is24Hour = DateFormat.is24HourFormat(context)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
