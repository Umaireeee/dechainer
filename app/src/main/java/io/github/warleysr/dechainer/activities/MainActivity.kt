package io.github.warleysr.dechainer.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.LockClock
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.focus.Pomodoro
import io.github.warleysr.dechainer.lock.LockEngine
import io.github.warleysr.dechainer.lock.LockMode
import io.github.warleysr.dechainer.screens.apps.AppsScreen
import io.github.warleysr.dechainer.screens.common.RepeatWhileVisible
import io.github.warleysr.dechainer.screens.common.ScreenInfoButton
import io.github.warleysr.dechainer.screens.focus.FocusLogScreen
import io.github.warleysr.dechainer.screens.focus.FocusScreen
import io.github.warleysr.dechainer.screens.home.HomeScreen
import io.github.warleysr.dechainer.screens.home.MenuSheet
import io.github.warleysr.dechainer.screens.setup.SetupDeviceOwnerPrivileges
import io.github.warleysr.dechainer.screens.setup.SetupRecovery
import io.github.warleysr.dechainer.screens.tabs.*
import io.github.warleysr.dechainer.screens.urge.UrgeFlowHost
import io.github.warleysr.dechainer.screens.urge.UrgeSettingsScreen
import io.github.warleysr.dechainer.security.AppLock
import io.github.warleysr.dechainer.security.SecurityManager
import io.github.warleysr.dechainer.ui.theme.DechainerTheme
import io.github.warleysr.dechainer.ui.theme.Motion
import io.github.warleysr.dechainer.urge.DeepDiveScheduler
import io.github.warleysr.dechainer.urge.UrgeSource
import io.github.warleysr.dechainer.viewmodels.DeviceOwnerViewModel
import io.github.warleysr.dechainer.viewmodels.NavigationViewModel
import io.github.warleysr.dechainer.viewmodels.Route
import io.github.warleysr.dechainer.viewmodels.UrgeViewModel
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    /** The urge flow's state, shared with every screen that can start it (Home, Focus, the tile). */
    private val urgeVm: UrgeViewModel by viewModels()

    /**
     * Pins the phone to Déchaîner while any brick runs (an urge lock, a focus block, a punishment
     * day), and releases it when the last one ends. [ownerApps] are the apps the running bricks all
     * let through ([io.github.warleysr.dechainer.lock.BrickStatus.ownerApps]). Only as device owner: without it, Android would show its
     * own "pin this app?" prompt instead. If the app crashes, Android drops the pin by itself:
     * the phone is never trapped, and suspension keeps blocking underneath.
     */
    private fun syncBrickPin(brick: Boolean, ownerApps: Set<String> = emptySet()) {
        try {
            val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java)
            if (!dpm.isDeviceOwnerApp(packageName)) return
            val am = getSystemService(android.app.ActivityManager::class.java)
            val pinned = am.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE
            if (brick && !pinned) {
                // Setting up the pin can't stop the pin itself: a refused setting is logged and
                // the phone is pinned anyway.
                try {
                    io.github.warleysr.dechainer.data.DeviceOwnerRepository.prepareBrick(this, ownerApps)
                } catch (e: Exception) {
                    timber.log.Timber.w(e, "Brick setup partly refused")
                }
                startLockTask()
            } else if (!brick && pinned) {
                stopLockTask()
            }
        } catch (e: Exception) {
            timber.log.Timber.w(e, "Brick pin not changed")
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back to the app is a wake-up: the lock is recomputed from stored data, so a block
        // that outlived a lost alarm ends now (blueprint 5.4, R1).
        LockEngine.requestSync(this)
        // Coming back mid-brick (say, after answering a call): pin again.
        val status = LockEngine.refreshStatus(this)
        if (status != null) syncBrickPin(true, status.ownerApps)
        else if (Pomodoro.brickActive()) syncBrickPin(true, Pomodoro.allowedApps.value)
        // An urge that was left half-written opens straight back into its step.
        urgeVm.resumeIfAny()
        // A weekly report that came due while the phone was off is queued again (blueprint 6.5).
        thread { io.github.warleysr.dechainer.report.ReportScheduler.ensureQueued(applicationContext) }
    }

    /**
     * The Quick Settings tile and the icon shortcut start the ongoing path with no question first
     * (D2). The extra is taken off once handled, and an intent replayed from recents is ignored, so
     * neither can start a second lock by accident.
     */
    private fun handleUrgeIntent(intent: Intent?) {
        val name = intent?.getStringExtra(EXTRA_URGE_SOURCE) ?: return
        intent.removeExtra(EXTRA_URGE_SOURCE)
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val source = UrgeSource.entries.firstOrNull { it.name == name } ?: return
        urgeVm.startOngoing(source)
    }

    private val openTodayRequest = mutableStateOf(false)

    /** A notification or an unlock in the evening window brings the checklist forward. */
    private fun handleTodayIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(io.github.warleysr.dechainer.day.DayEngine.EXTRA_OPEN_TODAY, false) != true) return
        intent.removeExtra(io.github.warleysr.dechainer.day.DayEngine.EXTRA_OPEN_TODAY)
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        openTodayRequest.value = true
    }

    /** The weekly report notification opens that report. -1 when none was asked for. */
    private val openReportRequest = mutableLongStateOf(-1L)

    private fun handleReportIntent(intent: Intent?) {
        val id = intent?.getLongExtra(io.github.warleysr.dechainer.report.ReportNotifier.EXTRA_OPEN_REPORT, -1L) ?: -1L
        if (id < 0) return
        intent!!.removeExtra(io.github.warleysr.dechainer.report.ReportNotifier.EXTRA_OPEN_REPORT)
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        openReportRequest.longValue = id
    }

    override fun onStart() {
        super.onStart()
        // The app lock asks again after the app has been out of sight for a while.
        AppLock.onForeground()
    }

    override fun onStop() {
        super.onStop()
        AppLock.onBackground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUrgeIntent(intent)
        handleTodayIntent(intent)
        handleReportIntent(intent)
    }


    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecurityManager.consumeDebugAutoStartSession(this)
        SecurityManager.consumeDebugRestoreUnknownSourcesRestriction(this)
        enableEdgeToEdge(
            // Always night: light status and navigation icons, whatever the system theme is.
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        Pomodoro.ensureLoaded(this)
        // The first frame already knows whether something holds the phone.
        LockEngine.refreshStatus(this)
        if (savedInstanceState == null) { handleUrgeIntent(intent); handleTodayIntent(intent); handleReportIntent(intent) }
        // A deep dive that could not be made earlier gets another try (a job already waiting is left alone).
        // Entries left half-way past the resume window are settled first (a note never answered gets its deep dive from the note alone).
        val freshStart = savedInstanceState == null
        thread {
            if (freshStart) io.github.warleysr.dechainer.urge.UrgeFlow(applicationContext).settleStale()
            DeepDiveScheduler.enqueueIfPending(applicationContext)
        }
        setContent {
            val focusState by Pomodoro.state.collectAsState()
            val lockStatus by LockEngine.status.collectAsState()
            val urge by urgeVm.state.collectAsState()
            // A punishment day (or an urge lock the flow has not picked up yet) shows Home with its end
            // time; a focus block shows the Focus page. When bricks overlap, the one that ends last is named.
            val lockedHome = lockStatus?.takeIf { it.primary != LockMode.FOCUS_BLOCK }
            val brick = focusState.inBlock || lockStatus != null
            val focusBrick = brick && lockedHome == null
            val pinApps = lockStatus?.ownerApps ?: Pomodoro.allowedApps.value
            LaunchedEffect(brick, pinApps) { syncBrickPin(brick, pinApps) }
            // The status ends with the clock: when the end time has passed, ask for the plan again.
            RepeatWhileVisible(1000) {
                val ends = LockEngine.status.value?.endsAt ?: return@RepeatWhileVisible
                if (TrustedClock.now(this@MainActivity) >= ends) {
                    LockEngine.refreshStatus(this@MainActivity)
                    LockEngine.requestSync(this@MainActivity)
                }
            }
            // Every urge lock gets its counted entry and its breathing, also one found with none.
            LaunchedEffect(lockStatus?.primary, urge.entry == null) {
                if (lockStatus?.primary == LockMode.URGE_LOCK && urge.entry == null) urgeVm.adoptRunningLock()
            }
            DechainerTheme {
                val viewModel: DeviceOwnerViewModel = viewModel()
                viewModel.addShizukuListener()
                val navViewModel: NavigationViewModel = viewModel()

                // A brick shows its own screen, whatever was open before it started.
                val route = if (lockedHome != null && navViewModel.current() !in Route.OPEN_WHILE_LOCKED) Route.HOME else navViewModel.current()
                val recoverySet = SecurityManager.isRecoveryCodeSet(this@MainActivity)
                // The rules are confirmed once, at the end of setup; enforcement starts then (D19).
                var rulesConfirmed by remember { mutableStateOf(io.github.warleysr.dechainer.day.DayEngine.rulesConfirmed(this@MainActivity)) }

                BackHandler(enabled = route != Route.HOME) {
                    navViewModel.goBack()
                }
                // In a brick or in the urge flow, Back does nothing: on the main screen it would close
                // the app, and closing the pinned screen ends the pin. Registered last, so it wins.
                BackHandler(enabled = brick || urge.active) { }
                LaunchedEffect(brick) {
                    if (brick) navViewModel.navigateTo(Route.HOME)
                }
                LaunchedEffect(openReportRequest.longValue) {
                    if (openReportRequest.longValue >= 0 && !focusBrick && !urge.active) navViewModel.navigateTo(Route.REPORTS)
                }
                LaunchedEffect(route) { if (route != Route.REPORTS) openReportRequest.longValue = -1L }
                LaunchedEffect(openTodayRequest.value) {
                    if (openTodayRequest.value) {
                        openTodayRequest.value = false
                        if (!focusBrick && !urge.active) navViewModel.navigateTo(Route.TODAY)
                    }
                }

                // An urge lock or a punishment day ends any open recovery session, so the code can't
                // be used until the lock runs out.
                LaunchedEffect(lockedHome != null) {
                    if (lockedHome != null) SecurityManager.endSession()
                }

                var currentTime by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
                // Only while a timed session shows its countdown, and only while on screen.
                // Collected so the bar redraws when a session opens or ends (the timer is not Compose state).
                val sessionRevision by SecurityManager.sessionChanges.collectAsState()
                val sessionActive = remember(sessionRevision) { SecurityManager.isSessionActive() }
                if (sessionActive) {
                    RepeatWhileVisible(1000, key = sessionActive) { currentTime = android.os.SystemClock.elapsedRealtime() }
                }

                val context = LocalContext.current
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) {}
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
                    // The Pomodoro rings through a notification, so it needs this permission.

                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                var menuOpen by rememberSaveable { mutableStateOf(false) }
                // The app lock (owner request): everything but Home, the urge flow and a running brick waits behind it.
                val appLocked = AppLock.isLocked(this@MainActivity)
                var unlockRequested by rememberSaveable { mutableStateOf(false) }

                // Home and the urge flow have no top bar: the clock, or the breathing, is all there is.
                val showTopBar = recoverySet && !urge.active && (focusBrick || (route != Route.HOME && !appLocked))

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        if (showTopBar) TopAppBar(
                            // The screen you're on, so you always know where you are.
                            title = { Text(stringResource(if (focusBrick) R.string.focus_tab else route.title)) },
                            // Same tone as the page, so the bar reads as part of it, not a band on top.
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background
                            ),
                            navigationIcon = {
                                if (!focusBrick) {
                                    IconButton(onClick = { navViewModel.goBack() }) {
                                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                                    }
                                }
                            },
                            actions = {
                                // Nothing up here during a block: no info, no sign-out.
                                if (!brick) {
                                    ScreenInfoButton(route)
                                    if (SecurityManager.isSessionActive()) {
                                        val remaining = SecurityManager.sessionRemainingMs(currentTime)
                                        val minutes = (remaining / 1000) / 60
                                        val seconds = (remaining / 1000) % 60

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Outlined.LockClock, null, modifier = Modifier.padding(end = 4.dp))
                                            Text(
                                                text = "%02d:%02d".format(minutes, seconds),
                                                style = MaterialTheme.typography.labelLarge
                                            )
                                            IconButton(onClick = { SecurityManager.endSession() }) {
                                                Icon(Icons.Outlined.Logout, stringResource(R.string.end_session))
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    when {
                        !recoverySet -> SetupRecovery(innerPadding)

                        !rulesConfirmed && !urge.active && lockedHome == null && !brick ->
                            io.github.warleysr.dechainer.screens.setup.RulesOnboarding(innerPadding) { rulesConfirmed = true }

                        // The urge flow owns the screen: the choice, the breathing, the writing, the questions, the deep dive.
                        urge.active -> UrgeFlowHost(urgeVm)

                        // The focus block: only this page. It has nothing to protect (no settings, no way
                        // out), so it doesn't wait behind the unlock screen either.
                        focusBrick -> Box(modifier = Modifier.padding(innerPadding)) {
                            FocusScreen(onOpenLog = { })
                        }

                        // The owner's own PIN or pattern. Asked for the menu and every other screen; never for
                        // Home itself or the Urge button, so an urge can always be started at once.
                        appLocked && (route != Route.HOME || unlockRequested) ->
                            io.github.warleysr.dechainer.screens.AppLockScreen(
                                onUnlocked = { if (unlockRequested) menuOpen = true; unlockRequested = false },
                                onCancel = {
                                    unlockRequested = false
                                    if (route != Route.HOME) navViewModel.navigateTo(Route.HOME)
                                },
                                modifier = Modifier.padding(innerPadding)
                            )

                        route == Route.HOME -> {
                            HomeScreen(
                                lock = lockedHome,
                                menuEnabled = Route.menuFor(brick).isNotEmpty(),
                                onUrge = { urgeVm.openChoice(UrgeSource.HOME) },
                                onMenu = { if (appLocked) unlockRequested = true else menuOpen = true },
                                modifier = Modifier.padding(innerPadding)
                            )
                            if (menuOpen && !appLocked) {
                                MenuSheet(
                                    routes = Route.menuFor(brick),
                                    onUrge = { menuOpen = false; urgeVm.openChoice(UrgeSource.HOME) },
                                    onRoute = { menuOpen = false; navViewModel.navigateTo(it) },
                                    onDismiss = { menuOpen = false }
                                )
                            }
                        }

                        else -> Box(modifier = Modifier.padding(innerPadding)) {
                            // A soft cross-fade between screens: it answers the tap without
                            // pulling attention.
                            AnimatedContent(
                                targetState = route,
                                transitionSpec = {
                                    fadeIn(tween(Motion.SCREEN_MS, delayMillis = 60)) togetherWith fadeOut(tween(Motion.SCREEN_OUT_MS))
                                },
                                label = "screen"
                            ) { screen ->
                                when (screen) {
                                    Route.FOCUS -> FocusScreen(onOpenLog = { navViewModel.navigateTo(Route.FOCUS_LOG) })
                                    Route.FOCUS_LOG -> FocusLogScreen()
                                    Route.TODAY -> io.github.warleysr.dechainer.screens.TodayScreen()
                                    Route.REPORTS -> io.github.warleysr.dechainer.screens.ReportsScreen(
                                        openReportId = openReportRequest.longValue.takeIf { it >= 0 },
                                        onOpenData = { navViewModel.navigateTo(Route.DATA) },
                                        onOpenJournal = { navViewModel.navigateTo(Route.JOURNAL) }
                                    )
                                    Route.JOURNAL -> io.github.warleysr.dechainer.screens.JournalScreen(onOpen = { navViewModel.openEntry(it) })
                                    Route.ENTRY -> io.github.warleysr.dechainer.screens.EntryScreen(navViewModel.entryId)
                                    Route.DATA -> io.github.warleysr.dechainer.screens.DataScreen(onOpenEntry = { navViewModel.openEntry(it) })
                                    Route.APPS -> AppsScreen()
                                    Route.SCHEDULES -> SchedulesScreen()
                                    Route.SCHEDULE_EDITOR -> ScheduleEditorScreen()
                                    Route.SETTINGS -> ConfigTab()
                                    Route.RESTRICTIONS -> RestrictionsTab()
                                    Route.SETUP_DEVICE_OWNER -> SetupDeviceOwnerPrivileges()
                                    Route.URGE_SETTINGS -> UrgeSettingsScreen()
                                    Route.APP_LOCK -> io.github.warleysr.dechainer.screens.AppLockSettingsScreen()
                                    Route.HOME -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SecurityManager.endSession()
    }

    companion object {
        /**
         * Set by the Quick Settings tile and the icon shortcut: the name of the [UrgeSource] that
         * started the ongoing path. See [handleUrgeIntent].
         */
        const val EXTRA_URGE_SOURCE = "urge_source"
    }
}
