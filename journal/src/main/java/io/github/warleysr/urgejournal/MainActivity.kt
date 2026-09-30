package io.github.warleysr.urgejournal

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    /** What the launching intent asked for (a notification tap, or Déchaîner's shortcut). The app clears it once handled. */
    var pendingAction by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingAction = intent?.getStringExtra(EXTRA_ACTION)
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
                    App(this)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingAction = intent.getStringExtra(EXTRA_ACTION)
    }

    companion object {
        const val EXTRA_ACTION = "action"
        /** Open the check-in on the last ride. */
        const val ACTION_CHECKIN = "checkin"
        /** Start a ride straight away (used by Déchaîner's shortcut). */
        const val ACTION_RIDE = "ride"
        const val ACTION_NONE = "none"
    }
}

private enum class Screen { HOME, RIDE, AFTER, INTERVIEW, NOTE, PLAN, DETAIL, SETTINGS, REVIEW }

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

/** How long the block sent at the start of a ride lasts: the ten-minute ride plus time to settle. */
private const val RIDE_BLOCK_MINUTES = 30

/** What "still strong" asks for. */
private const val LONGER_BLOCK_MINUTES = 60

private const val FORTNIGHT_MS = 14L * 24 * 60 * 60 * 1000
private const val SIX_HOURS_MS = 6L * 60 * 60 * 1000

private fun hourOf(time: Long): Int = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).hour

@Composable
private fun App(activity: MainActivity) {
    val context = LocalContext.current
    val store = remember { JournalStore(context) }
    val settings = remember { AiSettings(context) }
    val reminders = remember { ReminderSettings(context) }
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
    // A ride is the ten minutes at the peak of an urge; the check-in after it feeds the coach.
    var quick by remember { mutableStateOf(false) }
    var rideStart by remember { mutableLongStateOf(0L) }
    var rideClock by remember { mutableLongStateOf(0L) }
    var blockMinutes by remember { mutableIntStateOf(RIDE_BLOCK_MINUTES) }
    var doorResult by remember { mutableStateOf<Door.Result?>(null) }
    var pendingAfter by remember { mutableStateOf<After?>(null) }
    var pendingTried by remember { mutableStateOf(emptyList<Step>()) }
    var plans by remember { mutableStateOf(store.plans()) }
    var nudgeMinute by remember { mutableIntStateOf(reminders.nudgeMinute) }
    var cardsTick by remember { mutableIntStateOf(0) }

    // Asking for the notification permission is done from a tap that needs it, never at the peak of an urge.
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterPermission?.invoke()
        afterPermission = null
    }
    fun withNotifications(then: () -> Unit) {
        if (!Notifier.needsPermission(context)) {
            then()
        } else {
            afterPermission = then
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun refresh() {
        entries = store.all()
    }

    fun goHome() {
        runId++
        hour = LocalTime.now().hour
        refresh()
        quick = false
        rideStart = 0L
        pendingAfter = null
        pendingTried = emptyList()
        cardsTick++
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
        if (!isSlip) quick = false
        hour = LocalTime.now().hour
        answers = emptyMap()
        screen = Screen.INTERVIEW
    }

    /** The three-question version, asked after a ride once there is a clear head to answer with. */
    fun startDetails() {
        slipped = false
        quick = true
        answers = emptyMap()
        screen = Screen.INTERVIEW
    }

    /** The ride is over, one way or another: forget it and its reminder. */
    fun clearRide() {
        store.clearPendingRide()
        Notifier.cancelCheckIn(context)
        rideStart = 0L
    }

    /** Asks Déchaîner to pause the apps chosen for its panic button. */
    fun pauseApps(minutes: Int) {
        blockMinutes = minutes
        doorResult = Door.send(context, DoorAction(DoorAction.IMPULSE_BLOCK, minutes))
    }

    fun startRide() {
        val now = System.currentTimeMillis()
        pauseApps(RIDE_BLOCK_MINUTES)
        slipped = false
        quick = true
        hour = LocalTime.now().hour
        answers = emptyMap()
        pendingAfter = null
        pendingTried = emptyList()
        rideStart = now
        rideClock = now
        // The urge is on record from the first second, so leaving without a check-in loses nothing.
        store.put(Entry(now, false, emptyMap(), null))
        refresh()
        store.setPendingRide(now)
        Notifier.scheduleCheckIn(context, now + Notifier.CHECKIN_DELAY_MS)
        runId++
        screen = Screen.RIDE
    }

    /** From the Home card or the notification: how did the last ride end? */
    fun startCheckIn() {
        val started = store.pendingRide()
        if (started == 0L) return
        rideStart = started
        slipped = false
        quick = true
        hour = hourOf(started)
        answers = emptyMap()
        pendingAfter = null
        pendingTried = emptyList()
        screen = Screen.AFTER
    }

    fun finish(note: String) {
        val time = if (rideStart != 0L) rideStart else System.currentTimeMillis()
        val entry = Entry(
            time, slipped, answers,
            if (slipped) null else pendingAfter?.outcome,
            note.trim(), null, pendingTried,
            if (slipped) null else pendingAfter
        )
        store.put(entry)
        clearRide()
        refresh()
        current = entry
        ai = AiState.Idle
        screen = Screen.PLAN
        runAi(entry)
    }

    fun answer(q: Q, opt: Opt) {
        val next = answers + (q to opt)
        answers = next
        if (QuestionTree.next(next, hour, slipped, quick) == null) screen = Screen.NOTE
    }

    fun undoLastAnswer() {
        val last = QuestionTree.sequence(answers[Q.FEELING], hour, slipped, quick).lastOrNull { it in answers }
        if (last != null) answers = answers - last
    }

    fun stepBack() {
        val last = QuestionTree.sequence(answers[Q.FEELING], hour, slipped, quick).lastOrNull { it in answers }
        when {
            last != null -> answers = answers - last
            quick && rideStart != 0L && !slipped -> screen = Screen.AFTER
            else -> goHome()
        }
    }

    // What a notification tap or Déchaîner's shortcut asked for.
    val action = activity.pendingAction
    LaunchedEffect(action) {
        when (action) {
            MainActivity.ACTION_RIDE -> startRide()
            MainActivity.ACTION_CHECKIN -> startCheckIn()
        }
        if (action != null) activity.pendingAction = null
    }

    when (screen) {
        Screen.HOME -> {
            val now = System.currentTimeMillis()
            val cards = remember(entries, cardsTick, nudgeMinute) {
                HomeCards(
                    pendingRideAt = store.pendingRide().takeIf { it != 0L && now - it < SIX_HOURS_MS } ?: 0L,
                    hot = if (nudgeMinute < 0 && now - store.dismissedAt("hot") > FORTNIGHT_MS) Insights.hotWindow(entries, now) else null,
                    heavier = now - store.dismissedAt("heavier") > FORTNIGHT_MS && Insights.heavier(entries, now),
                    nudgeMinute = nudgeMinute,
                    askDay = LocalTime.now().hour >= 17 && LocalDate.now() !in store.days(),
                    planDays = Insights.planDays(store.days(), LocalDate.now())
                )
            }
            HomeScreen(
                entries = entries,
                hour = hour,
                setup = SetupState(
                    dechainerInstalled = Door.isInstalled(context),
                    canReachDechainer = Door.hasPermission(context),
                    aiReady = settings.configured
                ),
                cards = cards,
                onRide = { startRide() },
                onUrge = { startInterview(false) },
                onSlip = { startInterview(true) },
                onOpen = { detail = it; screen = Screen.DETAIL },
                onReview = {
                    screen = Screen.REVIEW
                    runReview(force = false)
                },
                onSettings = { screen = Screen.SETTINGS },
                onCheckIn = { startCheckIn() },
                onDropRide = {
                    clearRide()
                    cardsTick++
                },
                onNudge = { minute ->
                    withNotifications {
                        reminders.nudgeMinute = minute
                        nudgeMinute = minute
                        Notifier.rearmNudge(context)
                    }
                },
                onDismissHot = {
                    store.dismiss("hot")
                    cardsTick++
                },
                onDismissHeavy = {
                    store.dismiss("heavier")
                    cardsTick++
                },
                onDay = { result ->
                    store.setDay(LocalDate.now(), result)
                    cardsTick++
                },
                onShare = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, Insights.shareText(entries, System.currentTimeMillis()))
                    }
                    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            )
        }

        Screen.RIDE -> {
            BackHandler { goHome() }
            RideScreen(
                startedAt = rideClock,
                blockMinutes = blockMinutes,
                door = doorResult,
                step = Coach.rideStep(entries),
                myPlan = MyPlan.forRide(plans, hour),
                onDone = { screen = Screen.AFTER },
                onLonger = { pauseApps(LONGER_BLOCK_MINUTES) },
                onLeave = { goHome() }
            )
        }

        Screen.AFTER -> {
            BackHandler { goHome() }
            AfterScreen(
                after = pendingAfter,
                tried = pendingTried,
                onAfter = { pendingAfter = it },
                onToggle = { step ->
                    pendingTried = if (step in pendingTried) pendingTried - step else pendingTried + step
                },
                onSave = {
                    pendingAfter?.let { a ->
                        store.put(Entry(rideStart, false, emptyMap(), a.outcome, "", null, pendingTried, a))
                        clearRide()
                        goHome()
                    }
                },
                onDetails = { startDetails() },
                onAgain = {
                    val now = System.currentTimeMillis()
                    rideClock = now
                    pauseApps(RIDE_BLOCK_MINUTES)
                    screen = Screen.RIDE
                },
                onGaveIn = { startInterview(true) },
                onBack = { goHome() }
            )
        }

        Screen.INTERVIEW -> {
            BackHandler { stepBack() }
            val q = QuestionTree.next(answers, hour, slipped, quick)
            if (q != null) {
                InterviewScreen(
                    q = q,
                    total = QuestionTree.sequence(answers[Q.FEELING], hour, slipped, quick).size,
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
                    history = entries,
                    plans = plans,
                    ai = ai,
                    providerLabel = settings.provider.label,
                    onSavePlan = { text ->
                        store.addPlan(
                            MyPlan(
                                System.currentTimeMillis(), text.trim(),
                                entry.answers[Q.FEELING], isLate(hourOf(entry.time))
                            )
                        )
                        plans = store.plans()
                    },
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
                reminders = reminders,
                plans = plans,
                onPlansChanged = { plans = store.plans() },
                onNeedNotifications = { then -> withNotifications(then) },
                onDeleteAll = {
                    store.clear()
                    Notifier.cancelCheckIn(context)
                    plans = emptyList()
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
