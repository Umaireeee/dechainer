package io.github.warleysr.dechainer.screens.dumb

import io.github.warleysr.dechainer.models.BlockSchedule
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.warleysr.dechainer.BuildConfig
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.lock.DumbPhone
import io.github.warleysr.dechainer.lock.DumbPhoneRules
import io.github.warleysr.dechainer.screens.common.RepeatWhileVisible
import io.github.warleysr.dechainer.screens.setup.SetupDeviceOwnerPrivileges
import io.github.warleysr.dechainer.security.SecurityManager
import io.github.warleysr.dechainer.viewmodels.DeviceOwnerViewModel
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the exit wait is called on screen: the real one is seven days, a debug build's is five minutes. */
private val waitName: String get() = if (BuildConfig.DEBUG) "5-minute test wait" else "7-day wait"

/**
 * The whole app: one screen at a time, chosen from what is stored. Not started: the Start screen (or
 * the Device Owner setup while that is missing). Started: Home, and Settings behind a tap on the clock.
 * An exit wait that is running: the wait screen. There is nothing else to open and nothing to edit.
 */
@Composable
fun DumbPhoneRoot() {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(TrustedClock.now(ctx)) }
    RepeatWhileVisible(1000) { now = TrustedClock.now(ctx) }
    // Read again every second, so a wait running out shows at once.
    val deviceOwner = remember(now) { DumbPhone.isDeviceOwner(ctx) }
    val startedAt = remember(now) { DumbPhone.startedAt(ctx) }
    val waitLeft = remember(now) { if (startedAt > 0L) SecurityManager.getForcedRemovalRemainingTime(ctx) else -1L }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            startedAt == 0L && !deviceOwner -> Box(Modifier.fillMaxSize().systemBarsPadding()) {
                SetupDeviceOwnerPrivileges()
            }
            startedAt == 0L -> StartScreen()
            waitLeft >= 0L -> WaitScreen(waitLeft)
            showSettings -> SettingsScreen(now = now, startedAt = startedAt, onBack = { showSettings = false })
            else -> HomeScreen(now = now, startedAt = startedAt, onSettings = { showSettings = true })
        }
    }
}

@Composable
private fun StartScreen() {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(Modifier.height(40.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ready to lock your phone", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
            Text(
                "Once it starts, the only way out is a $waitName.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Card {
            val items = listOf(
                "Open every day from ${DumbPhoneRules.openHoursLabel()}, for banking, Gmail and the rest",
                "The rest of the day only calls, SMS, camera, Settings and the hotspot work",
                "Updates and installs only in the open hours; new apps are paused at the next lock",
                "Adult sites are blocked on every network"
            )
            items.forEachIndexed { index, text ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("•", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp))
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HoldButton(label = "Hold to start the lock", enabled = true) { DumbPhone.start(ctx) }
            Text(
                "For the first 10 minutes you can undo it. After that, only the $waitName.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun HomeScreen(now: Long, startedAt: Long, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val zoned = Instant.ofEpochMilli(now).atZone(TrustedClock.zone())
    val is24 = DateFormat.is24HourFormat(ctx)
    val clock = DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm").format(zoned)
    val date = DateTimeFormatter.ofPattern("EEEE, d MMMM").format(zoned).uppercase(Locale.getDefault())
    val undoLeft = DumbPhoneRules.undoRemainingMs(now, startedAt)
    val open = DumbPhoneRules.isOpenAt(zoned.hour * 60 + zoned.minute)
    val statusText = if (open) {
        "Open until ${BlockSchedule.formatMinute(DumbPhoneRules.OPEN_END_MINUTE)}"
    } else {
        "Locked until ${BlockSchedule.formatMinute(DumbPhoneRules.OPEN_START_MINUTE)}"
    }

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        Text(date, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            clock,
            fontSize = 96.sp,
            fontWeight = FontWeight.ExtraLight,
            modifier = Modifier.clickable(onClickLabel = "Open settings", onClick = onSettings).padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Spacer(Modifier.weight(1f))
        Card {
            StatusRow(statusText, strong = true)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StatusRow("Hotspot works", strong = false)
        }
        if (undoLeft > 0L) {
            Spacer(Modifier.height(12.dp))
            val minutes = undoLeft / 60_000
            val seconds = (undoLeft / 1000) % 60
            TextButton(onClick = { DumbPhone.undo(ctx) }) {
                Text("Undo the lock (%d:%02d left)".format(minutes, seconds))
            }
        } else {
            Spacer(Modifier.height(16.dp))
            Text(
                "Tap the clock for settings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusRow(text: String, strong: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
            color = if (strong) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsScreen(now: Long, startedAt: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)
    var confirm by rememberSaveable { mutableStateOf(false) }
    val undoLeft = DumbPhoneRules.undoRemainingMs(now, startedAt)

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
            Text(
                "Nothing here can be changed. The only way out is the $waitName below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Section("OPEN HOURS") {
            ValueRow("Every day", DumbPhoneRules.openHoursLabel())
        }
        Section("PROTECTION") {
            ValueRow("Adult sites", "Blocked, any network")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ValueRow("Installs", "Open hours only")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ValueRow("VPNs and other sources", "Refused")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ValueRow("Second space, users, safe mode", "Off")
        }
        Spacer(Modifier.weight(1f))
        Card {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Need your phone back?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Start a $waitName. The lock stays fully on until it ends, and you can cancel any time.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (undoLeft > 0L) {
                    OutlinedButton(
                        onClick = { DumbPhone.undo(ctx) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text("Undo the lock now") }
                }
                OutlinedButton(
                    onClick = { confirm = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text("Start $waitName") }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Start the $waitName?") },
            text = { Text("Your phone stays fully locked until the wait ends. You can cancel it from the wait screen.") },
            confirmButton = {
                TextButton(onClick = {
                    SecurityManager.startForcedRemoval(ctx)
                    confirm = false
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Not now") } }
        )
    }
}

@Composable
private fun WaitScreen(remainingMs: Long) {
    val ctx = LocalContext.current
    val viewModel: DeviceOwnerViewModel = viewModel()
    var confirmUnlock by rememberSaveable { mutableStateOf(false) }
    val finished = remainingMs <= 0L
    val headline = when {
        finished -> "The wait is over"
        remainingMs < 3_600_000L -> "${(remainingMs + 59_999L) / 60_000L} min"
        else -> {
            val (days, hours) = DumbPhoneRules.splitWait(remainingMs)
            if (days > 0) "$days day${if (days == 1L) "" else "s"}\n$hours hour${if (hours == 1L) "" else "s"}" else "$hours hour${if (hours == 1L) "" else "s"}"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            if (finished) "YOUR PHONE CAN BE UNLOCKED" else "THE LOCK LIFTS IN",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Text(headline, fontSize = 52.sp, fontWeight = FontWeight.ExtraLight, textAlign = TextAlign.Center, lineHeight = 58.sp)
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (finished) {
                OutlinedButton(
                    onClick = { confirmUnlock = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) { Text("Unlock my phone") }
            }
            Button(
                onClick = { SecurityManager.cancelForcedRemoval(ctx) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) { Text("Cancel, stay locked", fontWeight = FontWeight.SemiBold) }
            Text(
                "Until then everything stays locked. Calls, hotspot and camera still work.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }

    if (confirmUnlock) {
        AlertDialog(
            onDismissRequest = { confirmUnlock = false },
            title = { Text("Unlock your phone?") },
            text = { Text("Every limit and protection is lifted and this app stops managing the phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnlock = false
                    // The lock's own settings go first (limit, DNS): they need Device Owner, which goes next.
                    DumbPhone.release(ctx)
                    viewModel.removeDeviceOwner()
                    (ctx as? android.app.Activity)?.recreate()
                }) { Text("Unlock") }
            },
            dismissButton = { TextButton(onClick = { confirmUnlock = false }) { Text("Stay locked") } }
        )
    }
}

// ---- Small pieces ----

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
    ) { content() }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(content)
    }
}

@Composable
private fun ValueRow(name: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.End)
    }
}

/** A button that must be held for a moment: an accidental tap cannot start the lock. */
@Composable
private fun HoldButton(label: String, enabled: Boolean, onDone: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(pressed) {
        if (pressed) {
            val steps = 30
            for (step in 1..steps) {
                delay(50L)
                progress = step / steps.toFloat()
            }
            pressed = false
            progress = 0f
            onDone()
        } else {
            progress = 0f
        }
    }
    val base = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(50))
            .background(base)
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures(onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f))
        )
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
