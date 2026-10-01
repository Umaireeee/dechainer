package io.github.warleysr.dechainer.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    RepeatWhileVisible(1000) { now = TrustedClock.now(ctx) }

    val today = DayWindow.dateOf(now, zone)
    val tomorrow = today.plusDays(1)
    // Reads again after every write, and when the clock crosses a day.
    val row = remember(today, version) { repo.day(today) }
    val goals = remember(today, version) { repo.goals(today) }
    val plan = remember(tomorrow, version) { repo.goals(tomorrow) }
    val tomorrowRow = remember(tomorrow, version) { repo.day(tomorrow) }
    val canEdit = DayWindow.canEditPlan(now, tomorrow, zone)
    val drafts = remember(tomorrow, version) { mutableStateListOf<String>().apply { addAll(plan.map { it.text }.ifEmpty { listOf("", "", "") }) } }

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
                Text(g.text + if (g.state != GoalState.OPEN) "  (${g.state.name.lowercase()})" else "", Modifier.weight(1f))
                if (g.state == GoalState.OPEN && g.type == GoalType.MANUAL) {
                    if (DayWindow.canMarkDone(now, today, zone)) TextButton({ repo.setGoal(g.id, GoalState.DONE, ResolvedBy.USER); version++ }) { Text(stringResource(R.string.today_done)) }
                    if (DayWindow.canMarkNotDone(now, today, zone)) TextButton({ repo.setGoal(g.id, GoalState.NOT_DONE, ResolvedBy.USER); version++ }) { Text(stringResource(R.string.today_not_done)) }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)); Text(stringResource(R.string.today_tomorrow), style = MaterialTheme.typography.titleLarge) }
        if (!canEdit) {
            items(plan, key = { "p${it.id}" }) { Text("• " + it.text) }
            item { Text(stringResource(R.string.today_tomorrow_locked), style = MaterialTheme.typography.bodySmall) }
        } else {
            items(drafts.size) { i ->
                OutlinedTextField(drafts[i], { drafts[i] = it.take(120) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.today_goal_hint, i + 1)) })
            }
            item {
                Row {
                    if (drafts.size < DayRules.MAX_GOALS) TextButton({ drafts.add("") }) { Text(stringResource(R.string.today_add_goal)) }
                    Button({
                        // Re-checked at the moment of saving: the window may have closed while typing.
                        if (DayWindow.canEditPlan(TrustedClock.now(ctx), tomorrow, zone) &&
                            repo.savePlan(tomorrow, drafts.map { NewGoal(it) }, TrustedClock.now(ctx))) {
                            LockEngine.requestSync(ctx); version++
                        }
                    }) { Text(stringResource(R.string.today_save_plan)) }
                }
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
