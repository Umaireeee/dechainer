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
import io.github.warleysr.dechainer.screens.common.EntryGate
import io.github.warleysr.dechainer.screens.common.PrivateArea
import io.github.warleysr.dechainer.screens.common.RepeatWhileVisible
import io.github.warleysr.dechainer.security.EntryLock
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
import io.github.warleysr.dechainer.security.SecurityManager
import io.github.warleysr.dechainer.ui.theme.DechainerTheme
import io.github.warleysr.dechainer.ui.theme.Motion
import io.github.warleysr.dechainer.urge.UrgeSource
import io.github.warleysr.dechainer.viewmodels.DeviceOwnerViewModel
import io.github.warleysr.dechainer.viewmodels.NavigationViewModel
import io.github.warleysr.dechainer.viewmodels.Route
import io.github.warleysr.dechainer.viewmodels.UrgeViewModel

class MainActivity : ComponentActivity() {
    /** The urge flow's state, shared with every screen that can start it (Home, Focus, the tile). */
    private val urgeVm: UrgeViewModel by viewModels()

    /**
     * Pins the phone to Déchaîner while any brick runs (an urge lock or a focus
     * block), and releases it when the last one ends. [ownerApps] are the apps the running bricks all
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

    /**
     * The lock at the front door (see [EntryLock]). False on every cold start, so opening the app
     * always asks first. Kept in the activity, not in Compose state, so a rotation does not ask again.
     */
    private var entryUnlocked by mutableStateOf(false)
    private var leftAt = 0L

    /** Whether the locked screen covers the app right now (a brick does not lift this for the private steps). */
    private fun lockScreenCovers(): Boolean =
        SecurityManager.isEntryLockEnabled(this) && SecurityManager.hasRecoveryCode(this) && !entryUnlocked

    override fun onStop() {
        super.onStop()
        leftAt = android.os.SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        // Back after more than a minute away: ask again. A short trip (a call, the shade) does not.
        if (EntryLock.shouldRelock(leftAt, android.os.SystemClock.elapsedRealtime())) {
            entryUnlocked = false
            SecurityManager.closePrivate()
        }
        leftAt = 0L
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
        // Not while the locked screen covers the app: the writing is private and waits for the pattern.
        if (!lockScreenCovers()) urgeVm.resumeIfAny()
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
        urgeVm.startUrge(source)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUrgeIntent(intent)
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
        if (savedInstanceState == null) handleUrgeIntent(intent)
        setContent {
            val focusState by Pomodoro.state.collectAsState()
            val lockStatus by LockEngine.status.collectAsState()
            val urge by urgeVm.state.collectAsState()
            // An urge lock the flow has not picked up yet shows Home with its end
            // time; a focus block shows the Focus page. When bricks overlap, the one that ends last is named.
            val lockedHome = lockStatus?.takeIf { it.primary != LockMode.FOCUS_BLOCK }
            val brick = focusState.inBlock || lockStatus != null
            val focusBrick = brick && lockedHome == null
            val allowedApps by Pomodoro.allowedApps.collectAsState()
            val pinApps = lockStatus?.ownerApps ?: allowedApps
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
                DisposableEffect(viewModel) {
                    viewModel.addShizukuListener()
                    onDispose { viewModel.removeShizukuListener() }
                }
                val navViewModel: NavigationViewModel = viewModel()

                // A brick shows its own screen, whatever was open before it started.
                val route = if (lockedHome != null) Route.HOME else navViewModel.current()
                val recoverySet = SecurityManager.isRecoveryCodeSet(this@MainActivity)

                BackHandler(enabled = route != Route.HOME) {
                    navViewModel.goBack()
                }
                // In a brick or in the urge flow, Back does nothing: on the main screen it would close
                // the app, and closing the pinned screen ends the pin. Registered last, so it wins.
                BackHandler(enabled = brick || urge.active) { }
                LaunchedEffect(brick) {
                    if (brick) navViewModel.navigateTo(Route.HOME)
                }

                // The pattern was drawn: an urge that stopped after its breathing carries on to the writing.
                LaunchedEffect(entryUnlocked) { if (entryUnlocked) urgeVm.resumeIfAny() }

                // An urge lock ends any open recovery session, so the code can't
                // be used until the lock runs out.
                LaunchedEffect(lockedHome != null) {
                    if (lockedHome != null) SecurityManager.endSession()
                }

                var currentTime by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
                // Only while a timed session shows its countdown, and only while on screen.
                val sessionActive = SecurityManager.isSessionActive()
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

                // Before anything else: opening the app asks for the opening pattern. A brick and the
                // urge flow are never behind it, so the Urge button always works.
                val gateShown = EntryLock.required(
                    enabled = SecurityManager.isEntryLockEnabled(context),
                    recoverySet = recoverySet,
                    unlocked = entryUnlocked,
                    brick = brick,
                    urgeActive = urge.active,
                    hasPattern = SecurityManager.hasEntryPattern(context)
                )

                // Home and the urge flow have no top bar: the clock, or the breathing, is all there is.
                val showTopBar = recoverySet && !gateShown && !urge.active && (focusBrick || route != Route.HOME)

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
                                        val remaining = SecurityManager.sessionEndElapsed - currentTime
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
                        gateShown -> EntryGate(onUnlocked = { entryUnlocked = true }, onUrge = { urgeVm.startUrge(UrgeSource.HOME) })

                        !recoverySet -> SetupRecovery(innerPadding)

                        // The urge flow owns the screen: the choice, the breathing, the writing, the questions, the deep dive.
                        urge.active -> UrgeFlowHost(urgeVm)

                        // The focus block: only this page. It has nothing to protect (no settings, no way
                        // out), so it doesn't wait behind the unlock screen either.
                        focusBrick -> Box(modifier = Modifier.padding(innerPadding)) {
                            FocusScreen(onOpenLog = { })
                        }

                        route == Route.HOME -> {
                            HomeScreen(
                                lock = lockedHome,
                                menuEnabled = Route.menuFor(brick).isNotEmpty(),
                                onUrge = { urgeVm.startUrge(UrgeSource.HOME) },
                                onMenu = { menuOpen = true },
                                modifier = Modifier.padding(innerPadding)
                            )
                            if (menuOpen) {
                                MenuSheet(
                                    routes = Route.menuFor(brick),
                                    onUrge = { menuOpen = false; urgeVm.startUrge(UrgeSource.HOME) },
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
                                    Route.DATA -> PrivateArea { io.github.warleysr.dechainer.screens.DataScreen() }
                                    Route.APPS -> AppsScreen()
                                    Route.SCHEDULES -> SchedulesScreen()
                                    Route.SCHEDULE_EDITOR -> ScheduleEditorScreen()
                                    Route.SETTINGS -> ConfigTab()
                                    Route.RESTRICTIONS -> RestrictionsTab()
                                    Route.SETUP_DEVICE_OWNER -> SetupDeviceOwnerPrivileges()
                                    Route.URGE_SETTINGS -> UrgeSettingsScreen()
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
