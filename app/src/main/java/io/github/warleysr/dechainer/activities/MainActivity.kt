package io.github.warleysr.dechainer.activities

import io.github.warleysr.dechainer.lock.LockEngine
import io.github.warleysr.dechainer.lock.LockMode
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.screens.LockedHomeScreen
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import io.github.warleysr.dechainer.focus.Pomodoro
import io.github.warleysr.dechainer.ui.theme.Motion
import io.github.warleysr.dechainer.screens.common.ScreenInfoButton
import io.github.warleysr.dechainer.screens.common.RepeatWhileVisible
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.AppBlocking
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.LockClock
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.screens.apps.AppsScreen
import io.github.warleysr.dechainer.screens.focus.FocusLogScreen
import io.github.warleysr.dechainer.screens.focus.FocusScreen
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Schedule
import io.github.warleysr.dechainer.screens.setup.SetupDeviceOwnerPrivileges
import io.github.warleysr.dechainer.screens.setup.SetupRecovery
import io.github.warleysr.dechainer.screens.tabs.*
import io.github.warleysr.dechainer.security.SecurityManager
import io.github.warleysr.dechainer.ui.theme.DechainerTheme
import io.github.warleysr.dechainer.viewmodels.DeviceOwnerViewModel
import io.github.warleysr.dechainer.viewmodels.NavigationViewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
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
    }


    private val authenticated = mutableStateOf(false)

    // When the app last left the screen (uptime), so a long absence asks to unlock again.
    private var leftAt = 0L

    override fun onStop() {
        super.onStop()
        leftAt = android.os.SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        // Unlocking once must not keep the app open for days: after a real absence the unlock
        // (and the entry challenge) is asked again. A short trip to Settings keeps you in.
        if (authenticated.value && leftAt > 0L &&
            android.os.SystemClock.elapsedRealtime() - leftAt > RELOCK_AFTER_MS
        ) {
            authenticated.value = false
        }
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
        setContent {
            // The brick: pinned to this screen for as long as anything holds the phone.
            val focusState by Pomodoro.state.collectAsState()
            val lockStatus by LockEngine.status.collectAsState()
            // An urge lock or a punishment day shows the locked screen; a focus block shows Focus.
            // When they overlap, the one that ends last is the one named.
            val lockedHome = lockStatus?.takeIf { it.primary != LockMode.FOCUS_BLOCK }
            val brick = focusState.inBlock || lockStatus != null
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
            DechainerTheme {
                val viewModel: DeviceOwnerViewModel = viewModel()
                viewModel.addShizukuListener()
                val navViewModel: NavigationViewModel = viewModel()

                val currentScreen = navViewModel.selectedTab()
                val isRoot = currentScreen in NavigationViewModel.ROOTS

                BackHandler(enabled = !isRoot) {
                    navViewModel.goBack()
                }
                // In a block, Back does nothing: on the main screen it would close the app, and
                // closing the pinned screen ends the pin. Registered last, so it wins.
                BackHandler(enabled = brick) { }
                // And a focus block always shows the Focus page, wherever you were.
                LaunchedEffect(brick, lockedHome) {
                    if (brick && lockedHome == null && currentScreen != "focus") navViewModel.navigateTo("focus")
                }

                // An urge lock or a punishment day locks Déchaîner itself, also when you are already
                // inside (the journal can start one from outside): the way in closes, and any open
                // recovery session ends, so the code can't be used until the lock runs out.
                LaunchedEffect(lockedHome != null) {
                    if (lockedHome != null) {
                        authenticated.value = false
                        SecurityManager.endSession()
                    }
                }

                var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
                // Only while a timed session shows its countdown, and only while on screen.
                val sessionActive = SecurityManager.isSessionActive()
                if (sessionActive) {
                    RepeatWhileVisible(1000, key = sessionActive) { currentTime = System.currentTimeMillis() }
                }

                val context = LocalContext.current
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) {}
                LaunchedEffect(authenticated.value) {
                    if (!authenticated.value) return@LaunchedEffect
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
                    // The Pomodoro rings through a notification, so it needs this permission.

                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            // The screen you're on, so you always know where you are. Today keeps
                            // the app's name.
                            title = {
                                Text(
                                    stringResource(
                                        if (lockedHome != null) R.string.locked_title
                                        else if (brick) R.string.focus_tab
                                        else if (!authenticated.value) R.string.app_name else when (currentScreen) {
                                            "focus" -> R.string.focus_tab
                                            "focus_log" -> R.string.focus_log
                                            "apps" -> R.string.apps
                                            "config" -> R.string.settings
                                            "restrictions" -> R.string.protections
                                            "schedules", "schedule_editor" -> R.string.schedules
                                            "entry_challenge" -> R.string.entry_challenge
                                            else -> R.string.app_name
                                        }
                                    )
                                )
                            },
                            // Same tone as the page, so the bar reads as part of it, not a band on top.
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background
                            ),
                            navigationIcon = {
                                if (!isRoot) {
                                    IconButton(onClick = { navViewModel.goBack() }) {
                                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                                    }
                                }
                            },
                            actions = {
                                // Nothing up here during a block: no info, no sign-out.
                                if (!brick) {
                                if (authenticated.value) ScreenInfoButton(currentScreen)
                                if (SecurityManager.isSessionActive()) {
                                    val remaining = SecurityManager.sessionEndTime - currentTime
                                    val minutes = (remaining / 1000) / 60
                                    val seconds = (remaining / 1000) % 60
                                    
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.LockClock, null, modifier = Modifier.padding(end = 4.dp))
                                        Text(
                                            text = "%02d:%02d".format(minutes, seconds),
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                        IconButton(onClick = { SecurityManager.endSession() }) {
                                            Icon(Icons.Outlined.Logout, null)
                                        }
                                    }
                                }
                                }
                            }
                        )
                    },
                    bottomBar = {
                      // No tabs during a block: the Focus page is all there is.
                      if (!brick) {
                        val tabs = listOf(
                            Pair("focus", stringResource(R.string.focus_tab)),
                            Pair("apps", stringResource(R.string.apps)),
                            Pair("schedules", stringResource(R.string.schedules)),
                            Pair("config", stringResource(R.string.settings))
                        )

                        val selectedBaseTab = when (currentScreen) {
                            "focus", "focus_log" -> "focus"
                            "apps" -> "apps"
                            "schedules", "schedule_editor" -> "schedules"
                            else -> "config"
                        }

                        NavigationBar(
                            modifier = Modifier.alpha(if (!authenticated.value) 0f else 1f)
                        ) {
                            tabs.forEach { pair ->
                                NavigationBarItem(
                                    selected = selectedBaseTab == pair.first,
                                    onClick = { navViewModel.navigateTo(pair.first) },
                                    label = { Text(pair.second) },
                                    icon = {
                                        Icon(
                                            when (pair.first) {
                                                "focus" -> Icons.Outlined.Timer
                                                "apps" -> Icons.Outlined.Block
                                                "schedules" -> Icons.Outlined.Schedule
                                                else -> Icons.Outlined.Settings
                                            }, contentDescription = null
                                        )
                                    }
                                )
                            }
                        }
                      }
                    }
                ) { innerPadding ->

                    if (lockedHome != null)
                        // An urge lock or a punishment day: when it ends and why, nothing to tap.
                        LockedHomeScreen(lockedHome, Modifier.padding(innerPadding))
                    else if (brick)
                        // The brick: only this page. It has nothing to protect (no settings, no
                        // way out), so it doesn't wait behind the unlock screen either.
                        Box(modifier = Modifier.padding(innerPadding)) {
                            FocusScreen(onOpenLog = { })
                        }
                    else if (!(SecurityManager.isRecoveryCodeSet(this)))
                        SetupRecovery(innerPadding)
                    else {
                        if (!authenticated.value)
                            LockScreen(onAuthenticated = { authenticated.value = true })
                        else
                            Box(modifier = Modifier.padding(innerPadding)) {
                                // A soft cross-fade between screens: it answers the tap without
                                // pulling attention.
                                AnimatedContent(
                                    targetState = currentScreen,
                                    transitionSpec = {
                                        fadeIn(tween(Motion.SCREEN_MS, delayMillis = 60)) togetherWith fadeOut(tween(Motion.SCREEN_OUT_MS))
                                    },
                                    label = "screen"
                                ) { screen ->
                                when (screen) {
                                    "focus" -> FocusScreen(onOpenLog = { navViewModel.navigateTo("focus_log") })
                                    "focus_log" -> FocusLogScreen()
                                    "apps" -> AppsScreen()
                                    "schedules" -> SchedulesScreen()
                                    "schedule_editor" -> ScheduleEditorScreen()
                                    "config" -> ConfigTab()
                                    "restrictions" -> RestrictionsTab()
                                    "entry_challenge" -> EntryChallengeScreen()
                                    "setup_device_owner" -> SetupDeviceOwnerPrivileges()
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

    private companion object {
        /** Away longer than this, and the app asks to be unlocked again. */
        const val RELOCK_AFTER_MS = 5 * 60 * 1000L
    }
}
