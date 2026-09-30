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
        // Only a fresh launch carries a request; a recreated screen must not repeat it.
        applyPrivacy()
        pendingAction = if (savedInstanceState == null) intent?.getStringExtra(EXTRA_ACTION) else null
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

    /** Hides the app in recents and blocks screenshots, unless the person turned that off in Settings. */
    fun applyPrivacy() {
        val flag = android.view.WindowManager.LayoutParams.FLAG_SECURE
        if (PrivacySettings(this).hideInRecents) window.setFlags(flag, flag) else window.clearFlags(flag)
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

private enum class Screen { HOME, STARTING, RIDE, AFTER, INTERVIEW, NOTE, PLAN, DETAIL, LOG, SETTINGS, REVIEW }

/** Where an AI reply is: for one entry's deep dive, or for the weekly review. */
sealed interface AiState {
    data object Idle : AiState
    data object NeedsKey : AiState
    data object NeedsConsent : AiState
    data object Loading : AiState
    /** The reply is arriving: [text] is everything written so far. */
    data class Streaming(val text: String) : AiState
    data class Ready(val text: String) : AiState
    data class Failed(val error: AiError, val detail: String = "") : AiState
}

/** A cached weekly review counts as fresh for this long. */
private const val REVIEW_FRESH_MS = 12L * 60 * 60 * 1000

/** How long the block sent at the start of a ride lasts: the ten-minute ride plus time to settle. */
private const val RIDE_BLOCK_MINUTES = 30

/** What "still strong" asks for. */
private const val LONGER_BLOCK_MINUTES = 60

/** The ride lock: every other app is out of reach for the ten minutes of the ride. */
private const val RIDE_LOCK_MINUTES = 10

/** "Still strong" locks everything for as long as Déchaîner allows a ride lock to run. */
private const val LONGER_LOCK_MINUTES = 30

/** How long to wait before asking Déchaîner what is really in force: it has to receive the request first. */
private const val STATUS_DELAY_MS = 1500L

/** The screen is refreshed with the streaming reply at most this often (milliseconds). */
private const val STREAM_UI_MS = 80L

private const val FORTNIGHT_MS = 14L * 24 * 60 * 60 * 1000
private const val SIX_HOURS_MS = Notifier.PENDING_MAX_AGE_MS

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
    var lockMinutes by remember { mutableIntStateOf(RIDE_LOCK_MINUTES) }
    var doorResult by remember { mutableStateOf<Door.Result?>(null) }
    var doorStatus by remember { mutableStateOf<DoorStatus?>(null) }
    var statusChecked by remember { mutableStateOf(false) }
    var startingAt by remember { mutableLongStateOf(0L) }
    val privacy = remember { PrivacySettings(context) }
    var pendingAfter by remember { mutableStateOf<After?>(null) }
    var pendingTried by remember { mutableStateOf(emptyList<Step>()) }
    var plans by remember { mutableStateOf(store.plans()) }
    var nudgeMinute by remember { mutableIntStateOf(reminders.nudgeMinute) }
    var cardsTick by remember { mutableIntStateOf(0) }
    // Where an opened entry goes back to: Home's short list, or the full log.
    var detailBack by remember { mutableStateOf(Screen.HOME) }

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
        // Words that sound like a crisis don't stop the coach: the plan screen shows the support
        // card at once, and the coach, told so, answers with care instead of tips (see Prompt).
        gate()?.let { ai = it; return }
        ai = AiState.Loading
        val id = ++runId
        val provider = settings.provider
        val baseUrl = settings.baseUrl
        val key = settings.key
        val model = settings.model
        val system = Prompt.systemFor(settings.deep)
        val about = settings.about
        thread {
            val result = try {
                // Built here, off the main thread: reading the whole journal can take a moment.
                val history = store.all()
                val user = Prompt.user(
                    entry,
                    Insights.summary(history, System.currentTimeMillis()),
                    about,
                    Prompt.extras(history, entry, store.plans().map { it.text })
                )
                var lastPush = 0L
                AiClient.chatStream(provider, baseUrl, key, model, system, user) { text ->
                    // Show what has arrived, at most a few times a second, and only if this is still the screen asking.
                    val nowMs = System.currentTimeMillis()
                    if (id == runId && nowMs - lastPush >= STREAM_UI_MS) {
                        lastPush = nowMs
                        ai = AiState.Streaming(text)
                    }
                }
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
        val about = settings.about
        thread {
            val result = try {
                var lastPush = 0L
                // The last review, if it's from an earlier week, so this one can follow up on it.
                val previous = store.lastReview()?.let { (at, text) ->
                    val days = ((now - at) / (24L * 60 * 60 * 1000)).toInt()
                    if (days >= 3) Prompt.PreviousReview(days, text) else null
                }
                AiClient.chatStream(provider, baseUrl, key, model, WEEKLY_SYSTEM, Prompt.weeklyUser(store.all(), now, about, previous = previous)) { text ->
                    val nowMs = System.currentTimeMillis()
                    if (id == runId && nowMs - lastPush >= STREAM_UI_MS) {
                        lastPush = nowMs
                        review = AiState.Streaming(text)
                    }
                }
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

    /** Asks Déchaîner for the total ride lock and for the longer pause of the apps chosen for the panic button. */
    fun lockDown(pauseMinutes: Int, totalLockMinutes: Int) {
        blockMinutes = pauseMinutes
        lockMinutes = totalLockMinutes
        val locked = Door.send(context, DoorAction(DoorAction.RIDE_LOCK, totalLockMinutes))
        val paused = Door.send(context, DoorAction(DoorAction.IMPULSE_BLOCK, pauseMinutes))
        doorResult = if (locked == Door.Result.SENT) paused else locked
        // What was asked for proves nothing on its own: ask Déchaîner what is really in force.
        doorStatus = null
        statusChecked = false
        if (locked == Door.Result.SENT) {
            thread {
                Thread.sleep(STATUS_DELAY_MS)
                doorStatus = Door.status(context)
                statusChecked = true
            }
        } else {
            statusChecked = true
        }
    }

    /** A ride that is already running: show it again instead of starting another. */
    fun resumeRide() {
        val started = store.pendingRide()
        if (started == 0L) return
        rideStart = started
        rideClock = started
        slipped = false
        quick = true
        hour = hourOf(started)
        answers = emptyMap()
        pendingAfter = null
        pendingTried = emptyList()
        screen = Screen.RIDE
    }

    fun startRide() {
        val now = System.currentTimeMillis()
        lockDown(RIDE_BLOCK_MINUTES, RIDE_LOCK_MINUTES)
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

    // Once per launch: a ride nobody checked in on for hours is dropped, with its empty note.
    LaunchedEffect(Unit) {
        store.dropStaleRide(System.currentTimeMillis(), SIX_HOURS_MS)
        Share.cleanup(context)
        refresh()
    }

    fun leaveDetail() {
        if (detailBack == Screen.LOG) {
            refresh()
            detail = null
            screen = Screen.LOG
        } else {
            goHome()
        }
    }

    // What a notification tap or the tile / icon shortcut asked for.
    val action = activity.pendingAction
    LaunchedEffect(action) {
        when (action) {
            // Asked for from outside the app: never starts at once. A countdown gives a chance to cancel,
            // and asking again while a ride runs or is about to start changes nothing.
            MainActivity.ACTION_RIDE -> when (
                RideRequest.decide(store.pendingRide(), System.currentTimeMillis(), RIDE_SECONDS, screen == Screen.STARTING)
            ) {
                RideRequest.Decision.COUNTDOWN -> {
                    startingAt = System.currentTimeMillis()
                    screen = Screen.STARTING
                }
                RideRequest.Decision.RESUME -> resumeRide()
                RideRequest.Decision.IGNORE -> {}
            }
            MainActivity.ACTION_CHECKIN -> startCheckIn()
        }
        if (action != null) {
            activity.pendingAction = null
            activity.intent?.removeExtra(MainActivity.EXTRA_ACTION)
        }
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
                onOpen = { detail = it; detailBack = Screen.HOME; screen = Screen.DETAIL },
                onLog = { screen = Screen.LOG },
                onDay = { result ->
                    store.setDay(LocalDate.now(), result)
                    cardsTick++
                },
                onFocus = { minutes -> Door.send(context, DoorAction(DoorAction.FOCUS_BLOCK, minutes)) },
                onReview = {
                    screen = Screen.REVIEW
                    runReview(force = false)
                },
                onSettings = { screen = Screen.SETTINGS },
                onCheckIn = { startCheckIn() },
                onDropRide = {
                    // Skipping a ride drops its empty note too, so an accidental tap leaves nothing behind.
                    val started = store.pendingRide()
                    clearRide()
                    if (started != 0L) store.deleteStub(started)
                    refresh()
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
                onShare = { Share.text(context, Insights.shareText(entries, System.currentTimeMillis())) }
            )
        }

        Screen.STARTING -> {
            BackHandler { goHome() }
            StartingScreen(startedAt = startingAt, onStart = { startRide() }, onCancel = { goHome() })
        }

        Screen.RIDE -> {
            BackHandler { goHome() }
            RideScreen(
                startedAt = rideClock,
                door = doorResult,
                status = doorStatus,
                statusChecked = statusChecked,
                step = Coach.rideStep(entries),
                myPlan = MyPlan.forRide(plans, hour),
                onDone = { screen = Screen.AFTER },
                onLonger = {
                    lockDown(LONGER_BLOCK_MINUTES, LONGER_LOCK_MINUTES)
                    Notifier.scheduleCheckIn(context, System.currentTimeMillis() + Notifier.CHECKIN_DELAY_MS)
                },
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
                    lockDown(RIDE_BLOCK_MINUTES, RIDE_LOCK_MINUTES)
                    Notifier.scheduleCheckIn(context, now + Notifier.CHECKIN_DELAY_MS)
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
            BackHandler { leaveDetail() }
            detail?.let { picked ->
                // Re-read it so a report or outcome saved since is shown.
                val entry = entries.firstOrNull { it.time == picked.time } ?: picked
                DetailScreen(
                    entry = entry,
                    onOutcome = { outcome ->
                        store.setOutcome(entry.time, outcome)
                        leaveDetail()
                    },
                    onBack = { leaveDetail() }
                )
            }
        }

        Screen.LOG -> {
            BackHandler { goHome() }
            LogScreen(
                entries = entries,
                onOpen = { detail = it; detailBack = Screen.LOG; screen = Screen.DETAIL },
                onExport = { Share.file(context, "urge-journal.csv", "text/csv", CsvExport.csv(entries)) },
                onBack = { goHome() }
            )
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
                privacy = privacy,
                onPrivacyChanged = { activity.applyPrivacy() },
                plans = plans,
                onPlansChanged = { plans = store.plans() },
                onNeedNotifications = { then -> withNotifications(then) },
                onDeleteAll = {
                    store.clear()
                    Notifier.cancelCheckIn(context)
                    cardsTick++
                    plans = emptyList()
                    goHome()
                },
                onImported = { refresh(); plans = store.plans() },
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
