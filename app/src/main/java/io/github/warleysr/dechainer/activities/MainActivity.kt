package io.github.warleysr.dechainer.activities

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.AppBlocking
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.data.ApkUpdateInstaller
import io.github.warleysr.dechainer.screens.common.PendingChange
import io.github.warleysr.dechainer.screens.setup.SetupDeviceOwnerPrivileges
import io.github.warleysr.dechainer.screens.tabs.ActivityBlockerScreen
import io.github.warleysr.dechainer.screens.tabs.AppsTab
import io.github.warleysr.dechainer.screens.tabs.BrowserHardeningScreen
import io.github.warleysr.dechainer.screens.tabs.ConfigTab
import io.github.warleysr.dechainer.screens.tabs.ImpulseLockScreen
import io.github.warleysr.dechainer.screens.tabs.LocalDnsShieldScreen
import io.github.warleysr.dechainer.screens.tabs.PendingUnlocksScreen
import io.github.warleysr.dechainer.screens.tabs.RestrictionsTab
import io.github.warleysr.dechainer.screens.tabs.ScheduleTab
import io.github.warleysr.dechainer.screens.tabs.SuspensionLogsScreen
import io.github.warleysr.dechainer.security.SecurityManager
import io.github.warleysr.dechainer.security.TimelockManager
import io.github.warleysr.dechainer.ui.theme.DechainerTheme
import io.github.warleysr.dechainer.viewmodels.DeviceOwnerViewModel
import io.github.warleysr.dechainer.viewmodels.NavigationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val STATE_AUTHENTICATED = "authenticated"
        private const val AUTH_GRACE_MS = 30_000L
    }

    private val authenticated = mutableStateOf(false)
    private var lastStopElapsed = 0L

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wasAuthenticated = savedInstanceState?.getBoolean(STATE_AUTHENTICATED, false) == true
        authenticated.value = wasAuthenticated && SecurityManager.getImpulseBlockRemainingTime(this) <= 0L
        TimelockManager.syncAll(this)
        SecurityManager.consumeDebugRestoreUnknownSourcesRestriction(this)
        lifecycleScope.launch(Dispatchers.IO) {
            ApkUpdateInstaller.restoreUnknownSourcesRestrictionsIfIdle(this@MainActivity, abandonStale = true)
        }
        enableEdgeToEdge()
        setContent {
            DechainerTheme {
                val viewModel: DeviceOwnerViewModel = viewModel()
                val navViewModel: NavigationViewModel = viewModel()
                val pendingRequests by TimelockManager.requests.collectAsState()

                val isUnlocked = authenticated.value
                val currentScreen = navViewModel.selectedTab()
                val isRoot = currentScreen in listOf("apps", "schedule", "config", "suspension_logs")

                BackHandler(enabled = isUnlocked && !isRoot) {
                    navViewModel.goBack()
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.app_name)) },
                            navigationIcon = {
                                if (isUnlocked && !isRoot) {
                                    IconButton(onClick = { navViewModel.goBack() }) {
                                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                                    }
                                }
                            },
                            actions = {
                                if (isUnlocked && pendingRequests.isNotEmpty() && currentScreen != "pending_unlocks") {
                                    IconButton(onClick = { navViewModel.navigateTo("pending_unlocks") }) {
                                        BadgedBox(
                                            badge = {
                                                Badge { Text(pendingRequests.size.toString()) }
                                            }
                                        ) {
                                            Icon(
                                                Icons.Outlined.HourglassTop,
                                                contentDescription = stringResource(R.string.timelock_pending_title)
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    },
                    snackbarHost = {
                        SnackbarHost(PendingChange.snackbarHost) { data ->
                            Snackbar(
                                modifier = Modifier.padding(16.dp),
                                action = {
                                    data.visuals.actionLabel?.let { actionLabel ->
                                        IconButton(onClick = { data.performAction() }) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Outlined.Undo,
                                                contentDescription = actionLabel,
                                                tint = SnackbarDefaults.actionColor
                                            )
                                        }
                                    }
                                }
                            ) {
                                Text(data.visuals.message)
                            }
                        }
                    },
                    bottomBar = {
                        if (!isUnlocked) return@Scaffold

                        val tabs = listOf(
                            Pair("apps", stringResource(R.string.apps)),
                            Pair("schedule", stringResource(R.string.schedule_title)),
                            Pair("config", stringResource(R.string.protection_title)),
                            Pair("suspension_logs", stringResource(R.string.log_title))
                        )

                        val selectedBaseTab = when (currentScreen) {
                            "apps", "schedule", "suspension_logs" -> currentScreen
                            else -> "config"
                        }

                        NavigationBar {
                            tabs.forEach { pair ->
                                NavigationBarItem(
                                    selected = selectedBaseTab == pair.first,
                                    onClick = { navViewModel.navigateTo(pair.first) },
                                    label = { Text(pair.second) },
                                    icon = {
                                        Icon(
                                            when (pair.first) {
                                                "apps" -> Icons.Default.AppBlocking
                                                "schedule" -> Icons.Default.Schedule
                                                "suspension_logs" -> Icons.Default.History
                                                else -> Icons.Outlined.Shield
                                            },
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    if (!authenticated.value) {
                        Box(
                            modifier = Modifier
                                .padding(innerPadding)
                                .consumeWindowInsets(innerPadding)
                        ) {
                            LockScreen(
                                isOwner = viewModel.isDeviceOwner(),
                                onAuthenticated = {
                                    if (SecurityManager.getImpulseBlockRemainingTime(this@MainActivity) <= 0L) {
                                        authenticated.value = true
                                    }
                                }
                            )
                        }
                    } else {
                        Box(modifier = Modifier.padding(innerPadding)) {
                            when (currentScreen) {
                                "restrictions" -> RestrictionsTab()
                                "apps" -> AppsTab()
                                "schedule" -> ScheduleTab()
                                "config" -> ConfigTab()
                                "setup_device_owner" -> SetupDeviceOwnerPrivileges()
                                "activity_blocker" -> ActivityBlockerScreen()
                                "impulse_lock" -> ImpulseLockScreen()
                                "suspension_logs" -> SuspensionLogsScreen()
                                "pending_unlocks" -> PendingUnlocksScreen()
                                "local_dns_shield" -> LocalDnsShieldScreen()
                                "browser_hardening" -> BrowserHardeningScreen()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(
            STATE_AUTHENTICATED,
            authenticated.value && SecurityManager.getImpulseBlockRemainingTime(this) <= 0L
        )
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            lastStopElapsed = SystemClock.elapsedRealtime()
        }
    }

    override fun onResume() {
        super.onResume()
        TimelockManager.syncAll(this)
        val impulseActive = SecurityManager.getImpulseBlockRemainingTime(this) > 0L
        val timedOutInBackground = lastStopElapsed > 0L &&
            SystemClock.elapsedRealtime() - lastStopElapsed > AUTH_GRACE_MS
        if (impulseActive || timedOutInBackground) {
            authenticated.value = false
        }
        lastStopElapsed = 0L
    }
}
