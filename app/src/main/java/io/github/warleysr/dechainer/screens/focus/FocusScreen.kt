package io.github.warleysr.dechainer.screens.focus

import io.github.warleysr.dechainer.DechainerApplication
import io.github.warleysr.dechainer.Rules
import io.github.warleysr.dechainer.focus.Flavor
import io.github.warleysr.dechainer.focus.FocusFlow
import io.github.warleysr.dechainer.focus.FocusRunner
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.lock.LockEngine
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.Image
import io.github.warleysr.dechainer.ui.theme.Motion
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import io.github.warleysr.dechainer.screens.common.Chevron
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.TimePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import io.github.warleysr.dechainer.focus.BlockPlanner
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import io.github.warleysr.dechainer.screens.common.rememberRecoveryGate
import io.github.warleysr.dechainer.screens.common.RecoveryGateDialog
import io.github.warleysr.dechainer.screens.common.AppPickerDialog
import io.github.warleysr.dechainer.models.AppItem
import io.github.warleysr.dechainer.data.AppRepository
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.warleysr.dechainer.R
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.warleysr.dechainer.urge.UrgeSource
import io.github.warleysr.dechainer.viewmodels.UrgeViewModel
import io.github.warleysr.dechainer.focus.FocusLogMath
import io.github.warleysr.dechainer.focus.Phase
import io.github.warleysr.dechainer.focus.Pomodoro
import io.github.warleysr.dechainer.focus.PomodoroSettings
import io.github.warleysr.dechainer.screens.common.RepeatWhileVisible
import io.github.warleysr.dechainer.ui.theme.CalmCard
import java.time.LocalDate

/**
 * The Focus tab: one ring, one number, one button. Everything else is out of the way.
 * The ring moves once a second, and only while this screen is on — the timer itself runs on an
 * alarm, not on this screen.
 */
@Composable
fun FocusScreen(onOpenLog: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(context) {
        withContext(Dispatchers.IO) { Pomodoro.ensureLoaded(context); FocusRunner.ensureLoaded(context) }
    }
    val flow by FocusRunner.flow.collectAsState()
    val state by Pomodoro.state.collectAsState()
    val settings by Pomodoro.settings.collectAsState()
    val log by Pomodoro.log.collectAsState()
    val pending by Pomodoro.pendingQuestion.collectAsState()
    var showAppPicker by remember { mutableStateOf(false) }
    var showBlock by remember { mutableStateOf(false) }
    val allowedApps by Pomodoro.allowedApps.collectAsState()
    val gate = rememberRecoveryGate()

    var now by remember { mutableLongStateOf(TrustedClock.now()) }
    // Also while a block runs as one stretch (a special session, the plain timer): its end is a wake-up too.
    if (state.isRunning || state.inBlock) RepeatWhileVisible(1000) {
        now = TrustedClock.now()
        // The tick is a wake-up too (blueprint 5.4, R1): once a block's end has passed, have the lock
        // engine close it from the time, whether or not its alarm ever fired.
        if (state.inBlock && now >= state.blockEndsAt) LockEngine.requestSync(DechainerApplication.getInstance())
    }

    var showSettings by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }

    // The phase's own planned length: a block may shorten its last session below the setting.
    val total = (state.plannedMinutes.takeIf { it > 0 } ?: settings.minutesFor(state.phase)) * 60_000L
    val remaining = state.remaining(now, settings)
    val progress = if (total > 0) (1f - remaining.toFloat() / total).coerceIn(0f, 1f) else 0f
    val todayDate = LocalDate.now()
    val today = remember(log, todayDate) {
        FocusLogMath.byDay(log).firstOrNull { it.date == todayDate }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { showSettings = true }, enabled = state.isIdle) {
                Icon(Icons.Outlined.Tune, contentDescription = stringResource(R.string.focus_settings))
            }
        }

        // A session that ended while you were away is still waiting for its answer.
        pending?.let { id -> Pomodoro.session(id) }?.takeIf { it.done == null }?.let { session ->
            CalmCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), highlighted = true) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.focus_question_pending, session.minutes),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { Pomodoro.answer(context, session.id, false) }) {
                            Text(stringResource(R.string.focus_answer_no))
                        }
                        Button(onClick = { Pomodoro.answer(context, session.id, true) }) {
                            Text(stringResource(R.string.focus_answer_yes))
                        }
                    }
                }
            }
        }

        // The focus flow (blueprint 6.3): the prompt, a check-in, the reset, the plain timer, a special
        // session's countdown, or the last question. While a block runs it takes the place of the ring.
        val panel = flow?.takeIf { flowPanelShows(it.stage) }
        if (panel != null) {
            FocusFlowPanel(panel, Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(16.dp))
            if (state.inBlock) {
                if (allowedApps.isNotEmpty()) {
                    AllowedAppsRow(allowedApps)
                }
                UrgeButton()
                Spacer(Modifier.height(24.dp))
                return@Column
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                when (state.phase) {
                    Phase.FOCUS -> R.string.focus_phase_focus
                    Phase.SHORT_BREAK -> R.string.focus_phase_short
                    Phase.LONG_BREAK -> R.string.focus_phase_long
                }
            ).uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 3.sp),
            color = if (state.phase == Phase.FOCUS) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.tertiary
        )
        if (state.inBlock) {
            // A committed block: when it ends, and that it's locked till then.
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    R.string.focus_block_until,
                    java.time.Instant.ofEpochMilli(state.blockEndsAt).atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        Spacer(Modifier.height(12.dp))
        // What a session is for is asked when it starts (the start dialog, or the prompt of a scheduled one).

        // --- The ring ---
        val track = MaterialTheme.colorScheme.surfaceContainerHigh
        // Focus ↔ break: the accent eases from amber to sage instead of snapping, so the phase
        // change is felt without reading a word.
        val arc by animateColorAsState(
            targetValue = if (state.phase == Phase.FOCUS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
            animationSpec = tween(Motion.PHASE_MS, easing = FastOutSlowInEasing),
            label = "phaseColor"
        )
        val haptics = LocalHapticFeedback.current
        Box(
            modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 10.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                if (progress > 0f) {
                    drawArc(arc, -90f, 360f * progress, false, Offset(inset, inset), arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val secs = (remaining + 999) / 1000
                Text(
                    "%d:%02d".format(secs / 60, secs % 60),
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 64.sp),
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (state.isPaused) {
                    Text(
                        stringResource(R.string.focus_paused),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Spacer(Modifier.height(28.dp))

        // --- Controls ---
        if (state.isIdle && !state.inBlock) {
            // The one thing to do from here: commit a block. It runs and bricks by itself.
            Button(
                onClick = { showBlock = true },
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().height(56.dp)
            ) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.focus_block_start), style = MaterialTheme.typography.titleMedium)
            }
        } else if (state.inBlock) {
            // Pause: for physical work (an errand, someone calling you over). The timer stops so
            // that time isn't logged as study; the phone stays bricked and the end time holds.
            FilledIconButton(
                onClick = {
                    if (state.isRunning) Pomodoro.pause(context)
                    else { haptics.performHapticFeedback(HapticFeedbackType.LongPress); Pomodoro.start(context) }
                },
                modifier = Modifier.size(80.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = arc)
            ) {
                Icon(
                    if (state.isRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (state.isRunning) R.string.focus_pause else R.string.focus_start),
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            // Committed: nothing on this screen ends it early. Not even the recovery code.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.focus_block_no_way_out),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (allowedApps.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                AllowedAppsRow(allowedApps)
            }
            UrgeButton()
        } else {
            // Only reachable for a single session left running by an older version.
            IconButton(onClick = { confirmStop = true }) {
                Icon(Icons.Outlined.Stop, contentDescription = stringResource(R.string.focus_stop))
            }
        }
        Spacer(Modifier.height(36.dp))

        // --- Today, and the way to the full log ---
        CalmCard(modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(enabled = !state.inBlock, onClick = onOpenLog)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.focus_today),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (today == null) stringResource(R.string.focus_today_none)
                        else stringResource(R.string.focus_today_summary, sessionsLabel(today.count), today.doneCount, formatMinutes(today.minutes)),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = stringResource(R.string.focus_log))
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    RecoveryGateDialog(gate)
    if (showBlock) BlockDialog(settings, onDismiss = { showBlock = false })

    if (showSettings) {
        FocusSettingsDialog(
            settings,
            allowedCount = allowedApps.size,
            onPickApps = { showAppPicker = true },
            onDismiss = { showSettings = false }
        ) {
            Pomodoro.updateSettings(context, it)
            showSettings = false
        }
    }
    if (showAppPicker) {
        var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            apps = withContext(Dispatchers.IO) { AppRepository.getApps() }
            loading = false
        }
        AppPickerDialog(
            apps = apps,
            isLoading = loading,
            isSelected = { it in allowedApps },
            onToggle = { Pomodoro.toggleAllowedApp(context, it) },
            onDismiss = { showAppPicker = false }
        )
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.focus_stop_title)) },
            text = { Text(stringResource(R.string.focus_stop_text)) },
            confirmButton = {
                TextButton(onClick = { Pomodoro.stop(context); confirmStop = false }) { Text(stringResource(R.string.focus_stop)) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}


/** "1 session" or "3 sessions". */
@Composable
fun sessionsLabel(count: Int): String =
    if (count == 1) stringResource(R.string.focus_sessions_one) else stringResource(R.string.focus_sessions_many, count)

@Composable
fun formatMinutes(minutes: Int): String = when {
    minutes < 60 -> stringResource(R.string.time_minutes, minutes)
    minutes % 60 == 0 -> stringResource(R.string.time_hours, minutes / 60)
    else -> stringResource(R.string.time_hours_minutes, minutes / 60, minutes % 60)
}

@Composable
private fun FocusSettingsDialog(
    initial: PomodoroSettings,
    allowedCount: Int,
    onPickApps: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (PomodoroSettings) -> Unit
) {
    var s by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.focus_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DialogSection(stringResource(R.string.focus_section_timer))
                // Common lengths in one tap; the stepper below fine-tunes anything else.
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(25, 30, 50, 60, 90).forEach { m ->
                        FilterChip(
                            selected = s.focusMinutes == m,
                            onClick = { s = s.copy(focusMinutes = m) },
                            label = { Text(stringResource(R.string.focus_minutes_short, m)) }
                        )
                    }
                }
                Stepper(stringResource(R.string.focus_setting_focus), s.focusMinutes, PomodoroSettings.FOCUS_RANGE, 5) {
                    s = s.copy(focusMinutes = it)
                }
                Stepper(stringResource(R.string.focus_setting_short), s.shortBreakMinutes, PomodoroSettings.SHORT_RANGE, 1) {
                    s = s.copy(shortBreakMinutes = it)
                }
                Stepper(stringResource(R.string.focus_setting_long), s.longBreakMinutes, PomodoroSettings.LONG_RANGE, 5) {
                    s = s.copy(longBreakMinutes = it)
                }
                Stepper(stringResource(R.string.focus_setting_every), s.longBreakEvery, PomodoroSettings.EVERY_RANGE, 1, unit = false) {
                    s = s.copy(longBreakEvery = it)
                }
                DialogSection(stringResource(R.string.focus_section_blocks))
                // Open all through a block, with calls, your alarm clock and Quick Settings.
                TextButton(onClick = onPickApps, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.focus_allowed_apps, allowedCount))
                    Spacer(Modifier.weight(1f))
                    Chevron()
                }
                Text(
                    stringResource(R.string.focus_allowed_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DialogSection(stringResource(R.string.focus_section_sounds))
                val context = LocalContext.current
                TextButton(onClick = { Pomodoro.openAlarmSoundSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.MusicNote, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.focus_chime_sound))
                    Spacer(Modifier.weight(1f))
                    Chevron()
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(s) }) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, step: Int, unit: Boolean = true, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = value > range.first) { Text("−") }
        Text(
            if (unit) stringResource(R.string.focus_minutes_short, value) else value.toString(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.widthIn(min = 52.dp)
        )
        TextButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = value < range.last) { Text("+") }
    }
}

/**
 * Commit a focus block: pick when it ends on a clock, see the plan, commit. After that it runs
 * by itself and can't be changed or left early without the recovery code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockDialog(settings: PomodoroSettings, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Kept current while the dialog is open, so the preview always matches what you'd commit.
    var now by remember { mutableStateOf(java.time.LocalDateTime.now()) }
    RepeatWhileVisible(15_000) { now = java.time.LocalDateTime.now() }
    val allowed by Pomodoro.allowedApps.collectAsState()

    // The chosen end, as a time of day. Starts two hours out, on a quarter hour.
    var endTime by remember {
        val t = java.time.LocalTime.now().plusHours(2)
        mutableStateOf(t.withMinute((t.minute / 15) * 15).withSecond(0).withNano(0))
    }
    var showClock by remember { mutableStateOf(false) }
    val blockHaptics = LocalHapticFeedback.current
    // A time that's already passed today means tomorrow.
    val end = now.toLocalDate().atTime(endTime).let { if (!it.isAfter(now)) it.plusDays(1) else it }
    val total = java.time.Duration.between(now, end).toMinutes().toInt()
    // Blueprint 5.2: at least 10 minutes, at most 8 hours.
    val tooLong = total > (Rules.FOCUS_BLOCK_MAX_MS / 60_000L).toInt()
    val tooShort = total < (Rules.FOCUS_BLOCK_MIN_MS / 60_000L).toInt()
    var flavor by remember { mutableStateOf(Flavor.USUAL) }
    var purpose by remember { mutableStateOf("") }
    val plan = remember(total, settings) { if (tooLong) emptyList() else BlockPlanner.plan(total, settings) }
    val sessions = plan.count { it.first == Phase.FOCUS }
    val breaks = plan.size - sessions
    val timeFormat = remember { java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.focus_block_title)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.focus_block_until_label), style = MaterialTheme.typography.labelLarge)
                // Tap the time to set any time on a clock.
                TextButton(onClick = { showClock = true }) {
                    Text(end.format(timeFormat), style = MaterialTheme.typography.displaySmall)
                }
                Text(
                    stringResource(R.string.focus_block_tap_to_change) + " · " + formatMinutes(total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                // Usual (sessions, breaks and check-ins) or special (one continuous session), and what it is for.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = flavor == Flavor.USUAL, onClick = { flavor = Flavor.USUAL },
                        label = { Text(stringResource(R.string.focus_flow_usual)) }
                    )
                    FilterChip(
                        selected = flavor == Flavor.SPECIAL, onClick = { flavor = Flavor.SPECIAL },
                        label = { Text(stringResource(R.string.focus_flow_special)) }
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = purpose,
                    onValueChange = { purpose = it.replace('\n', ' ').take(80) },
                    label = { Text(stringResource(R.string.focus_flow_purpose_label)) },
                    placeholder = { Text(stringResource(R.string.focus_flow_purpose_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        tooLong -> stringResource(R.string.focus_block_too_long)
                        tooShort -> stringResource(R.string.focus_block_too_short)
                        flavor == Flavor.SPECIAL -> stringResource(R.string.focus_flow_special_hint)
                        plan.isEmpty() -> stringResource(R.string.focus_block_too_short)
                        else -> stringResource(
                            R.string.focus_block_plan,
                            sessionsLabel(sessions),
                            breaksLabel(breaks),
                            formatMinutes(plan.filter { it.first == Phase.FOCUS }.sumOf { it.second })
                        )
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(12.dp))
                if (allowed.isNotEmpty()) {
                    Text(
                        stringResource(R.string.focus_block_allowed_line, allowed.size),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    stringResource(R.string.focus_block_irreversible),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.focus_block_rules_brick),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !tooLong && !tooShort && FocusFlow.cleanPurpose(purpose) != null &&
                    (flavor == Flavor.SPECIAL || plan.isNotEmpty()),
                onClick = {
                    val endMillis = end.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    if (FocusRunner.startManual(context, endMillis, flavor, purpose)) {
                        blockHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDismiss()
                    }
                }
            ) { Text(stringResource(R.string.focus_block_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )

    if (showClock) {
        val clock = rememberTimePickerState(
            initialHour = endTime.hour,
            initialMinute = endTime.minute,
            is24Hour = android.text.format.DateFormat.is24HourFormat(context)
        )
        AlertDialog(
            onDismissRequest = { showClock = false },
            text = { TimePicker(state = clock) },
            confirmButton = {
                TextButton(onClick = {
                    endTime = java.time.LocalTime.of(clock.hour, clock.minute)
                    showClock = false
                }) { Text(stringResource(R.string.apply)) }
            },
            dismissButton = { TextButton(onClick = { showClock = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

/** "no breaks", "1 break", "3 breaks". */
@Composable
private fun breaksLabel(count: Int): String = when (count) {
    0 -> stringResource(R.string.focus_breaks_none)
    1 -> stringResource(R.string.focus_breaks_one)
    else -> stringResource(R.string.focus_breaks_many, count)
}

/** A small section label inside a dialog: TIMER, LOCK, SOUNDS. */
@Composable
private fun DialogSection(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

/**
 * An urge during a block (blueprint 6.2, 5.3): the Urge button opens the same flow as everywhere
 * else. The phone is already bricked, so no second lock starts, and the breathing still runs for ten
 * minutes.
 */
@Composable
private fun UrgeButton() {
    val urge: UrgeViewModel = viewModel()
    Spacer(Modifier.height(24.dp))
    OutlinedButton(
        onClick = { urge.openChoice(UrgeSource.FOCUS) },
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()
    ) { Text(stringResource(R.string.urge_button)) }
}

/**
 * The apps you allowed for blocks, as a row of icons. The pinned phone has no launcher, so this is
 * how you open them; the Home button brings you back to this page.
 */
@Composable
private fun AllowedAppsRow(packages: Set<String>) {
    val context = LocalContext.current
    val items = remember(packages) {
        val pm = context.packageManager
        packages.mapNotNull { pkg ->
            try {
                val label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                Triple(pkg, label, pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap())
            } catch (_: Exception) {
                null
            }
        }.sortedBy { it.second.lowercase() }
    }
    if (items.isEmpty()) return
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items.forEach { (pkg, label, icon) ->
            Column(
                modifier = Modifier
                    .width(64.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable {
                        try {
                            context.packageManager.getLaunchIntentForPackage(pkg)?.let { context.startActivity(it) }
                        } catch (_: Exception) { }
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(icon, null, Modifier.size(48.dp).clip(CircleShape))
                Spacer(Modifier.height(4.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

