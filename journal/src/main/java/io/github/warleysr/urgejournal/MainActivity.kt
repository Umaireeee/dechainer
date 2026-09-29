package io.github.warleysr.urgejournal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import java.time.LocalTime
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UrgeTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color(0xFF1C1A17), Color(0xFF231F1A)))),
                    color = Color.Transparent,
                    // Without this the text falls back to black on the dark page.
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) {
                    App()
                }
            }
        }
    }
}

private enum class Screen { HOME, INTERVIEW, NOTE, PLAN, DETAIL, SETTINGS }

/** Where the AI deep dive is for the current entry. */
sealed interface AiState {
    data object Idle : AiState
    data object NeedsKey : AiState
    data object NeedsConsent : AiState
    data object Loading : AiState
    /** The note suggests crisis, so care is shown instead of a report. */
    data object Support : AiState
    data class Ready(val text: String) : AiState
    data class Failed(val error: AiError, val detail: String = "") : AiState
}

@Composable
private fun App() {
    val context = LocalContext.current
    val store = remember { JournalStore(context) }
    val settings = remember { AiSettings(context) }
    var entries by remember { mutableStateOf(store.all()) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var slipped by remember { mutableStateOf(false) }
    var hour by remember { mutableIntStateOf(LocalTime.now().hour) }
    var answers by remember { mutableStateOf(emptyMap<Q, Opt>()) }
    var current by remember { mutableStateOf<Entry?>(null) }
    var detail by remember { mutableStateOf<Entry?>(null) }
    var ai by remember { mutableStateOf<AiState>(AiState.Idle) }

    fun refresh() {
        entries = store.all()
    }

    fun goHome() {
        hour = LocalTime.now().hour
        refresh()
        current = null
        detail = null
        ai = AiState.Idle
        screen = Screen.HOME
    }

    fun runAi(entry: Entry) {
        if (Safety.needsSupport(entry.note)) {
            ai = AiState.Support
            return
        }
        if (!settings.configured) {
            ai = AiState.NeedsKey
            return
        }
        if (!settings.consent) {
            ai = AiState.NeedsConsent
            return
        }
        ai = AiState.Loading
        val provider = settings.provider
        val baseUrl = settings.baseUrl
        val key = settings.key
        val model = settings.model
        val user = Prompt.user(entry, Insights.summary(store.all(), System.currentTimeMillis()))
        thread {
            when (val r = AiClient.chat(provider, baseUrl, key, model, Prompt.SYSTEM, user)) {
                is AiResult.Ok -> {
                    store.setReport(entry.time, r.text)
                    refresh()
                    ai = AiState.Ready(r.text)
                }
                is AiResult.Failed -> ai = AiState.Failed(r.error, r.detail)
            }
        }
    }

    fun startInterview(isSlip: Boolean) {
        slipped = isSlip
        hour = LocalTime.now().hour
        answers = emptyMap()
        screen = Screen.INTERVIEW
    }

    fun finish(note: String) {
        val entry = Entry(System.currentTimeMillis(), slipped, answers, null, note.trim())
        store.add(entry)
        refresh()
        current = entry
        ai = AiState.Idle
        screen = Screen.PLAN
        runAi(entry)
    }

    fun answer(q: Q, opt: Opt) {
        val next = answers + (q to opt)
        answers = next
        if (QuestionTree.next(next, hour, slipped) == null) screen = Screen.NOTE
    }

    fun stepBack() {
        val last = QuestionTree.sequence(answers[Q.FEELING], hour, slipped).lastOrNull { it in answers }
        if (last == null) goHome() else answers = answers - last
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            entries = entries,
            hour = hour,
            onUrge = { startInterview(false) },
            onSlip = { startInterview(true) },
            onOpen = { detail = it; screen = Screen.DETAIL },
            onSettings = { screen = Screen.SETTINGS }
        )

        Screen.INTERVIEW -> {
            BackHandler { stepBack() }
            val q = QuestionTree.next(answers, hour, slipped)
            if (q != null) {
                InterviewScreen(
                    q = q,
                    total = QuestionTree.sequence(answers[Q.FEELING], hour, slipped).size,
                    done = answers.size,
                    slipped = slipped,
                    onAnswer = { answer(q, it) },
                    onBack = { stepBack() }
                )
            }
        }

        Screen.NOTE -> {
            BackHandler {
                // Undo the last answer and return to the questions.
                val last = QuestionTree.sequence(answers[Q.FEELING], hour, slipped).lastOrNull { it in answers }
                if (last != null) answers = answers - last
                screen = Screen.INTERVIEW
            }
            NoteScreen(
                slipped = slipped,
                onDone = { finish(it) },
                onBack = {
                    val last = QuestionTree.sequence(answers[Q.FEELING], hour, slipped).lastOrNull { it in answers }
                    if (last != null) answers = answers - last
                    screen = Screen.INTERVIEW
                }
            )
        }

        Screen.PLAN -> {
            BackHandler { goHome() }
            current?.let { entry ->
                PlanScreen(
                    entry = entry,
                    ai = ai,
                    providerLabel = settings.provider.label,
                    onConsent = { yes ->
                        if (yes) {
                            settings.consent = true
                            runAi(entry)
                        } else {
                            ai = AiState.Idle
                        }
                    },
                    onRetry = { runAi(entry) },
                    onSettings = { screen = Screen.SETTINGS },
                    onOutcome = { outcome ->
                        store.setOutcome(entry.time, outcome)
                        goHome()
                    },
                    onDone = { goHome() }
                )
            }
        }

        Screen.DETAIL -> {
            BackHandler { goHome() }
            detail?.let { picked ->
                // Re-read it so a report or outcome saved since is shown.
                val entry = entries.firstOrNull { it.time == picked.time } ?: picked
                DetailScreen(
                    entry = entry,
                    onOutcome = { outcome ->
                        store.setOutcome(entry.time, outcome)
                        goHome()
                    },
                    onBack = { goHome() }
                )
            }
        }

        Screen.SETTINGS -> {
            BackHandler { screen = if (current != null) Screen.PLAN else Screen.HOME }
            SettingsScreen(
                settings = settings,
                onDeleteAll = {
                    store.clear()
                    goHome()
                },
                onBack = {
                    val entry = current
                    if (entry != null) {
                        screen = Screen.PLAN
                        // Back from adding a key: try the deep dive now.
                        if (ai is AiState.NeedsKey || ai is AiState.Failed) runAi(entry)
                    } else {
                        screen = Screen.HOME
                    }
                }
            )
        }
    }
}
