package io.github.warleysr.urgejournal

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
import androidx.compose.runtime.getValue
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
private fun Page(content: @Composable () -> Unit) {
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

@Composable
fun HomeScreen(
    entries: List<Entry>,
    hour: Int,
    onUrge: () -> Unit,
    onSlip: () -> Unit,
    onOpen: (Entry) -> Unit,
    onSettings: () -> Unit
) {
    val now = System.currentTimeMillis()
    val week = Insights.week(entries, now)
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
            week.daysSinceGaveIn?.let { stringResource(R.string.home_days_since, it) }
                ?: stringResource(R.string.home_no_slips),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Ember(stringResource(R.string.home_urge), onUrge)
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onSlip) { Text(stringResource(R.string.home_slip)) }
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
        }

        if (entries.isNotEmpty()) {
            Eyebrow(stringResource(R.string.recent_title))
            entries.takeLast(8).reversed().forEach { e ->
                val whenText = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                    .format(Instant.ofEpochMilli(e.time).atZone(ZoneId.systemDefault()))
                val feeling = e.answers[Q.FEELING]?.let { label("opt_", it) } ?: ""
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
        }
        Spacer(Modifier.height(16.dp))
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
    ai: AiState,
    providerLabel: String,
    onConsent: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onOutcome: (Outcome) -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val hour = Instant.ofEpochMilli(entry.time).atZone(ZoneId.systemDefault()).hour
    val plan = remember(entry.time) { Coach.plan(entry.answers, hour, entry.slipped) }
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

        Eyebrow(stringResource(R.string.plan_now))
        NumberedSteps(plan.steps.map { stringResource(stepRes(it)) })

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

        if (entry.slipped) {
            Text(
                stringResource(R.string.slip_outro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    val report = remember(raw) { ReportParser.parse(raw) }
    if (report == null) {
        Panel { Text(raw.trim(), style = MaterialTheme.typography.bodyLarge) }
        return
    }
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
        ReportList(R.string.deep_today, report.today)
        ReportList(R.string.deep_this_week, report.thisWeek)
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
fun SettingsScreen(settings: AiSettings, onDeleteAll: () -> Unit, onBack: () -> Unit) {
    var key by remember { mutableStateOf(settings.key) }
    var provider by remember { mutableStateOf(settings.provider) }
    var customBase by remember { mutableStateOf(settings.customBase) }
    var model by remember { mutableStateOf(settings.model) }
    var consent by remember { mutableStateOf(settings.consent) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<TestState>(TestState.Idle) }

    fun pick(p: Provider) {
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

    Page {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)

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
