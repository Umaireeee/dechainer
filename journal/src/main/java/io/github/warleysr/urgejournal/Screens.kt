package io.github.warleysr.urgejournal

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            // Keep clear of the status bar, the navigation bar and the keyboard.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) { content() }
}

private fun greetingRes(hour: Int): Int = when {
    hour < 5 -> R.string.greeting_night
    hour < 12 -> R.string.greeting_morning
    hour < 18 -> R.string.greeting_afternoon
    else -> R.string.greeting_evening
}

/** What still needs setting up, so the first minutes aren't a guessing game. */
data class SetupState(
    val dechainerInstalled: Boolean,
    val canReachDechainer: Boolean,
    val aiReady: Boolean
) {
    val complete: Boolean get() = dechainerInstalled && canReachDechainer && aiReady
}

@Composable
private fun SetupRow(done: Boolean, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (done) "✓" else "○",
            style = MaterialTheme.typography.bodyLarge,
            color = if (done) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/** The optional cards on Home, worked out by the caller so this screen only has to draw them. */
data class HomeCards(
    /** When a ride started that has not been checked in on, or 0. */
    val pendingRideAt: Long,
    /** A time of day where urges keep landing, when there is enough to say so and no heads-up is set. */
    val hot: Insights.HotWindow?,
    /** The last two weeks look heavier than the two before. */
    val heavier: Boolean,
    /** Minutes from midnight of the daily heads-up, or -1. */
    val nudgeMinute: Int,
    /** It is evening and today has not been answered yet. */
    val askDay: Boolean,
    /** Days that went to plan out of the days checked in over the last week, or null. */
    val planDays: Pair<Int, Int>?
)

@Composable
fun HomeScreen(
    entries: List<Entry>,
    hour: Int,
    setup: SetupState,
    cards: HomeCards,
    onRide: () -> Unit,
    onUrge: () -> Unit,
    onSlip: () -> Unit,
    onOpen: (Entry) -> Unit,
    onReview: () -> Unit,
    onSettings: () -> Unit,
    onCheckIn: () -> Unit,
    onDropRide: () -> Unit,
    onNudge: (Int) -> Unit,
    onDismissHot: () -> Unit,
    onDismissHeavy: () -> Unit,
    onShare: () -> Unit,
    onLog: () -> Unit,
    onDay: (DayResult) -> Unit,
    onFocus: (Int) -> Door.Result
) {
    val now = System.currentTimeMillis()
    val week = Insights.week(entries, now)
    val clean = Insights.cleanDays(entries, now)
    var tapped by remember { mutableStateOf(false) }
    var askFocus by remember { mutableStateOf(false) }
    var focusResult by remember { mutableStateOf<Door.Result?>(null) }

    if (askFocus) {
        AlertDialog(
            onDismissRequest = { askFocus = false },
            title = { Text(stringResource(R.string.focus_title)) },
            text = { Text(stringResource(R.string.focus_body)) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(25, 50, 90).forEach { minutes ->
                        TextButton(onClick = {
                            focusResult = onFocus(minutes)
                            askFocus = false
                        }) { Text(stringResource(R.string.focus_minutes, minutes)) }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { askFocus = false }) { Text(stringResource(R.string.delete_no)) } }
        )
    }
    val recentCount = entries.count { !it.isStub && it.time >= now - 30L * 24 * 60 * 60 * 1000 }
    Page {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(greetingRes(hour)),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onSettings) { Text(stringResource(R.string.settings_title)) }
        }
        Text(
            when {
                clean != null && clean.second >= 3 -> stringResource(R.string.home_clean_days, clean.first, clean.second)
                week.daysSinceGaveIn != null -> stringResource(R.string.home_days_since, week.daysSinceGaveIn)
                else -> stringResource(R.string.home_no_slips)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Ember(stringResource(R.string.home_urge), onTap = { tapped = true }, onStart = { tapped = false; onRide() })
            Text(
                stringResource(if (tapped) R.string.home_urge_hold else R.string.home_urge_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = if (tapped) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onUrge) { Text(stringResource(R.string.home_log_only)) }
                TextButton(onClick = onSlip) { Text(stringResource(R.string.home_slip)) }
            }
            OutlinedButton(onClick = { askFocus = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_focus))
            }
            focusResult?.let {
                Text(
                    stringResource(
                        when (it) {
                            Door.Result.SENT -> R.string.focus_sent
                            Door.Result.NOT_INSTALLED -> R.string.door_not_installed
                            Door.Result.NO_PERMISSION -> R.string.door_no_permission
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        if (cards.askDay) {
            Panel {
                Text(stringResource(R.string.day_title), style = MaterialTheme.typography.titleLarge)
                DayResult.entries.forEach { r ->
                    OutlinedButton(onClick = { onDay(r) }, modifier = Modifier.fillMaxWidth()) {
                        Text(label("day_", r))
                    }
                }
            }
        }

        if (cards.pendingRideAt != 0L) {
            val minutes = ((now - cards.pendingRideAt) / 60_000L).coerceAtLeast(0)
            Panel(highlight = true) {
                Text(stringResource(R.string.checkin_card_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.checkin_card_body, minutes), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onCheckIn, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.checkin_card_button))
                }
                TextButton(onClick = onDropRide) { Text(stringResource(R.string.checkin_card_drop)) }
            }
        }

        if (cards.heavier) {
            Panel {
                Text(stringResource(R.string.heavier_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.heavier_body), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onDismissHeavy) { Text(stringResource(R.string.card_dismiss)) }
            }
        }

        cards.hot?.let { hot ->
            Panel {
                Text(stringResource(R.string.hot_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(
                        R.string.hot_body,
                        Times.clock(hot.startHour * 60),
                        Times.clock(hot.endHour * 60),
                        hot.count,
                        hot.total
                    ),
                    style = MaterialTheme.typography.bodyLarge
                )
                Button(onClick = { onNudge(hot.nudgeMinute) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.hot_button, Times.clock(hot.nudgeMinute)))
                }
                TextButton(onClick = onDismissHot) { Text(stringResource(R.string.card_dismiss)) }
            }
        }

        if (!setup.complete) {
            Eyebrow(stringResource(R.string.setup_title))
            Panel {
                SetupRow(setup.dechainerInstalled, stringResource(R.string.setup_dechainer))
                if (setup.dechainerInstalled) {
                    SetupRow(setup.canReachDechainer, stringResource(R.string.setup_permission))
                }
                SetupRow(setup.aiReady, stringResource(R.string.setup_ai))
            }
        }

        Eyebrow(stringResource(R.string.week_title))
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat(week.total.toString(), stringResource(R.string.stat_logged), Modifier)
                Stat(week.resisted.toString(), stringResource(R.string.stat_resisted), Modifier)
                Stat(week.gaveIn.toString(), stringResource(R.string.stat_gave_in), Modifier)
            }
            WeekBars(Insights.days(entries, now))
            week.topFeeling?.let {
                Text(stringResource(R.string.week_top, label("opt_", it)), style = MaterialTheme.typography.bodyMedium)
            }
            week.peakHour?.let {
                Text(stringResource(R.string.week_peak, "%02d:00".format(it)), style = MaterialTheme.typography.bodyMedium)
            }
            cards.planDays?.let {
                Text(stringResource(R.string.week_plan_days, it.first, it.second), style = MaterialTheme.typography.bodyMedium)
            }
            if (week.total > 0) {
                TextButton(onClick = onShare) { Text(stringResource(R.string.share_week)) }
            }
        }
        if (recentCount >= 3) {
            OutlinedButton(onClick = onReview, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.review_button))
            }
        }

        if (entries.isNotEmpty()) {
            Eyebrow(stringResource(R.string.recent_title))
            entries.takeLast(5).reversed().forEach { e ->
                val whenText = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                    .format(Instant.ofEpochMilli(e.time).atZone(ZoneId.systemDefault()))
                val feeling = e.answers[Q.FEELING]?.let { label("opt_", it) }
                    ?: e.after?.let { label("after_", it) } ?: stringResource(R.string.entry_urge)
                val result = when {
                    e.slipped -> stringResource(R.string.result_slipped)
                    e.outcome == Outcome.RESISTED -> stringResource(R.string.result_through)
                    e.outcome == Outcome.GAVE_IN -> stringResource(R.string.result_gave_in)
                    else -> stringResource(R.string.result_open)
                }
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(e) }.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("$whenText · $feeling", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        result,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (e.gaveIn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
            OutlinedButton(onClick = onLog, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.log_open, entries.size))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** A ten-minute ride: the one screen for the peak of an urge. Nothing to answer, nothing to read but one line. */
@Composable
fun RideScreen(
    startedAt: Long,
    blockMinutes: Int,
    lockMinutes: Int,
    door: Door.Result?,
    step: Step,
    myPlan: MyPlan?,
    onDone: () -> Unit,
    onLonger: () -> Unit,
    onLeave: () -> Unit
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val elapsed = ((now - startedAt) / 1000L).coerceAtLeast(0)
    val remaining = (RIDE_SECONDS - elapsed).coerceAtLeast(0)
    val finished = remaining == 0L
    val inhale = elapsed % 10 < 4
    Page {
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.ride_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.ride_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        BreathCircle(
            inhale = inhale,
            centerText = if (finished) stringResource(R.string.ride_done_mark) else "%d:%02d".format(remaining / 60, remaining % 60),
            caption = stringResource(if (finished) R.string.ride_finished else if (inhale) R.string.ride_in else R.string.ride_out)
        )
        Text(
            when (door) {
                Door.Result.SENT -> stringResource(R.string.ride_locked, lockMinutes) + "\n" +
                    stringResource(R.string.ride_paused, blockMinutes)
                Door.Result.NOT_INSTALLED -> stringResource(R.string.door_not_installed)
                Door.Result.NO_PERMISSION -> stringResource(R.string.door_no_permission)
                null -> ""
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Eyebrow(stringResource(R.string.ride_one_thing))
        Panel(highlight = true) { Text(stringResource(stepRes(step)), style = MaterialTheme.typography.bodyLarge) }
        myPlan?.let {
            Eyebrow(stringResource(R.string.ride_your_rule))
            Panel { Text(it.text, style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic)) }
        }
        Spacer(Modifier.height(4.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (finished) R.string.ride_continue else R.string.ride_early))
        }
        OutlinedButton(onClick = onLonger, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.ride_longer))
        }
        TextButton(onClick = onLeave) { Text(stringResource(R.string.ride_leave)) }
    }
}

/** After the ride: how it stands, and what you tried. Two taps, then done. */
@Composable
fun AfterScreen(
    after: After?,
    tried: List<Step>,
    onAfter: (After) -> Unit,
    onToggle: (Step) -> Unit,
    onSave: () -> Unit,
    onDetails: () -> Unit,
    onAgain: () -> Unit,
    onGaveIn: () -> Unit,
    onBack: () -> Unit
) {
    Page {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.after_title), style = MaterialTheme.typography.headlineMedium)
        After.entries.forEach { a ->
            OptionCard(label("after_", a), selected = after == a) { onAfter(a) }
        }
        if (after != null) {
            Eyebrow(stringResource(R.string.after_tried))
            Step.entries.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { step ->
                        FilterChip(
                            selected = step in tried,
                            onClick = { onToggle(step) },
                            label = { Text(label("try_", step)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(4.dp))
            if (after == After.STILL) {
                Button(onClick = onAgain, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.after_again)) }
                OutlinedButton(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.after_save_open)) }
            } else {
                Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.after_save)) }
                OutlinedButton(onClick = onDetails, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.after_details)) }
            }
        }
        TextButton(onClick = onGaveIn) { Text(stringResource(R.string.after_gave_in)) }
        TextButton(onClick = onBack) { Text(stringResource(R.string.detail_back)) }
    }
}

@Composable
fun InterviewScreen(
    q: Q,
    total: Int,
    done: Int,
    slipped: Boolean,
    onAnswer: (Opt) -> Unit,
    onBack: () -> Unit
) {
    Page {
        Spacer(Modifier.height(8.dp))
        Dots(done, total)
        if (slipped && done == 0) {
            Text(
                stringResource(R.string.slip_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedContent(
            targetState = q,
            transitionSpec = { fadeIn(tween(260, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
            label = "question"
        ) { question ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label("q_", question), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                question.options.forEach { opt ->
                    OptionCard(label("opt_", opt)) { onAnswer(opt) }
                }
            }
        }
        TextButton(onClick = onBack) {
            Text(stringResource(if (done == 0) R.string.interview_cancel else R.string.interview_back))
        }
    }
}

@Composable
fun NoteScreen(slipped: Boolean, onDone: (String) -> Unit, onBack: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Page {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.note_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(if (slipped) R.string.note_hint_slip else R.string.note_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(1200) },
            minLines = 5,
            maxLines = 10,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            stringResource(R.string.note_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = { onDone(text) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (text.isBlank()) R.string.note_skip else R.string.note_continue))
        }
        TextButton(onClick = onBack) { Text(stringResource(R.string.interview_back)) }
    }
}

@Composable
fun PlanScreen(
    entry: Entry,
    history: List<Entry>,
    plans: List<MyPlan>,
    ai: AiState,
    providerLabel: String,
    onSavePlan: (String) -> Unit,
    onConsent: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onOutcome: (Outcome) -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val hour = Instant.ofEpochMilli(entry.time).atZone(ZoneId.systemDefault()).hour
    val plan = remember(entry.time) { Coach.plan(entry.answers, hour, entry.slipped, history.filter { it.time != entry.time }) }
    val myPlan = remember(entry.time, plans) { MyPlan.best(plans, entry.answers[Q.FEELING], hour) }
    var results by remember { mutableStateOf(emptyMap<DoorAction, Door.Result>()) }

    if (ai is AiState.NeedsConsent) {
        AlertDialog(
            onDismissRequest = { onConsent(false) },
            title = { Text(stringResource(R.string.consent_title)) },
            text = { Text(stringResource(R.string.consent_body, providerLabel)) },
            confirmButton = { TextButton(onClick = { onConsent(true) }) { Text(stringResource(R.string.consent_yes)) } },
            dismissButton = { TextButton(onClick = { onConsent(false) }) { Text(stringResource(R.string.consent_no)) } }
        )
    }

    Page {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (entry.slipped) R.string.plan_title_slip else R.string.plan_title),
            style = MaterialTheme.typography.headlineMedium
        )

        // Time-critical first: the block, then the first steps.
        Eyebrow(stringResource(R.string.plan_lock))
        Panel(highlight = true) {
            listOfNotNull(plan.primary, plan.secondary).forEachIndexed { i, action ->
                val text = if (action.kind == DoorAction.FOCUS_BLOCK)
                    stringResource(R.string.action_focus, action.minutes)
                else stringResource(R.string.action_impulse, action.minutes)
                val result = results[action]
                val send = { results = results + (action to Door.send(context, action)) }
                if (i == 0) Button(onClick = send, enabled = result != Door.Result.SENT, modifier = Modifier.fillMaxWidth()) { Text(text) }
                else OutlinedButton(onClick = send, enabled = result != Door.Result.SENT, modifier = Modifier.fillMaxWidth()) { Text(text) }
                result?.let {
                    Text(
                        stringResource(
                            when (it) {
                                Door.Result.SENT -> R.string.door_sent
                                Door.Result.NOT_INSTALLED -> R.string.door_not_installed
                                Door.Result.NO_PERMISSION -> R.string.door_no_permission
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Text(stringResource(R.string.door_note), style = MaterialTheme.typography.bodySmall)
        }

        myPlan?.let {
            Eyebrow(stringResource(R.string.ride_your_rule))
            Panel { Text(it.text, style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic)) }
        }

        // The app's own quick steps are a fallback. Once the AI has written personal ones they
        // would only repeat it, so they step aside.
        val aiHasSteps = ai is AiState.Ready && ReportParser.parse(ai.text)?.rightNow?.isNotEmpty() == true
        if (!aiHasSteps) {
            Eyebrow(stringResource(R.string.plan_now))
            NumberedSteps(plan.steps.map { stringResource(stepRes(it)) })
        }

        // Without a deep dive, still say why it's probably happening.
        if (ai !is AiState.Ready) {
            Eyebrow(stringResource(R.string.plan_why))
            Bullets(plan.reasons.map { stringResource(reasonRes(it)) })
        }

        // The deep dive.
        Eyebrow(stringResource(R.string.deep_title))
        when (ai) {
            is AiState.Loading -> Panel { Breathing(stringResource(R.string.deep_loading)) }
            is AiState.Ready -> ReportView(ai.text)
            is AiState.Failed -> Panel {
                Text(stringResource(errorRes(ai.error)), style = MaterialTheme.typography.bodyLarge)
                if (ai.detail.isNotBlank()) {
                    Text(ai.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.deep_retry)) }
                    if (ai.error == AiError.BAD_KEY || ai.error == AiError.BAD_MODEL) TextButton(onClick = onSettings) { Text(stringResource(R.string.settings_title)) }
                }
            }
            is AiState.NeedsKey -> Panel {
                Text(stringResource(R.string.deep_needs_key), style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = onSettings) { Text(stringResource(R.string.deep_add_key)) }
            }
            is AiState.Support -> Panel(highlight = true) {
                Text(stringResource(R.string.support_body), style = MaterialTheme.typography.bodyLarge)
            }
            else -> {}
        }

        if (plan.rules.isNotEmpty() && ai !is AiState.Ready) {
            Eyebrow(stringResource(R.string.plan_later))
            Bullets(plan.rules.map { stringResource(ruleRes(it)) })
        }

        // A plan works best when it is yours: the coach suggests, you write it, and it comes back
        // next time this kind of moment does.
        val feelingNow = entry.answers[Q.FEELING]
        val late = isLate(hour)
        val ruleTexts = plan.rules.map { stringResource(ruleRes(it)) }
        val prefill = stringResource(
            R.string.plan_if_prefix,
            feelingNow?.let { label("opt_", it).lowercase() } ?: stringResource(R.string.plan_if_urge),
            if (late) stringResource(R.string.plan_if_late) else ""
        )
        var editing by remember(entry.time) { mutableStateOf(false) }
        var saved by remember(entry.time) { mutableStateOf(false) }
        var draft by remember(entry.time) { mutableStateOf(prefill) }
        Eyebrow(stringResource(R.string.plan_yours))
        Panel {
            if (saved) {
                Text(stringResource(R.string.plan_saved), style = MaterialTheme.typography.bodyLarge)
            } else if (!editing) {
                Text(stringResource(R.string.plan_yours_body), style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.plan_write))
                }
            } else {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(300) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                ruleTexts.forEach { rule ->
                    AssistChip(
                        onClick = { draft = draft.trimEnd() + " " + rule.replaceFirstChar { c -> c.lowercase() } },
                        label = { Text(rule, maxLines = 2) }
                    )
                }
                Button(
                    onClick = {
                        onSavePlan(draft)
                        saved = true
                    },
                    enabled = draft.trim().length > prefill.trim().length,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.plan_save)) }
            }
        }

        if (entry.slipped) {
            Text(
                stringResource(R.string.slip_outro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.plan_done)) }
        } else if (entry.outcome != null) {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.plan_done)) }
        } else {
            Eyebrow(stringResource(R.string.plan_outcome_q))
            Button(onClick = { onOutcome(Outcome.RESISTED) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.plan_through))
            }
            OutlinedButton(onClick = { onOutcome(Outcome.GAVE_IN) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.plan_gave_in))
            }
            TextButton(onClick = onDone) { Text(stringResource(R.string.plan_later_btn)) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** The deep dive as a short letter: what's going on, then what to do, then what to learn. */
@Composable
fun ReportView(raw: String) {
    val context = LocalContext.current
    val report = remember(raw) { ReportParser.parse(raw) }
    if (report == null) {
        Panel { Text(raw.trim(), style = MaterialTheme.typography.bodyLarge) }
        return
    }
    val startExpanded = remember { AiSettings(context).expandAll }
    var expanded by remember(raw) { mutableStateOf(startExpanded) }
    val hasMore = report.today.isNotEmpty() || report.longTerm.isNotEmpty() ||
        report.understand.isNotEmpty() || report.pattern.isNotBlank()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (report.headline.isNotBlank()) {
            Text(report.headline, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (report.why.isNotBlank()) {
            Panel { Text(report.why, style = MaterialTheme.typography.bodyLarge) }
        }
        if (report.rightNow.isNotEmpty()) {
            Eyebrow(stringResource(R.string.deep_right_now))
            NumberedSteps(report.rightNow)
        }
        ReportList(R.string.deep_this_week, report.thisWeek)

        // The rest is a tap away, so the first screen stays short enough to act on.
        if (expanded) {
            ReportList(R.string.deep_today, report.today)
            ReportList(R.string.deep_long_term, report.longTerm)
            if (report.understand.isNotEmpty()) {
                Eyebrow(stringResource(R.string.deep_understand))
                report.understand.forEach { (title, body) ->
                    Panel {
                        if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleLarge)
                        if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (report.pattern.isNotBlank()) {
                Eyebrow(stringResource(R.string.deep_pattern))
                Text(report.pattern, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (hasMore) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.deep_less else R.string.deep_more))
            }
        }
        if (report.encouragement.isNotBlank()) {
            Text(
                report.encouragement,
                style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReportList(titleRes: Int, items: List<String>) {
    if (items.isEmpty()) return
    Eyebrow(stringResource(titleRes))
    Bullets(items)
}

@Composable
fun ReviewScreen(
    state: AiState,
    providerLabel: String,
    onConsent: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit
) {
    if (state is AiState.NeedsConsent) {
        AlertDialog(
            onDismissRequest = { onConsent(false) },
            title = { Text(stringResource(R.string.consent_title)) },
            text = { Text(stringResource(R.string.review_consent_body, providerLabel)) },
            confirmButton = { TextButton(onClick = { onConsent(true) }) { Text(stringResource(R.string.consent_yes)) } },
            dismissButton = { TextButton(onClick = { onConsent(false) }) { Text(stringResource(R.string.consent_no)) } }
        )
    }
    Page {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.review_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.review_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        when (state) {
            is AiState.Loading -> Panel { Breathing(stringResource(R.string.review_loading)) }
            is AiState.Ready -> {
                ReportView(state.text)
                OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.review_refresh))
                }
            }
            is AiState.Failed -> Panel {
                Text(stringResource(errorRes(state.error)), style = MaterialTheme.typography.bodyLarge)
                if (state.detail.isNotBlank()) {
                    Text(state.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.deep_retry)) }
                    TextButton(onClick = onSettings) { Text(stringResource(R.string.settings_title)) }
                }
            }
            is AiState.NeedsKey -> Panel {
                Text(stringResource(R.string.deep_needs_key), style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = onSettings) { Text(stringResource(R.string.deep_add_key)) }
            }
            else -> {}
        }
        TextButton(onClick = onBack) { Text(stringResource(R.string.detail_back)) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun DetailScreen(entry: Entry, onOutcome: (Outcome) -> Unit, onBack: () -> Unit) {
    val whenText = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .format(Instant.ofEpochMilli(entry.time).atZone(ZoneId.systemDefault()))
    Page {
        Spacer(Modifier.height(8.dp))
        Text(whenText, style = MaterialTheme.typography.headlineSmall)
        Panel {
            Q.entries.forEach { q ->
                entry.answers[q]?.let {
                    Text(
                        label("q_", q) + "  " + label("opt_", it),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            entry.after?.let {
                Text(stringResource(R.string.detail_after, label("after_", it)), style = MaterialTheme.typography.bodyMedium)
            }
            if (entry.tried.isNotEmpty()) {
                // joinToString is not inline, so the composable labels are looked up first.
                val triedNames = entry.tried.map { s -> label("try_", s).lowercase() }
                Text(
                    stringResource(R.string.detail_tried, triedNames.joinToString(", ")),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (entry.note.isNotBlank()) {
                Text(
                    entry.note,
                    style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic)
                )
            }
        }
        Eyebrow(stringResource(R.string.deep_title))
        val report = entry.report
        if (report != null) ReportView(report)
        else Text(
            stringResource(R.string.detail_no_report),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!entry.slipped && entry.outcome == null) {
            Eyebrow(stringResource(R.string.plan_outcome_q))
            Button(onClick = { onOutcome(Outcome.RESISTED) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.plan_through))
            }
            OutlinedButton(onClick = { onOutcome(Outcome.GAVE_IN) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.plan_gave_in))
            }
        }
        TextButton(onClick = onBack) { Text(stringResource(R.string.detail_back)) }
    }
}

private sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data object Ok : TestState
    data class Failed(val error: AiError, val detail: String) : TestState
}

@Composable
fun SettingsScreen(
    settings: AiSettings,
    store: JournalStore,
    reminders: ReminderSettings,
    plans: List<MyPlan>,
    onPlansChanged: () -> Unit,
    onNeedNotifications: (() -> Unit) -> Unit,
    onDeleteAll: () -> Unit,
    onImported: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var key by remember { mutableStateOf(settings.key) }
    var provider by remember { mutableStateOf(settings.provider) }
    var customBase by remember { mutableStateOf(settings.customBase) }
    var model by remember { mutableStateOf(settings.model) }
    var consent by remember { mutableStateOf(settings.consent) }
    var deep by remember { mutableStateOf(settings.deep) }
    var expandAll by remember { mutableStateOf(settings.expandAll) }
    var about by remember { mutableStateOf(settings.about) }
    var backupMessage by remember { mutableStateOf<Int?>(null) }
    var backupCount by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var checkIn by remember { mutableStateOf(reminders.checkIn) }
    var nudge by remember { mutableIntStateOf(reminders.nudgeMinute) }
    var evening by remember { mutableIntStateOf(reminders.eveningMinute) }
    var editingPlan by remember { mutableStateOf<MyPlan?>(null) }
    var planText by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<TestState>(TestState.Idle) }
    var available by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelsFailed by remember { mutableStateOf(false) }

    fun pick(p: Provider) {
        available = emptyList()
        modelsFailed = false
        provider = p
        model = p.defaultModel
        consent = false
        saved = false
        test = TestState.Idle
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDeleteAll() }) { Text(stringResource(R.string.delete_yes)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.delete_no)) } }
        )
    }

    editingPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { editingPlan = null },
            title = { Text(stringResource(R.string.plans_edit)) },
            text = {
                OutlinedTextField(
                    value = planText,
                    onValueChange = { planText = it.take(300) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.editPlan(plan.id, planText)
                    editingPlan = null
                    onPlansChanged()
                }) { Text(stringResource(R.string.settings_save)) }
            },
            dismissButton = { TextButton(onClick = { editingPlan = null }) { Text(stringResource(R.string.delete_no)) } }
        )
    }

    Page {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)

        Eyebrow(stringResource(R.string.reminders_title))
        Text(
            stringResource(R.string.reminders_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            Modifier.fillMaxWidth().clickable {
                val next = !checkIn
                if (next) onNeedNotifications { checkIn = true; reminders.checkIn = true }
                else { checkIn = false; reminders.checkIn = false }
            },
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Checkbox(checked = checkIn, onCheckedChange = null)
            Text(stringResource(R.string.reminders_checkin), style = MaterialTheme.typography.bodyMedium)
        }
        Text(stringResource(R.string.reminders_nudge), style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = nudge < 0,
                onClick = {
                    nudge = -1
                    reminders.nudgeMinute = -1
                    Notifier.rearmNudge(context)
                },
                label = { Text(stringResource(R.string.reminders_off)) }
            )
            listOf(20 * 60, 21 * 60, 21 * 60 + 30, 22 * 60, 23 * 60).forEach { minute ->
                FilterChip(
                    selected = nudge == minute,
                    onClick = {
                        onNeedNotifications {
                            nudge = minute
                            reminders.nudgeMinute = minute
                            Notifier.rearmNudge(context)
                        }
                    },
                    label = { Text(Times.clock(minute)) }
                )
            }
        }
        Text(stringResource(R.string.reminders_evening), style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = evening < 0,
                onClick = {
                    evening = -1
                    reminders.eveningMinute = -1
                    Notifier.rearmEvening(context)
                },
                label = { Text(stringResource(R.string.reminders_off)) }
            )
            listOf(19 * 60, 20 * 60, 21 * 60, 22 * 60).forEach { minute ->
                FilterChip(
                    selected = evening == minute,
                    onClick = {
                        onNeedNotifications {
                            evening = minute
                            reminders.eveningMinute = minute
                            Notifier.rearmEvening(context)
                        }
                    },
                    label = { Text(Times.clock(minute)) }
                )
            }
        }
        if (!Notifier.canPost(context)) {
            Text(
                stringResource(R.string.reminders_blocked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Eyebrow(stringResource(R.string.plans_title))
        if (plans.isEmpty()) {
            Text(
                stringResource(R.string.plans_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        plans.forEach { plan ->
            Panel {
                Text(plan.text, style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { planText = plan.text; editingPlan = plan }) {
                        Text(stringResource(R.string.plans_edit))
                    }
                    TextButton(onClick = { store.removePlan(plan.id); onPlansChanged() }) {
                        Text(stringResource(R.string.plans_remove))
                    }
                }
            }
        }

        Eyebrow(stringResource(R.string.settings_ai))
        Text(
            stringResource(R.string.settings_ai_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Which service. Pasting a key picks it automatically.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Provider.entries.forEach { p ->
                FilterChip(selected = provider == p, onClick = { pick(p) }, label = { Text(p.label) })
            }
        }
        if (provider != Provider.CUSTOM) {
            Text(
                stringResource(R.string.settings_key_hint, provider.keyHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            OutlinedTextField(
                value = customBase,
                onValueChange = { customBase = it; saved = false; test = TestState.Idle },
                label = { Text(stringResource(R.string.settings_base)) },
                supportingText = { Text(stringResource(R.string.settings_base_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        OutlinedTextField(
            value = key,
            onValueChange = { new ->
                key = new
                saved = false
                test = TestState.Idle
                val guess = Provider.detect(new)
                if (guess != null && guess != provider) pick(guess)
            },
            label = { Text(stringResource(R.string.settings_key)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it; saved = false; test = TestState.Idle },
            label = { Text(stringResource(R.string.settings_model)) },
            supportingText = { Text(stringResource(R.string.settings_model_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (provider.models.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                provider.models.forEach { m ->
                    AssistChip(onClick = { model = m; saved = false; test = TestState.Idle }, label = { Text(m) })
                }
            }
        }

        // Ask the service which models this key can use; names change, so don't rely on a fixed list.
        val canLoad = key.isNotBlank() && (provider != Provider.CUSTOM || customBase.isNotBlank())
        OutlinedButton(
            onClick = {
                loadingModels = true
                modelsFailed = false
                val base = if (provider == Provider.CUSTOM) customBase else provider.baseUrl
                val k = key.trim()
                Thread {
                    val list = AiClient.listModels(base, k)
                    available = list.orEmpty()
                    modelsFailed = list == null
                    loadingModels = false
                }.start()
            },
            enabled = canLoad && !loadingModels,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(if (loadingModels) R.string.settings_loading_models else R.string.settings_load_models)) }
        if (modelsFailed) {
            Text(
                stringResource(R.string.settings_models_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (available.isNotEmpty()) {
            Text(
                stringResource(R.string.settings_models_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val query = model.trim().removePrefix("models/")
            val shown = available.filter { it.contains(query, ignoreCase = true) }.ifEmpty { available }.take(14)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.forEach { m ->
                    AssistChip(onClick = { model = m; saved = false; test = TestState.Idle }, label = { Text(m) })
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable { consent = !consent; saved = false },
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Checkbox(checked = consent, onCheckedChange = { consent = it; saved = false })
            Text(stringResource(R.string.settings_consent, provider.label), style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = {
                settings.key = key
                settings.provider = provider
                settings.customBase = customBase
                settings.model = model
                settings.consent = consent
                settings.about = about
                settings.deep = deep
                settings.expandAll = expandAll
                saved = true
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(if (saved) R.string.settings_saved else R.string.settings_save)) }

        // A quick check that the key, the address and the model name actually work together.
        val canTest = key.isNotBlank() && model.isNotBlank() && (provider != Provider.CUSTOM || customBase.isNotBlank())
        OutlinedButton(
            onClick = {
                test = TestState.Running
                val p = provider
                val base = if (p == Provider.CUSTOM) customBase else p.baseUrl
                val k = key.trim()
                val m = model.trim()
                Thread {
                    test = when (val r = AiClient.chat(p, base, k, m, "Reply with the single word OK.", "Say OK.")) {
                        is AiResult.Ok -> TestState.Ok
                        is AiResult.Failed -> TestState.Failed(r.error, r.detail)
                    }
                }.start()
            },
            enabled = canTest && test != TestState.Running,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.settings_test)) }
        when (val t = test) {
            is TestState.Running -> Breathing(stringResource(R.string.settings_testing))
            is TestState.Ok -> Text(
                stringResource(R.string.settings_test_ok),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.tertiary
            )
            is TestState.Failed -> {
                Text(stringResource(errorRes(t.error)), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                if (t.detail.isNotBlank()) {
                    Text(t.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {}
        }

        Eyebrow(stringResource(R.string.settings_about))
        Text(
            stringResource(R.string.settings_about_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = about,
            onValueChange = { about = it.take(ABOUT_LIMIT); saved = false },
            label = { Text(stringResource(R.string.settings_about_label)) },
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            Modifier.fillMaxWidth().clickable { deep = !deep; saved = false },
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Checkbox(checked = deep, onCheckedChange = { deep = it; saved = false })
            Text(stringResource(R.string.settings_deep), style = MaterialTheme.typography.bodyMedium)
        }
        Row(
            Modifier.fillMaxWidth().clickable { expandAll = !expandAll; saved = false },
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Checkbox(checked = expandAll, onCheckedChange = { expandAll = it; saved = false })
            Text(stringResource(R.string.settings_expand), style = MaterialTheme.typography.bodyMedium)
        }

        Eyebrow(stringResource(R.string.settings_backup))
        Text(
            stringResource(R.string.settings_backup_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                Share.file(context, "urge-journal-backup.txt", "text/plain", store.exportJson())
            }) { Text(stringResource(R.string.settings_export)) }
            OutlinedButton(onClick = {
                val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                val added = store.importJson(clip)
                backupCount = added
                backupMessage = if (added > 0) R.string.settings_import_ok else R.string.settings_import_none
                if (added > 0) onImported()
            }) { Text(stringResource(R.string.settings_import)) }
        }
        backupMessage?.let {
            Text(
                stringResource(it, backupCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        Eyebrow(stringResource(R.string.settings_privacy))
        Text(
            stringResource(R.string.settings_privacy_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start
        )
        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.delete_title))
        }
        TextButton(onClick = onBack) { Text(stringResource(R.string.detail_back)) }
        Spacer(Modifier.height(16.dp))
    }
}

/** How long the guided ride lasts. Urges usually crest and fade inside it. */
const val RIDE_SECONDS = 10L * 60

private fun stepRes(s: Step): Int = when (s) {
    Step.BREATHE -> R.string.step_breathe
    Step.LEAVE_ROOM -> R.string.step_leave_room
    Step.COLD_WATER -> R.string.step_cold_water
    Step.WALK -> R.string.step_walk
    Step.MESSAGE_SOMEONE -> R.string.step_message_someone
    Step.PHONE_OUT -> R.string.step_phone_out
    Step.SMALL_TASK -> R.string.step_small_task
    Step.SLEEP -> R.string.step_sleep
}

private fun reasonRes(r: Reason): Int = when (r) {
    Reason.TIRED_LATE -> R.string.reason_tired_late
    Reason.STRESS_ESCAPE -> R.string.reason_stress_escape
    Reason.BORED_AVOIDING -> R.string.reason_bored_avoiding
    Reason.LONELY -> R.string.reason_lonely
    Reason.ANXIOUS -> R.string.reason_anxious
    Reason.THOUGHT_TRAP -> R.string.reason_thought_trap
    Reason.GENERAL -> R.string.reason_general
}

private fun ruleRes(r: Rule): Int = when (r) {
    Rule.PHONE_CHARGES_OUTSIDE -> R.string.rule_phone_charges_outside
    Rule.EARLIER_BLOCK -> R.string.rule_earlier_block
    Rule.EXTEND_SCHEDULE -> R.string.rule_extend_schedule
    Rule.OTHER_DEVICE -> R.string.rule_other_device
    Rule.OFFLINE_URGE -> R.string.rule_offline_urge
    Rule.STUDY_START -> R.string.rule_study_start
    Rule.TELL_SOMEONE -> R.string.rule_tell_someone
    Rule.SLEEP_EARLIER -> R.string.rule_sleep_earlier
}

private fun errorRes(e: AiError): Int = when (e) {
    AiError.BAD_KEY -> R.string.error_bad_key
    AiError.NO_CREDITS -> R.string.error_no_credits
    AiError.RATE_LIMIT -> R.string.error_rate_limit
    AiError.NETWORK -> R.string.error_network
    AiError.SERVER -> R.string.error_server
    AiError.EMPTY -> R.string.error_empty
    AiError.BAD_MODEL -> R.string.error_bad_model
}
