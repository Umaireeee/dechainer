package io.github.warleysr.dechainer.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.day.*
import io.github.warleysr.dechainer.lock.LockEngine
import io.github.warleysr.dechainer.screens.common.RepeatWhileVisible
import io.github.warleysr.dechainer.store.Store

/** Today (blueprint 6.4): today's goals, tomorrow's plan in the evening window, and the rest-day choice. */
@Composable
fun TodayScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val repo = remember { Store.days(ctx) }
    val zone = TrustedClock.zone()
    var now by remember { mutableLongStateOf(TrustedClock.now(ctx)) }
    var version by remember { mutableIntStateOf(0) }
    var saveStatus by remember { mutableStateOf(SaveStatus.NONE) }
    RepeatWhileVisible(1000) { now = TrustedClock.now(ctx) }

    val today = DayWindow.dateOf(now, zone)
    val tomorrow = today.plusDays(1)
    // Reads again after every write, and when the clock crosses a day.
    val row = remember(today, version) { repo.day(today) }
    val goals = remember(today, version) { repo.goals(today) }
    val plan = remember(tomorrow, version) { repo.goals(tomorrow) }
    val tomorrowRow = remember(tomorrow, version) { repo.day(tomorrow) }
    val canEdit = DayWindow.canEditPlan(now, tomorrow, zone)
    val drafts = remember(tomorrow, version) {
        mutableStateListOf<GoalDraft>().apply {
            addAll(plan.map { GoalDraft(it.text, it.type, it.targetMinutes?.toString().orEmpty()) }.ifEmpty { List(DayRules.MIN_GOALS) { GoalDraft() } })
        }
    }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            when {
                row?.kind == DayKind.PUNISHMENT -> Text(stringResource(R.string.today_punishment, stringResource(when (row.violation) {
                    Violation.UNRESOLVED -> R.string.today_why_unresolved
                    Violation.UNDER_HALF -> R.string.today_why_under_half
                    else -> R.string.today_why_plan_missing
                })), style = MaterialTheme.typography.titleMedium)
                row?.kind == DayKind.REST -> Text(stringResource(R.string.today_rest), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.today_goals), style = MaterialTheme.typography.titleLarge)
            if (goals.isEmpty()) Text(stringResource(R.string.today_no_goals))
        }
        items(goals, key = { it.id }) { g ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val tag = when (g.type) {
                    GoalType.FOCUS_MINUTES -> stringResource(R.string.today_type_focus_tag, g.targetMinutes ?: 0)
                    GoalType.NO_SLIP -> stringResource(R.string.today_type_noslip_tag)
                    GoalType.MANUAL -> ""
                }
                val state = when (g.state) {
                    GoalState.DONE -> stringResource(R.string.today_state_done)
                    GoalState.NOT_DONE -> stringResource(R.string.today_state_not_done)
                    GoalState.OPEN -> ""
                }
                Column(Modifier.weight(1f)) {
                    Text(g.text)
                    val sub = listOf(tag, state).filter { it.isNotEmpty() }.joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (g.state == GoalState.OPEN && g.type == GoalType.MANUAL) {
                    if (DayWindow.canMarkDone(now, today, zone)) TextButton({ repo.setGoal(g.id, GoalState.DONE, ResolvedBy.USER); version++ }) { Text(stringResource(R.string.today_done)) }
                    if (DayWindow.canMarkNotDone(now, today, zone)) TextButton({ repo.setGoal(g.id, GoalState.NOT_DONE, ResolvedBy.USER); version++ }) { Text(stringResource(R.string.today_not_done)) }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)); Text(stringResource(R.string.today_tomorrow), style = MaterialTheme.typography.titleLarge) }
        if (!canEdit) {
            items(plan, key = { "p${it.id}" }) { g ->
                val tag = when (g.type) {
                    GoalType.FOCUS_MINUTES -> " · " + stringResource(R.string.today_type_focus_tag, g.targetMinutes ?: 0)
                    GoalType.NO_SLIP -> " · " + stringResource(R.string.today_type_noslip_tag)
                    GoalType.MANUAL -> ""
                }
                Text("• " + g.text + tag)
            }
            item { Text(stringResource(R.string.today_tomorrow_locked), style = MaterialTheme.typography.bodySmall) }
        } else {
            items(drafts.size) { i ->
                val d = drafts[i]
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(d.text, { d.text = it.take(120); saveStatus = SaveStatus.NONE }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.today_goal_hint, i + 1)) })
                    // How the goal is closed: by hand, by the focus minutes the app measures, or by a day with no slip.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(
                            GoalType.MANUAL to R.string.today_type_manual,
                            GoalType.FOCUS_MINUTES to R.string.today_type_focus,
                            GoalType.NO_SLIP to R.string.today_type_noslip
                        ).forEach { (type, label) ->
                            FilterChip(selected = d.type == type, onClick = { d.type = type; saveStatus = SaveStatus.NONE }, label = { Text(stringResource(label)) })
                        }
                    }
                    if (d.type == GoalType.FOCUS_MINUTES) {
                        OutlinedTextField(
                            d.minutes, { d.minutes = it.filter(Char::isDigit).take(3); saveStatus = SaveStatus.NONE },
                            Modifier.fillMaxWidth(), singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            label = { Text(stringResource(R.string.today_type_minutes_hint)) }
                        )
                    }
                }
            }
            item {
                val written = drafts.filter { it.text.trim().isNotEmpty() }
                val minutesOk = written.all { it.type != GoalType.FOCUS_MINUTES || it.minutesValue() != null }
                val enough = written.size in DayRules.MIN_GOALS..DayRules.MAX_GOALS && minutesOk
                val changed = written.map { it.toNew() } != plan.map { NewGoal(it.text, it.type, it.targetMinutes) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (drafts.size < DayRules.MAX_GOALS) TextButton({ drafts.add(GoalDraft()) }) { Text(stringResource(R.string.today_add_goal)) }
                    Button(
                        {
                            // Re-checked at the moment of saving: the window may have closed while typing.
                            val at = TrustedClock.now(ctx)
                            saveStatus = when {
                                !DayWindow.canEditPlan(at, tomorrow, zone) -> SaveStatus.CLOSED
                                repo.savePlan(tomorrow, drafts.filter { it.text.trim().isNotEmpty() }.map { it.toNew() }, at) -> {
                                    LockEngine.requestSync(ctx); version++; SaveStatus.SAVED
                                }
                                else -> SaveStatus.TOO_FEW
                            }
                        },
                        enabled = enough && changed
                    ) { Text(stringResource(R.string.today_save_plan)) }
                }
                // The save used to look like nothing happened: say what is saved, or what is missing.
                val (msg, color) = when {
                    saveStatus == SaveStatus.CLOSED -> stringResource(R.string.today_save_closed) to MaterialTheme.colorScheme.error
                    saveStatus == SaveStatus.TOO_FEW -> stringResource(R.string.today_save_too_few) to MaterialTheme.colorScheme.error
                    saveStatus == SaveStatus.SAVED || (!changed && plan.isNotEmpty()) ->
                        pluralStringResource(R.plurals.today_plan_saved, plan.size, plan.size) to MaterialTheme.colorScheme.primary
                    !minutesOk -> stringResource(R.string.today_save_minutes) to MaterialTheme.colorScheme.error
                    written.size < DayRules.MIN_GOALS -> stringResource(R.string.today_save_need, DayRules.MIN_GOALS, written.size) to MaterialTheme.colorScheme.onSurfaceVariant
                    else -> stringResource(R.string.today_save_unsaved) to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(msg, style = MaterialTheme.typography.bodyMedium, color = color)
            }
            item {
                val isRest = tomorrowRow?.kind == DayKind.REST
                val allowed = isRest || DayWindow.canDeclareRest(now, tomorrow, repo.restDates(), zone)
                OutlinedButton({ repo.setRest(tomorrow, !isRest); version++ }, enabled = allowed) {
                    Text(stringResource(if (isRest) R.string.today_rest_cancel else R.string.today_rest_tomorrow))
                }
            }
        }
    }
}

private enum class SaveStatus { NONE, SAVED, TOO_FEW, CLOSED }

/** A goal being written: its text, how it is closed, and the minutes for a focus goal. Fields are Compose state so the editor redraws as they change. */
private class GoalDraft(text: String = "", type: GoalType = GoalType.MANUAL, minutes: String = "") {
    var text by mutableStateOf(text)
    var type by mutableStateOf(type)
    var minutes by mutableStateOf(minutes)

    /** The target as a number, or null if it is not one in 5..720 (a focus goal needs a real target). */
    fun minutesValue(): Int? = minutes.toIntOrNull()?.takeIf { it in 5..720 }

    fun toNew(): NewGoal = NewGoal(
        text.trim(), type,
        if (type == GoalType.FOCUS_MINUTES) minutesValue() else null
    )
}
