package io.github.warleysr.urgejournal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UrgeTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App()
                }
            }
        }
    }
}

private enum class Screen { HOME, INTERVIEW, PLAN }

/** Looks up `<prefix><name>` in strings.xml, falling back to the raw name if it is missing. */
@Composable
private fun label(prefix: String, key: Enum<*>): String {
    val ctx = LocalContext.current
    val id = ctx.resources.getIdentifier(prefix + key.name.lowercase(), "string", ctx.packageName)
    return if (id != 0) ctx.getString(id) else key.name
}

@Composable
private fun App() {
    val context = LocalContext.current
    val store = remember { JournalStore(context) }
    var entries by remember { mutableStateOf(store.all()) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var slipped by remember { mutableStateOf(false) }
    var hour by remember { mutableIntStateOf(LocalTime.now().hour) }
    var answers by remember { mutableStateOf(emptyMap<Q, Opt>()) }
    var current by remember { mutableStateOf<Entry?>(null) }

    fun startInterview(isSlip: Boolean) {
        slipped = isSlip
        hour = LocalTime.now().hour
        answers = emptyMap()
        screen = Screen.INTERVIEW
    }

    fun answer(q: Q, opt: Opt) {
        val next = answers + (q to opt)
        answers = next
        if (QuestionTree.next(next, hour, slipped) == null) {
            val entry = Entry(System.currentTimeMillis(), slipped, next, null)
            store.add(entry)
            entries = store.all()
            current = entry
            screen = Screen.PLAN
        }
    }

    fun goHome() {
        entries = store.all()
        current = null
        screen = Screen.HOME
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            entries = entries,
            onUrge = { startInterview(false) },
            onSlip = { startInterview(true) }
        )
        Screen.INTERVIEW -> {
            val q = QuestionTree.next(answers, hour, slipped)
            BackHandler {
                // Step back one question, or leave if there is nothing to undo.
                val last = QuestionTree.sequence(answers[Q.FEELING], hour, slipped).lastOrNull { it in answers }
                if (last == null) goHome() else answers = answers - last
            }
            if (q != null) {
                InterviewScreen(
                    q = q,
                    total = QuestionTree.sequence(answers[Q.FEELING], hour, slipped).size,
                    done = answers.size,
                    slipped = slipped,
                    onAnswer = { answer(q, it) },
                    onCancel = ::goHome
                )
            }
        }
        Screen.PLAN -> {
            val entry = current
            BackHandler { goHome() }
            if (entry != null) {
                PlanScreen(
                    entry = entry,
                    onOutcome = { outcome ->
                        store.setOutcome(entry.time, outcome)
                        goHome()
                    },
                    onDone = ::goHome
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(entries: List<Entry>, onUrge: () -> Unit, onSlip: () -> Unit) {
    val week = Insights.week(entries, System.currentTimeMillis())
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(
            week.daysSinceGaveIn?.let { stringResource(R.string.home_days_since, it) }
                ?: stringResource(R.string.home_no_slips),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onUrge, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text(stringResource(R.string.home_urge), style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(onClick = onSlip, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.home_slip))
        }

        SectionTitle(stringResource(R.string.week_title))
        Panel {
            if (week.total == 0) {
                Text(stringResource(R.string.week_empty), style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(stringResource(R.string.week_counts, week.total, week.resisted, week.gaveIn), style = MaterialTheme.typography.bodyLarge)
                week.topFeeling?.let {
                    Text(stringResource(R.string.week_top, label("opt_", it)), style = MaterialTheme.typography.bodyLarge)
                }
                week.peakHour?.let {
                    Text(stringResource(R.string.week_peak, "%02d:00".format(it)), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        if (entries.isNotEmpty()) {
            SectionTitle(stringResource(R.string.recent_title))
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("$whenText · $feeling", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        result,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (e.gaveIn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InterviewScreen(
    q: Q,
    total: Int,
    done: Int,
    slipped: Boolean,
    onAnswer: (Opt) -> Unit,
    onCancel: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { (done.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            stringResource(R.string.interview_progress, done + 1, total),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (slipped && done == 0) {
            Text(stringResource(R.string.slip_intro), style = MaterialTheme.typography.bodyLarge)
        }
        Text(label("q_", q), style = MaterialTheme.typography.headlineSmall)
        q.options.forEach { opt ->
            OutlinedButton(onClick = { onAnswer(opt) }, modifier = Modifier.fillMaxWidth()) {
                Text(label("opt_", opt))
            }
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.interview_cancel)) }
    }
}

@Composable
private fun PlanScreen(entry: Entry, onOutcome: (Outcome) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val hour = Instant.ofEpochMilli(entry.time).atZone(ZoneId.systemDefault()).hour
    val plan = remember(entry) { Coach.plan(entry.answers, hour, entry.slipped) }
    var results by remember { mutableStateOf(emptyMap<DoorAction, Door.Result>()) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(if (entry.slipped) R.string.plan_title_slip else R.string.plan_title),
            style = MaterialTheme.typography.headlineMedium
        )

        SectionTitle(stringResource(R.string.plan_why))
        Panel {
            plan.reasons.forEach { Text("• " + label("reason_", it), style = MaterialTheme.typography.bodyLarge) }
        }

        SectionTitle(stringResource(R.string.plan_now))
        Panel {
            plan.steps.forEach { Text("• " + label("step_", it), style = MaterialTheme.typography.bodyLarge) }
        }

        SectionTitle(stringResource(R.string.plan_lock))
        listOfNotNull(plan.primary, plan.secondary).forEachIndexed { i, action ->
            val text = if (action.kind == DoorAction.FOCUS_BLOCK)
                stringResource(R.string.action_focus, action.minutes)
            else stringResource(R.string.action_impulse, action.minutes)
            val result = results[action]
            if (i == 0) Button(
                onClick = { results = results + (action to Door.send(context, action)) },
                enabled = result != Door.Result.SENT,
                modifier = Modifier.fillMaxWidth()
            ) { Text(text) } else OutlinedButton(
                onClick = { results = results + (action to Door.send(context, action)) },
                enabled = result != Door.Result.SENT,
                modifier = Modifier.fillMaxWidth()
            ) { Text(text) }
            result?.let {
                Text(
                    stringResource(
                        when (it) {
                            Door.Result.SENT -> R.string.door_sent
                            Door.Result.NOT_INSTALLED -> R.string.door_not_installed
                            Door.Result.NO_PERMISSION -> R.string.door_no_permission
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (it == Door.Result.SENT) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                )
            }
        }
        Text(
            stringResource(R.string.door_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (plan.rules.isNotEmpty()) {
            SectionTitle(stringResource(R.string.plan_later))
            Panel {
                plan.rules.forEach { Text("• " + label("rule_", it), style = MaterialTheme.typography.bodyLarge) }
            }
        }

        if (entry.slipped) {
            Text(stringResource(R.string.slip_outro), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.plan_done)) }
        } else {
            SectionTitle(stringResource(R.string.plan_outcome_q))
            Button(onClick = { onOutcome(Outcome.RESISTED) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.plan_through))
            }
            OutlinedButton(onClick = { onOutcome(Outcome.GAVE_IN) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.plan_gave_in))
            }
            TextButton(onClick = onDone) { Text(stringResource(R.string.plan_later_btn)) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}
