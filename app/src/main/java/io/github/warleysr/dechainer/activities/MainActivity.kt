package io.github.warleysr.dechainer.activities

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
     * Pins the phone to Déchaîner while a brick block runs, and releases it when it ends (or is
     * stopped with the recovery code). Only as device owner: without it, Android would show its
     * own "pin this app?" prompt instead. If the app crashes, Android drops the pin by itself:
     * the phone is never trapped, and suspension keeps blocking underneath.
     */
    private fun syncBrickPin(brick: Boolean) {
        try {
            val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java)
            if (!dpm.isDeviceOwnerApp(packageName)) return
            val am = getSystemService(android.app.ActivityManager::class.java)
            val pinned = am.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE
            if (brick && !pinned) {
                // Setting up the pin can't stop the pin itself: a refused setting is logged and
                // the phone is pinned anyway.
                try {
                    io.github.warleysr.dechainer.data.DeviceOwnerRepository.prepareBrick(this, Pomodoro.allowedApps.value)
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
        // Coming back mid-block (say, after answering a call): pin again.
        if (Pomodoro.brickActive()) syncBrickPin(true)
    }


    private val authenticated = mutableStateOf(false)

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
        setContent {
            // The brick: pinned to this screen for the whole of a focus block.
            val focusState by Pomodoro.state.collectAsState()
            val brick = focusState.inBlock
            LaunchedEffect(brick) { syncBrickPin(brick) }
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
                // And a block always shows the Focus page, wherever you were.
                LaunchedEffect(brick) {
                    if (brick && currentScreen != "focus") navViewModel.navigateTo("focus")
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
                                        if (brick) R.string.focus_tab
                                        else if (!authenticated.value) R.string.app_name else when (currentScreen) {
                                            "focus" -> R.string.focus_tab
                                            "focus_log" -> R.string.focus_log
                                            "apps" -> R.string.apps
                                            "config" -> R.string.settings
                                            "restrictions" -> R.string.protections
                                            "schedules", "schedule_editor" -> R.string.schedules
                                            "impulse_lock" -> R.string.impulse_lock
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

                    if (brick)
                        // The brick: only this page. It has nothing to protect (no settings, no
                        // way out), so it doesn't wait behind the unlock screen either.
                        Box(modifier = Modifier.padding(innerPadding)) {
                            FocusScreen(onOpenLog = { })
                        }
                    else if (!(SecurityManager.isRecoveryCodeSet(this)))
                        SetupRecovery(innerPadding)
                    else {
                        if (!authenticated.value)
                            LockScreen(
                                onAuthenticated = { authenticated.value = true }
                            )
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
                                    "impulse_lock" -> ImpulseLockScreen()
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
}
