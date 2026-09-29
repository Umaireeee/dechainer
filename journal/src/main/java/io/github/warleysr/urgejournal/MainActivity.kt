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

private enum class Screen { HOME, INTERVIEW, NOTE, PLAN, DETAIL, SETTINGS, REVIEW }

/** Where an AI reply is: for one entry's deep dive, or for the weekly review. */
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

/** A cached weekly review counts as fresh for this long. */
private const val REVIEW_FRESH_MS = 12L * 60 * 60 * 1000

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
    var review by remember { mutableStateOf<AiState>(AiState.Idle) }
    // Bumped whenever the screen moves on, so a slow reply can't land on the wrong screen.
    var runId by remember { mutableIntStateOf(0) }

    fun refresh() {
        entries = store.all()
    }

    fun goHome() {
        runId++
        hour = LocalTime.now().hour
        refresh()
        current = null
        detail = null
        ai = AiState.Idle
        review = AiState.Idle
        screen = Screen.HOME
    }

    /** Which gate an AI call is stopped by, or null if it can go ahead. */
    fun gate(): AiState? = when {
        !settings.configured -> AiState.NeedsKey
        !settings.consent -> AiState.NeedsConsent
        else -> null
    }

    fun runAi(entry: Entry) {
        if (Safety.needsSupport(entry.note)) {
            ai = AiState.Support
            return
        }
        gate()?.let { ai = it; return }
        ai = AiState.Loading
        val id = ++runId
        val provider = settings.provider
        val baseUrl = settings.baseUrl
        val key = settings.key
        val model = settings.model
        val system = Prompt.systemFor(settings.deep)
        val user = Prompt.user(entry, Insights.summary(store.all(), System.currentTimeMillis()), settings.about)
        thread {
            val result = try {
                AiClient.chat(provider, baseUrl, key, model, system, user)
            } catch (e: Throwable) {
                AiResult.Failed(AiError.SERVER, e.javaClass.simpleName)
            }
            // A finished report is always worth keeping, even if the person has moved on.
            if (result is AiResult.Ok) store.setReport(entry.time, result.text)
            if (id != runId) return@thread
            when (result) {
                is AiResult.Ok -> {
                    refresh()
                    ai = AiState.Ready(result.text)
                }
                is AiResult.Failed -> ai = AiState.Failed(result.error, result.detail)
            }
        }
    }

    fun runReview(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force) {
            val cached = store.lastReview()
            if (cached != null && now - cached.first < REVIEW_FRESH_MS) {
                review = AiState.Ready(cached.second)
                return
            }
        }
        gate()?.let { review = it; return }
        review = AiState.Loading
        val id = ++runId
        val provider = settings.provider
        val baseUrl = settings.baseUrl
        val key = settings.key
        val model = settings.model
        val user = Prompt.weeklyUser(store.all(), now, settings.about)
        thread {
            val result = try {
                AiClient.chat(provider, baseUrl, key, model, WEEKLY_SYSTEM, user)
            } catch (e: Throwable) {
                AiResult.Failed(AiError.SERVER, e.javaClass.simpleName)
            }
            if (result is AiResult.Ok) store.saveReview(result.text)
            if (id != runId) return@thread
            review = when (result) {
                is AiResult.Ok -> AiState.Ready(result.text)
                is AiResult.Failed -> AiState.Failed(result.error, result.detail)
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

    fun undoLastAnswer() {
        val last = QuestionTree.sequence(answers[Q.FEELING], hour, slipped).lastOrNull { it in answers }
        if (last != null) answers = answers - last
    }

    fun stepBack() {
        val last = QuestionTree.sequence(answers[Q.FEELING], hour, slipped).lastOrNull { it in answers }
        if (last == null) goHome() else answers = answers - last
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            entries = entries,
            hour = hour,
            setup = SetupState(
                dechainerInstalled = Door.isInstalled(context),
                canReachDechainer = Door.hasPermission(context),
                aiReady = settings.configured
            ),
            onUrge = { startInterview(false) },
            onSlip = { startInterview(true) },
            onOpen = { detail = it; screen = Screen.DETAIL },
            onReview = {
                screen = Screen.REVIEW
                runReview(force = false)
            },
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
                undoLastAnswer()
                screen = Screen.INTERVIEW
            }
            NoteScreen(
                slipped = slipped,
                onDone = { finish(it) },
                onBack = {
                    undoLastAnswer()
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

        Screen.REVIEW -> {
            BackHandler { goHome() }
            ReviewScreen(
                state = review,
                providerLabel = settings.provider.label,
                onConsent = { yes ->
                    if (yes) {
                        settings.consent = true
                        runReview(force = true)
                    } else {
                        review = AiState.Idle
                    }
                },
                onRefresh = { runReview(force = true) },
                onSettings = { screen = Screen.SETTINGS },
                onBack = { goHome() }
            )
        }

        Screen.SETTINGS -> {
            val backTo = when {
                current != null -> Screen.PLAN
                review !is AiState.Idle -> Screen.REVIEW
                else -> Screen.HOME
            }
            BackHandler { screen = backTo }
            SettingsScreen(
                settings = settings,
                store = store,
                onDeleteAll = {
                    store.clear()
                    goHome()
                },
                onImported = { refresh() },
                onBack = {
                    screen = backTo
                    // Back from adding a key: try the AI again now.
                    val entry = current
                    if (backTo == Screen.PLAN && entry != null && (ai is AiState.NeedsKey || ai is AiState.Failed)) runAi(entry)
                    if (backTo == Screen.REVIEW && (review is AiState.NeedsKey || review is AiState.Failed)) runReview(force = true)
                    if (backTo == Screen.HOME) refresh()
                }
            )
        }
    }
}
