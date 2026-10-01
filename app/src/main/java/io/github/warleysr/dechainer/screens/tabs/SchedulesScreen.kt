package io.github.warleysr.dechainer.screens.tabs

import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.border
import androidx.compose.material.icons.outlined.ContentCopy
import io.github.warleysr.dechainer.screens.common.RepeatWhileVisible
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.ui.theme.CalmCard
import io.github.warleysr.dechainer.models.BlockSchedule
import io.github.warleysr.dechainer.models.SchedulePreset
import io.github.warleysr.dechainer.models.ScheduleType
import io.github.warleysr.dechainer.screens.common.AppPickerDialog
import io.github.warleysr.dechainer.screens.common.RecoveryGateDialog
import io.github.warleysr.dechainer.screens.common.rememberRecoveryGate
import io.github.warleysr.dechainer.viewmodels.NavigationViewModel
import io.github.warleysr.dechainer.viewmodels.Route
import io.github.warleysr.dechainer.viewmodels.SchedulesViewModel
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

private val SOCIAL_MEDIA_SITES = listOf(
    "instagram.com", "facebook.com", "tiktok.com", "x.com", "twitter.com",
    "reddit.com", "youtube.com", "snapchat.com", "threads.net", "pinterest.com"
)

private val WEEKDAYS = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
)
private val WEEKENDS = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

// ---------------------------------------------------------------------------------------------
// List
// ---------------------------------------------------------------------------------------------

@Composable
fun SchedulesScreen(
    viewModel: SchedulesViewModel = viewModel(),
    navViewModel: NavigationViewModel = viewModel()
) {
    val recoveryGate = rememberRecoveryGate()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lockedMsg = stringResource(R.string.schedule_locked_message)
    val noFreeTimeMsg = stringResource(R.string.schedule_error_no_free_time)
    val defaultName = stringResource(R.string.schedule_default_name)
    val copySuffix = stringResource(R.string.schedule_copy_suffix)

    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    RepeatWhileVisible(15_000) {
        now = ZonedDateTime.now()
        viewModel.refresh()
    }

    fun showLocked() = scope.launch { snackbarHostState.showSnackbar(lockedMsg) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Room at the bottom so the last schedule never sits under the "New schedule" button.
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)
        ) {
            item {
                // The long explanation lives behind the ⓘ now; the list starts straight away.
                Spacer(Modifier.height(4.dp))
            }

            item {
                // Always on while this app is Device Owner (blueprint 9.2): the row stays so the rule can be
                // seen, but it is not a switch any more.
                ListItem(
                    headlineContent = { Text(stringResource(R.string.schedule_anti_tamper)) },
                    supportingContent = { Text(stringResource(R.string.schedule_anti_tamper_desc)) },
                    leadingContent = { Icon(Icons.Outlined.Lock, null) },
                    trailingContent = { Switch(checked = true, onCheckedChange = null, enabled = false) }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }

            item {
                Text(
                    stringResource(R.string.schedule_quick_start),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp)
                )
                Text(
                    stringResource(R.string.schedule_quick_start_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(SchedulePreset.entries.toList(), key = { it.name }) { preset ->
                        val name = stringResource(presetNameRes(preset))
                        AssistChip(
                            onClick = {
                                // Like a new schedule, a preset only adds blocking: no code needed.
                                viewModel.startPreset(preset, name)
                                navViewModel.navigateTo(Route.SCHEDULE_EDITOR)
                            },
                            label = { Text(name) }
                        )
                    }
                }
            }

            if (viewModel.schedules.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.schedules_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }

            items(viewModel.schedules, key = { it.id }) { schedule ->
                ScheduleCard(
                    schedule = schedule,
                    now = now,
                    // A copy only adds blocking, so like a new schedule it needs no recovery code.
                    onDuplicate = {
                        viewModel.startDuplicate(schedule, copySuffix)
                        navViewModel.navigateTo(Route.SCHEDULE_EDITOR)
                    },
                    onClick = {
                        if (viewModel.isLocked(schedule)) showLocked()
                        else recoveryGate.run {
                            viewModel.startEditing(schedule)
                            navViewModel.navigateTo(Route.SCHEDULE_EDITOR)
                        }
                    },
                    onEnabledChange = { enabled ->
                        when {
                            enabled -> {
                                val result = viewModel.setEnabled(schedule, true)
                                if (result == SchedulesViewModel.SaveResult.NO_FREE_TIME) {
                                    scope.launch { snackbarHostState.showSnackbar(noFreeTimeMsg) }
                                }
                            }
                            viewModel.isLocked(schedule) -> showLocked()
                            else -> recoveryGate.run {
                                if (viewModel.setEnabled(schedule, false) == SchedulesViewModel.SaveResult.LOCKED)
                                    showLocked()
                            }
                        }
                    }
                )
            }

            item { Spacer(Modifier.height(96.dp)) }
        }

        ExtendedFloatingActionButton(
            onClick = {
                // Creating a schedule only adds blocking, so it never needs the recovery code.
                viewModel.startNew(defaultName)
                navViewModel.navigateTo(Route.SCHEDULE_EDITOR)
            },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text(stringResource(R.string.schedule_new)) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
        )
    }

    RecoveryGateDialog(recoveryGate)
}

@Composable
private fun ScheduleCard(
    schedule: BlockSchedule,
    now: ZonedDateTime,
    onDuplicate: () -> Unit,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit
) {
    val nowLocal = now.toLocalDateTime()
    val active = schedule.isActiveAt(nowLocal)
    val timeFormatter = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }

    val status = when {
        !schedule.enabled -> stringResource(R.string.schedule_status_disabled)
        active -> stringResource(
            R.string.schedule_status_active,
            schedule.currentWindowEnd(nowLocal)?.format(timeFormatter) ?: ""
        )
        else -> schedule.nextStartAfter(now)?.let { next ->
            val day = next.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            stringResource(R.string.schedule_status_next, "$day ${next.format(timeFormatter)}")
        } ?: stringResource(R.string.schedule_status_never)
    }

    CalmCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        highlighted = false
    ) {
        // Running right now: a thin amber stripe down the left edge. The same card, a different
        // moment, rather than a tinted card that reads as a different kind of thing.
        val stripe = MaterialTheme.colorScheme.primary
        val running = active && schedule.enabled
        Row(
            modifier = Modifier
                .drawBehind { if (running) drawRect(stripe, size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height)) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        schedule.name.ifBlank { stringResource(R.string.schedule_default_name) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (schedule.lockWhileActive) {
                        Icon(
                            Icons.Outlined.Lock, null,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(16.dp)
                        )
                    }
                }
                Text(
                    "${BlockSchedule.formatMinute(schedule.startMinute)} – ${BlockSchedule.formatMinute(schedule.endMinute)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                DayDots(schedule.days)
                Spacer(Modifier.height(6.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active && schedule.enabled) FontWeight.Bold else FontWeight.Normal,
                    color = if (active && schedule.enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (schedule.isFocus) stringResource(R.string.schedule_type_focus)
                    else if (schedule.allowOnly) stringResource(R.string.schedule_summary_study, schedule.packages.size)
                    else scheduleSummary(schedule.packages.size, schedule.restrictions.size, schedule.websites.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDuplicate) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.schedule_duplicate))
            }
            Switch(checked = schedule.enabled, onCheckedChange = onEnabledChange)
        }
    }
}

/** Monday to Sunday as seven small circles: filled on the days the schedule runs. */
@Composable
private fun DayDots(days: Set<DayOfWeek>) {
    val on = MaterialTheme.colorScheme.primary
    val onText = MaterialTheme.colorScheme.onPrimary
    val offText = MaterialTheme.colorScheme.onSurfaceVariant
    val ring = MaterialTheme.colorScheme.outlineVariant
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { day ->
            val active = day in days
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .then(if (active) Modifier.background(on) else Modifier.border(1.dp, ring, CircleShape)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) onText else offText
                )
            }
        }
    }
}


// ---------------------------------------------------------------------------------------------
// Editor
// ---------------------------------------------------------------------------------------------

@Composable
fun ScheduleEditorScreen(
    viewModel: SchedulesViewModel = viewModel(),
    navViewModel: NavigationViewModel = viewModel()
) {
    // saveDraft()/deleteDraft()/closeEditor() all clear the draft as part of succeeding, which
    // makes the "no draft, back out" effect below fire a SECOND goBack() on the next frame and
    // pop the screen underneath too. This flag means "we are already leaving on purpose".
    var leaving by remember { mutableStateOf(false) }
    val draft = viewModel.draft
    if (draft == null) {
        LaunchedEffect(Unit) { if (!leaving) navViewModel.goBack() }
        return
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lockedMsg = stringResource(R.string.schedule_locked_message)
    val noDaysMsg = stringResource(R.string.schedule_error_no_days)
    val nothingMsg = stringResource(R.string.schedule_error_nothing)
    val noFreeTimeMsg = stringResource(R.string.schedule_error_no_free_time)
    val notSavedMsg = stringResource(R.string.schedule_error_not_saved)
    val focusShortMsg = stringResource(R.string.schedule_error_focus_short)
    val focusLongMsg = stringResource(R.string.schedule_error_focus_long)
    var showLongLockConfirm by remember { mutableStateOf(false) }

    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showCopyFrom by remember { mutableStateOf(false) }
    var showServicePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var websitesText by remember(draft.id) { mutableStateOf(draft.websites.sorted().joinToString("\n")) }

    fun handleResult(result: SchedulesViewModel.SaveResult) {
        when (result) {
            SchedulesViewModel.SaveResult.OK -> {
                leaving = true
                navViewModel.goBack()
            }
            SchedulesViewModel.SaveResult.LOCKED -> scope.launch { snackbarHostState.showSnackbar(lockedMsg) }
            SchedulesViewModel.SaveResult.NO_DAYS -> scope.launch { snackbarHostState.showSnackbar(noDaysMsg) }
            SchedulesViewModel.SaveResult.NOTHING_TO_BLOCK ->
                scope.launch { snackbarHostState.showSnackbar(nothingMsg) }
            SchedulesViewModel.SaveResult.NO_FREE_TIME ->
                scope.launch { snackbarHostState.showSnackbar(noFreeTimeMsg) }
            SchedulesViewModel.SaveResult.NEEDS_CONFIRMATION -> showLongLockConfirm = true
            SchedulesViewModel.SaveResult.NOT_SAVED -> scope.launch { snackbarHostState.showSnackbar(notSavedMsg) }
            SchedulesViewModel.SaveResult.FOCUS_TOO_SHORT -> scope.launch { snackbarHostState.showSnackbar(focusShortMsg) }
            SchedulesViewModel.SaveResult.FOCUS_TOO_LONG -> scope.launch { snackbarHostState.showSnackbar(focusLongMsg) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { value -> viewModel.updateDraft { it.copy(name = value.take(40)) } },
                        label = { Text(stringResource(R.string.schedule_name)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    )
                }

                // --- Type: block apps, or start a focus session (blueprint D12). Chosen when the entry is made. ---
                item {
                    EditorSectionTitle(stringResource(R.string.schedule_type_label))
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FilterChip(
                            selected = !draft.isFocus,
                            enabled = viewModel.isNewDraft,
                            onClick = { viewModel.updateDraft { it.copy(type = ScheduleType.BLOCK) } },
                            label = { Text(stringResource(R.string.schedule_type_block)) }
                        )
                        FilterChip(
                            selected = draft.isFocus,
                            enabled = viewModel.isNewDraft,
                            onClick = {
                                viewModel.updateDraft {
                                    it.copy(type = ScheduleType.FOCUS, lockWhileActive = false, allowOnly = false)
                                }
                            },
                            label = { Text(stringResource(R.string.schedule_type_focus)) }
                        )
                    }
                    if (draft.isFocus) {
                        Text(
                            stringResource(R.string.schedule_focus_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }

                // --- Days ---
                item {
                    EditorSectionTitle(stringResource(R.string.schedule_days))
                    DaySelector(
                        selected = draft.days,
                        onToggle = { day ->
                            viewModel.updateDraft {
                                it.copy(days = if (day in it.days) it.days - day else it.days + day)
                            }
                        }
                    )
                    Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                        TextButton(onClick = { viewModel.updateDraft { it.copy(days = DayOfWeek.entries.toSet()) } }) {
                            Text(stringResource(R.string.schedule_every_day))
                        }
                        TextButton(onClick = { viewModel.updateDraft { it.copy(days = WEEKDAYS) } }) {
                            Text(stringResource(R.string.schedule_weekdays))
                        }
                        TextButton(onClick = { viewModel.updateDraft { it.copy(days = WEEKENDS) } }) {
                            Text(stringResource(R.string.schedule_weekends))
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }

                // --- Time ---
                item {
                    EditorSectionTitle(stringResource(R.string.schedule_time))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TimeBox(
                            label = stringResource(R.string.schedule_starts),
                            minute = draft.startMinute,
                            onClick = { showStartPicker = true },
                            modifier = Modifier.weight(1f)
                        )
                        TimeBox(
                            label = stringResource(R.string.schedule_ends),
                            minute = draft.endMinute,
                            onClick = { showEndPicker = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    val timeNote = when {
                        draft.startMinute == draft.endMinute -> stringResource(R.string.schedule_full_day)
                        draft.crossesMidnight -> stringResource(R.string.schedule_crosses_midnight)
                        else -> null
                    }
                    if (timeNote != null) {
                        Text(
                            timeNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }

                // --- Study mode (allow only): retired. Shown only on a schedule that still has it,
                // so it can be switched off; new schedules just block what they list. ---
                if (draft.allowOnly) item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.schedule_allow_only)) },
                        supportingContent = { Text(stringResource(R.string.schedule_allow_only_desc)) },
                        leadingContent = { Icon(Icons.Outlined.School, null) },
                        trailingContent = {
                            Switch(
                                checked = draft.allowOnly,
                                onCheckedChange = { checked -> viewModel.updateDraft { it.copy(allowOnly = checked) } }
                            )
                        }
                    )
                }

                // --- Apps ---
                if (!draft.isFocus) item {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(if (draft.allowOnly) R.string.schedule_allowed_apps else R.string.schedule_apps))
                        },
                        supportingContent = {
                            val count = draft.packages.size
                            Text(
                                if (count == 0) stringResource(R.string.no_apps)
                                else draft.packages.map { viewModel.appName(it) }.sorted().joinToString(", "),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingContent = { Icon(Icons.Outlined.Apps, null) },
                        trailingContent = {
                            Button(onClick = { showAppPicker = true }) {
                                Text(stringResource(R.string.select_apps))
                            }
                        }
                    )
                    val others = viewModel.schedules.filter { it.id != draft.id }
                    if (others.isNotEmpty()) {
                        TextButton(
                            onClick = { showCopyFrom = true },
                            modifier = Modifier.padding(start = 56.dp)
                        ) {
                            Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.schedule_copy_from))
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }

                // --- Services ---
                if (!draft.isFocus) item {
                    val context = LocalContext.current
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.schedule_services)) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.schedule_services_desc))
                                if (draft.restrictions.isNotEmpty()) {
                                    Text(
                                        draft.restrictions.map { restrictionLabel(context, viewModel, it) }
                                            .sorted().joinToString(", "),
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        },
                        leadingContent = { Icon(Icons.Outlined.SettingsSuggest, null) },
                        trailingContent = {
                            Button(onClick = { showServicePicker = true }) {
                                Text(stringResource(R.string.schedule_select_services))
                            }
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }

                // --- Websites ---
                if (!draft.isFocus) item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
                    ) {
                        Icon(Icons.Outlined.Public, null)
                        Text(
                            stringResource(R.string.schedule_websites),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                    Text(
                        stringResource(R.string.schedule_websites_desc),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    OutlinedTextField(
                        value = websitesText,
                        onValueChange = { websitesText = it },
                        minLines = 3,
                        maxLines = 8,
                        placeholder = { Text("instagram.com\ntiktok.com") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )
                    AssistChip(
                        onClick = {
                            val merged = (parseWebsites(websitesText) + SOCIAL_MEDIA_SITES).sorted()
                            websitesText = merged.joinToString("\n")
                        },
                        label = { Text(stringResource(R.string.schedule_websites_add_social)) },
                        leadingIcon = { Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }

                // --- Protection ---
                if (!draft.isFocus) item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.schedule_lock_while_active)) },
                        supportingContent = { Text(stringResource(R.string.schedule_lock_while_active_desc)) },
                        leadingContent = { Icon(Icons.Outlined.Lock, null) },
                        trailingContent = {
                            Switch(
                                checked = draft.lockWhileActive,
                                onCheckedChange = { checked ->
                                    viewModel.updateDraft { it.copy(lockWhileActive = checked) }
                                }
                            )
                        }
                    )
                }

                if (!viewModel.isNewDraft) {
                    item {
                        TextButton(
                            onClick = { showDeleteConfirm = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Outlined.Delete, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.schedule_delete))
                        }
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        leaving = true
                        viewModel.closeEditor()
                        navViewModel.goBack()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.cancel)) }
                Button(
                    onClick = {
                        viewModel.updateDraft { it.copy(websites = parseWebsites(websitesText)) }
                        handleResult(viewModel.saveDraft())
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.schedule_save)) }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
        )
    }

    if (showStartPicker) {
        ScheduleTimePickerDialog(
            initialMinute = draft.startMinute,
            onConfirm = { minute ->
                viewModel.updateDraft { it.copy(startMinute = minute) }
                showStartPicker = false
            },
            onDismiss = { showStartPicker = false }
        )
    }

    if (showEndPicker) {
        ScheduleTimePickerDialog(
            initialMinute = draft.endMinute,
            onConfirm = { minute ->
                viewModel.updateDraft { it.copy(endMinute = minute) }
                showEndPicker = false
            },
            onDismiss = { showEndPicker = false }
        )
    }

    if (showAppPicker) {
        AppPickerDialog(
            apps = viewModel.apps.filter { it.packageName !in viewModel.protectedPackages },
            isLoading = viewModel.isLoadingApps,
            isSelected = { draft.packages.contains(it) },
            onToggle = { viewModel.toggleDraftPackage(it) },
            onDismiss = { showAppPicker = false }
        )
    }

    if (showCopyFrom) {
        CopyFromDialog(
            schedules = viewModel.schedules.filter { it.id != draft.id },
            onPick = { source ->
                viewModel.copyContentsFrom(source)
                showCopyFrom = false
            },
            onDismiss = { showCopyFrom = false }
        )
    }

    if (showServicePicker) {
        ServicePickerDialog(
            viewModel = viewModel,
            selected = draft.restrictions,
            onToggle = { viewModel.toggleDraftRestriction(it) },
            onDismiss = { showServicePicker = false }
        )
    }

    if (showLongLockConfirm) {
        LongLockConfirmDialog(
            windowMinutes = draft.windowMinutes,
            onConfirm = {
                showLongLockConfirm = false
                handleResult(viewModel.saveDraft(confirmedLongLock = true))
            },
            onDismiss = { showLongLockConfirm = false }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.schedule_delete)) },
            text = { Text(stringResource(R.string.schedule_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    handleResult(viewModel.deleteDraft())
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun LongLockConfirmDialog(windowMinutes: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var understood by remember { mutableStateOf(false) }
    val hours = windowMinutes / 60
    val minutes = windowMinutes % 60
    val duration = when {
        minutes == 0 -> stringResource(R.string.time_hours, hours)
        else -> stringResource(R.string.time_hours_minutes, hours, minutes)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, null) },
        title = { Text(stringResource(R.string.schedule_long_lock_title, duration)) },
        text = {
            Column {
                Text(stringResource(R.string.schedule_long_lock_message))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clickable { understood = !understood }
                ) {
                    Checkbox(checked = understood, onCheckedChange = { understood = it })
                    Text(stringResource(R.string.schedule_long_lock_checkbox), modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = understood, onClick = onConfirm) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

private fun parseWebsites(text: String): Set<String> = text
    .split('\n', ',', ' ', ';')
    .mapNotNull { BlockSchedule.normaliseWebsite(it) }
    .toSet()

private fun restrictionLabel(
    context: android.content.Context,
    viewModel: SchedulesViewModel,
    key: String
): String {
    val resId = viewModel.resourceNameFor(key)
        ?.let { context.resources.getIdentifier(it, "string", context.packageName) }
        ?.takeIf { it != 0 }
    return resId?.let { context.getString(it) } ?: key
}

@Composable
private fun EditorSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun DaySelector(selected: Set<DayOfWeek>, onToggle: (DayOfWeek) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        DayOfWeek.entries.forEach { day ->
            val isOn = day in selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(
                        if (isOn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onToggle(day) }
            ) {
                Text(
                    day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    fontWeight = FontWeight.Bold,
                    color = if (isOn) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TimeBox(label: String, minute: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(modifier = modifier.clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Schedule, null, modifier = Modifier.size(16.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            Text(
                BlockSchedule.formatMinute(minute),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleTimePickerDialog(initialMinute: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun ServicePickerDialog(
    viewModel: SchedulesViewModel,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }

    val labels = remember {
        (viewModel.suggestedRestrictions + viewModel.otherRestrictions)
            .associateWith { restrictionLabel(context, viewModel, it) }
    }

    fun matches(key: String) = query.isBlank() ||
        labels[key].orEmpty().contains(query, ignoreCase = true) ||
        key.contains(query, ignoreCase = true)

    val suggested = viewModel.suggestedRestrictions.filter(::matches)
    val others = viewModel.otherRestrictions.filter(::matches)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schedule_select_services)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.search_restrictions)) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    if (suggested.isNotEmpty()) {
                        item { PickerHeader(stringResource(R.string.schedule_suggested)) }
                        items(suggested, key = { "s_$it" }) { key ->
                            ServiceRow(labels[key] ?: key, key in selected) { onToggle(key) }
                        }
                    }
                    if (others.isNotEmpty()) {
                        item { PickerHeader(stringResource(R.string.schedule_all_services)) }
                        items(others, key = { "o_$it" }) { key ->
                            ServiceRow(labels[key] ?: key, key in selected) { onToggle(key) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm)) }
        }
    )
}

@Composable
private fun PickerHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun ServiceRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = { onClick() })
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Picks a schedule whose apps, services and websites are added to the one being edited. */
@Composable
private fun CopyFromDialog(schedules: List<BlockSchedule>, onPick: (BlockSchedule) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schedule_copy_from)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.schedule_copy_from_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn {
                    items(schedules, key = { it.id }) { source ->
                        ListItem(
                            headlineContent = { Text(source.name.ifBlank { stringResource(R.string.schedule_default_name) }) },
                            supportingContent = {
                                Text(scheduleSummary(source.packages.size, source.restrictions.size, source.websites.size))
                            },
                            modifier = Modifier.clickable { onPick(source) }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/**
 * "1 app · 2 services · 1 website": the right word for every count. English-only, so one/many
 * is enough; if a language is ever added, switch to pluralStringResource.
 */
@Composable
private fun scheduleSummary(apps: Int, services: Int, websites: Int): String = listOf(
    if (apps == 1) stringResource(R.string.count_app_one) else stringResource(R.string.count_app_many, apps),
    if (services == 1) stringResource(R.string.count_service_one) else stringResource(R.string.count_service_many, services),
    if (websites == 1) stringResource(R.string.count_website_one) else stringResource(R.string.count_website_many, websites)
).joinToString(" · ")


private fun presetNameRes(preset: SchedulePreset): Int = when (preset) {
    SchedulePreset.BEDTIME -> R.string.schedule_preset_bedtime
    SchedulePreset.STUDY_HOURS -> R.string.schedule_preset_study
    SchedulePreset.EXAM_WEEK -> R.string.schedule_preset_exam
    SchedulePreset.NIGHT_DETOX -> R.string.schedule_preset_night
}
