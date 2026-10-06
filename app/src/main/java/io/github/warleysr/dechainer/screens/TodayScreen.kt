package io.github.warleysr.dechainer.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.day.DayKind
import io.github.warleysr.dechainer.day.DayRules
import io.github.warleysr.dechainer.day.DayWindow
import io.github.warleysr.dechainer.day.Goal
import io.github.warleysr.dechainer.day.GoalState
import io.github.warleysr.dechainer.day.GoalType
import io.github.warleysr.dechainer.day.NewGoal
import io.github.warleysr.dechainer.day.PlanDraft
import io.github.warleysr.dechainer.day.ResolvedBy
import io.github.warleysr.dechainer.day.Violation
import io.github.warleysr.dechainer.lock.LockEngine
import io.github.warleysr.dechainer.screens.common.RepeatWhileVisible
import io.github.warleysr.dechainer.store.Store
import io.github.warleysr.dechainer.ui.theme.CalmCard
import java.time.format.DateTimeFormatter

/**
 * Today (blueprint 6.4): today's goals, tomorrow's plan in the evening window, and the rest-day
 * choice. Nothing here locks the phone: each finished day is recorded, and the reports and the
 * progress graphs read it.
 */
@Composable
fun TodayScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val repo = remember { Store.days(ctx) }
    val zone = TrustedClock.zone()
    var now by remember { mutableLongStateOf(TrustedClock.now(ctx)) }
    var version by remember { mutableIntStateOf(0) }
    RepeatWhileVisible(1000) { now = TrustedClock.now(ctx) }

    val today = DayWindow.dateOf(now, zone)
    val tomorrow = today.plusDays(1)
    // Read again after every write, and when the clock crosses a day.
    val row = remember(today, version) { repo.day(today) }
    val yesterday = remember(today, version) { repo.day(today.minusDays(1)) }
    val goals = remember(today, version) { repo.goals(today) }
    val plan = remember(tomorrow, version) { repo.goals(tomorrow) }
    val tomorrowRow = remember(tomorrow, version) { repo.day(tomorrow) }
    val focusToday = remember(today, version, now / 60_000L) { repo.stats(zone).focusMinutes(today) }
    val canEdit = DayWindow.canEditPlan(now, tomorrow, zone)
    val evening = DayWindow.inEvening(now, today, zone)
    val drafts = remember(tomorrow, version) {
        mutableStateListOf<PlanDraft>().apply { addAll(PlanDraft.fromGoals(plan)) }
    }
    var message by remember { mutableStateOf<Int?>(null) }

    val focusText = stringResource(R.string.today_goal_focus_text)
    val noSlipText = stringResource(R.string.today_goal_noslip_text)

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                today.format(DateTimeFormatter.ofPattern("EEEE d MMMM")),
                style = MaterialTheme.typography.headlineSmall
            )
            val status = when {
                row?.kind == DayKind.REST -> stringResource(R.string.today_rest)
                yesterday?.evaluated == true && yesterday.totalCount > 0 -> {
                    val base = stringResource(R.string.today_yesterday, yesterday.doneCount, yesterday.totalCount)
                    yesterday.violation?.let { base + " " + stringResource(whyText(it)) } ?: base
                }
                else -> null
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---- today's goals ----
        item {
            CalmCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.today_goals), style = MaterialTheme.typography.titleLarge)
                    if (goals.isEmpty()) {
                        Text(
                            stringResource(R.string.today_no_goals),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    goals.forEach { g ->
                        GoalRow(
                            goal = g,
                            focusMinutes = focusToday,
                            canDone = g.type == GoalType.MANUAL && g.state == GoalState.OPEN && DayWindow.canMarkDone(now, today, zone),
                            canNotDone = g.type == GoalType.MANUAL && g.state == GoalState.OPEN && DayWindow.canMarkNotDone(now, today, zone),
                            onDone = { repo.setGoal(g.id, GoalState.DONE, ResolvedBy.USER); version++ },
                            onNotDone = { repo.setGoal(g.id, GoalState.NOT_DONE, ResolvedBy.USER); version++ }
                        )
                    }
                    if (goals.any { it.type == GoalType.MANUAL && it.state == GoalState.OPEN } && !evening) {
                        Text(
                            stringResource(R.string.today_close_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ---- tomorrow's plan ----
        item {
            CalmCard(Modifier.fillMaxWidth(), highlighted = canEdit) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.today_tomorrow), style = MaterialTheme.typography.titleLarge)
                    if (!canEdit) {
                        if (plan.isEmpty()) {
                            Text(
                                stringResource(R.string.today_tomorrow_locked),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            plan.forEach { Text("• " + it.text, style = MaterialTheme.typography.bodyLarge) }
                        }
                    } else {
                        Text(
                            stringResource(R.string.today_plan_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val unfinished = goals.filter { it.type == GoalType.MANUAL && it.state != GoalState.DONE }
                        if (unfinished.isNotEmpty() && drafts.size < DayRules.MAX_GOALS) {
                            TextButton({
                                PlanDraft.carryOver(drafts.toList(), unfinished).let { drafts.clear(); drafts.addAll(it) }
                            }) { Text(stringResource(R.string.today_carry_over)) }
                        }
                        drafts.forEachIndexed { i, d ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = d.text,
                                    onValueChange = { drafts[i] = d.copy(text = it.take(PlanDraft.MAX_TEXT)) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.today_goal_hint, i + 1)) },
                                    supportingText = {
                                        TypeLabel(d) { drafts[i] = d.nextType() }
                                    }
                                )
                                if (drafts.size > DayRules.MIN_GOALS) {
                                    IconButton({ drafts.removeAt(i) }) {
                                        Icon(Icons.Outlined.RemoveCircleOutline, stringResource(R.string.today_remove_goal, i + 1))
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (drafts.size < DayRules.MAX_GOALS) {
                                TextButton({ drafts.add(PlanDraft()) }) { Text(stringResource(R.string.today_add_goal)) }
                            }
                            Spacer(Modifier.weight(1f))
                            Button({
                                val at = TrustedClock.now(ctx)
                                val goalsToSave: List<NewGoal> = drafts.mapNotNull { it.toNewGoal(focusText, noSlipText) }
                                message = when {
                                    !DayWindow.canEditPlan(at, tomorrow, zone) -> R.string.today_plan_window_closed
                                    goalsToSave.size < DayRules.MIN_GOALS -> R.string.today_plan_too_few
                                    repo.savePlan(tomorrow, goalsToSave, at) -> {
                                        LockEngine.requestSync(ctx)
                                        version++
                                        R.string.today_plan_saved
                                    }
                                    else -> R.string.today_plan_too_few
                                }
                            }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.today_save_plan)) }
                        }
                        message?.let {
                            Text(it.let { id -> stringResource(id) }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        // ---- rest day ----
        if (canEdit) {
            item {
                val isRest = tomorrowRow?.kind == DayKind.REST
                val allowed = isRest || DayWindow.canDeclareRest(now, tomorrow, repo.restDates(), zone)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton({ repo.setRest(tomorrow, !isRest); version++ }, enabled = allowed, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(if (isRest) R.string.today_rest_cancel else R.string.today_rest_tomorrow))
                    }
                    Text(
                        stringResource(if (allowed) R.string.today_rest_hint else R.string.today_rest_used),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun whyText(v: Violation): Int = when (v) {
    Violation.UNRESOLVED -> R.string.today_why_unresolved
    Violation.UNDER_HALF -> R.string.today_why_under_half
    Violation.PLAN_MISSING -> R.string.today_why_plan_missing
}

@Composable
private fun GoalRow(
    goal: Goal,
    focusMinutes: Int,
    canDone: Boolean,
    canNotDone: Boolean,
    onDone: () -> Unit,
    onNotDone: () -> Unit
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        val (icon, tint, label) = when (goal.state) {
            GoalState.DONE -> Triple(Icons.Outlined.CheckCircle, MaterialTheme.colorScheme.primary, R.string.today_done)
            GoalState.NOT_DONE -> Triple(Icons.Outlined.RemoveCircleOutline, MaterialTheme.colorScheme.onSurfaceVariant, R.string.today_not_done)
            GoalState.OPEN -> Triple(Icons.Outlined.RadioButtonUnchecked, MaterialTheme.colorScheme.onSurfaceVariant, R.string.today_open)
        }
        if (canDone) {
            IconButton(onDone) { Icon(icon, stringResource(R.string.today_mark_done, goal.text), tint = tint) }
        } else {
            Icon(icon, stringResource(label), tint = tint, modifier = Modifier.padding(12.dp).size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(goal.text, style = MaterialTheme.typography.bodyLarge)
            when (goal.type) {
                GoalType.FOCUS_MINUTES -> Text(
                    stringResource(R.string.today_focus_progress, focusMinutes, goal.targetMinutes ?: 0),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                GoalType.NO_SLIP -> if (goal.state == GoalState.OPEN) Text(
                    stringResource(R.string.today_noslip_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                GoalType.MANUAL -> Unit
            }
        }
        if (canNotDone) TextButton(onNotDone) { Text(stringResource(R.string.today_not_done)) }
    }
}

/** The goal's type under its field; a tap moves to the next type. */
@Composable
private fun TypeLabel(draft: PlanDraft, onNext: () -> Unit) {
    val text = when (draft.type) {
        GoalType.MANUAL -> stringResource(R.string.today_type_manual)
        GoalType.FOCUS_MINUTES -> stringResource(R.string.today_type_focus, draft.targetMinutes ?: 0)
        GoalType.NO_SLIP -> stringResource(R.string.today_type_noslip)
    }
    TextButton(onNext, Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.today_type_change, text), style = MaterialTheme.typography.labelMedium)
    }
}
