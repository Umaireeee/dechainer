package io.github.warleysr.dechainer.screens.dumb

import android.content.Context
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import io.github.warleysr.dechainer.data.AppRepository
import io.github.warleysr.dechainer.lock.DumbPhone
import io.github.warleysr.dechainer.lock.ExitWait
import io.github.warleysr.dechainer.lock.LockdownInput
import io.github.warleysr.dechainer.lock.StudyMode
import io.github.warleysr.dechainer.lock.StudyRules
import io.github.warleysr.dechainer.models.AppItem
import io.github.warleysr.dechainer.screens.common.AppPickerDialog
import io.github.warleysr.dechainer.screens.common.RepeatWhileVisible
import io.github.warleysr.dechainer.screens.setup.SetupDeviceOwnerPrivileges
import io.github.warleysr.dechainer.security.SecurityManager
import io.github.warleysr.dechainer.viewmodels.DeviceOwnerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the exit wait is called on screen: the real one is seven days, a debug build's is five minutes. */
private val waitName: String get() = if (BuildConfig.DEBUG) "5-minute test wait" else "7-day wait"

private const val HOME = "home"
private const val SETTINGS = "settings"
private const val STUDY = "study"
private const val LOCKDOWN = "lockdown"

/**
 * The whole app: one screen at a time, chosen from what is stored. Device Owner missing: its setup.
 * An exit wait that is running: the wait screen. Otherwise Home, with Study, Lockdown and Settings
 * (behind a tap on the clock) one tap away. Nothing here can end a running session early.
 */
@Composable
fun DumbPhoneRoot() {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(TrustedClock.now(ctx)) }
    RepeatWhileVisible(1000) { now = TrustedClock.now(ctx) }
    // Read again every second, so a session running out or a wait running out shows at once.
    val deviceOwner = remember(now) { DumbPhone.isDeviceOwner(ctx) }
    val waitLeft = remember(now) { if (deviceOwner) SecurityManager.getForcedRemovalRemainingTime(ctx) else -1L }
    val studyEnds = remember(now) { StudyMode.studyEndsAt(ctx) }
    val lockdown = remember(now) { StudyMode.lockdown(ctx) }
    var screen by rememberSaveable { mutableStateOf(HOME) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            !deviceOwner -> Box(Modifier.fillMaxSize().systemBarsPadding()) {
                SetupDeviceOwnerPrivileges()
            }
            waitLeft >= 0L -> WaitScreen(waitLeft)
            screen == SETTINGS -> SettingsScreen(onBack = { screen = HOME })
            screen == STUDY -> StudyScreen(now = now, runningEnds = studyEnds, onBack = { screen = HOME })
            screen == LOCKDOWN -> LockdownScreen(now = now, lockdown = lockdown, onBack = { screen = HOME })
            else -> HomeScreen(
                now = now,
                studyEnds = studyEnds,
                lockdown = lockdown,
                onStudy = { screen = STUDY },
                onLockdown = { screen = LOCKDOWN },
                onSettings = { screen = SETTINGS }
            )
        }
    }
}

@Composable
private fun HomeScreen(
    now: Long,
    studyEnds: Long,
    lockdown: LockdownInput,
    onStudy: () -> Unit,
    onLockdown: () -> Unit,
    onSettings: () -> Unit
) {
    val ctx = LocalContext.current
    val zoned = Instant.ofEpochMilli(now).atZone(TrustedClock.zone())
    val is24 = DateFormat.is24HourFormat(ctx)
    val clock = DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm").format(zoned)
    val date = DateTimeFormatter.ofPattern("EEEE, d MMMM").format(zoned).uppercase(Locale.getDefault())
    val studying = studyEnds > now
    val lockedDown = lockdown.endsAt > now && lockdown.apps.isNotEmpty()

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
            if (studying) {
                StatusRow("Study mode until ${clockText(ctx, studyEnds)}", strong = true)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                StatusRow("${leftText(StudyRules.minutesLeft(now, studyEnds))} left. Hotspot works", strong = false)
            }
            if (lockedDown) {
                if (studying) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                StatusRow(
                    "Lockdown until ${clockText(ctx, lockdown.endsAt)}, ${lockdown.apps.size} app${if (lockdown.apps.size == 1) "" else "s"}",
                    strong = true
                )
            }
            if (!studying && !lockedDown) StatusRow("Nothing is locked", strong = true)
            if (DumbPhone.isActive(ctx)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                StatusRow("Adult sites are blocked on every network", strong = false)
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onStudy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) { Text(if (studying) "Extend study mode" else "Study", fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onLockdown,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        ) { Text(if (lockedDown) "Lockdown" else "Lockdown on my distractions") }
        Spacer(Modifier.height(16.dp))
        Text(
            "Tap the clock for settings",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

// ---- Study mode ----

@Composable
private fun StudyScreen(now: Long, runningEnds: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)
    val running = runningEnds > now
    var endsAt by rememberSaveable { mutableStateOf(0L) }
    var refused by remember { mutableStateOf<String?>(null) }
    val decision = if (endsAt > 0L) StudyRules.decide(now, runningEnds, endsAt, deviceOwner = true) else null
    val startable = decision is StudyRules.Start.Started || decision is StudyRules.Start.Extended

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(
                if (running) "Study mode is on" else "Study mode",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                if (running) "It ends at ${clockText(ctx, runningEnds)}. You can make it longer, never shorter."
                else "Pick when it ends. Until then the phone is locked, and nothing ends it early.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        BulletCard(
            listOf(
                "Only calls, SMS, camera, Settings, the alarm clock and Gemini work",
                "The Google app is hidden. Every other app is paused",
                "No installing and no uninstalling while it runs",
                "When it ends the phone is normal again. The adult-site filter stays"
            )
        )
        EndTimeChooser(now = now, runningEnds = runningEnds, endsAt = endsAt, onChange = { endsAt = it; refused = null })
        EndSummary(ctx, now, endsAt, decision)
        refused?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        HoldButton(label = if (running) "Hold to extend" else "Hold to start study mode", enabled = startable) {
            when (val result = StudyMode.startStudy(ctx, endsAt)) {
                is StudyRules.Start.Started, is StudyRules.Start.Extended -> onBack()
                else -> refused = describe(ctx, now, endsAt, result).first
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---- Lockdown ----

@Composable
private fun LockdownScreen(now: Long, lockdown: LockdownInput, onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)
    val running = lockdown.endsAt > now && lockdown.apps.isNotEmpty()
    var endsAt by rememberSaveable { mutableStateOf(0L) }
    // The apps of the last lockdown are the starting point; a running one can only gain apps.
    var picked by remember { mutableStateOf(lockdown.apps) }
    var showPicker by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf<String?>(null) }
    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { AppRepository.getApps() }
        loading = false
    }

    val requestedEnd = when {
        endsAt > 0L -> endsAt
        running -> lockdown.endsAt
        else -> 0L
    }
    val decision = if (requestedEnd > 0L) {
        StudyRules.decideLockdown(now, lockdown.endsAt, lockdown.apps, requestedEnd, picked, deviceOwner = true)
    } else null
    val startable = decision is StudyRules.Start.Started ||
        decision is StudyRules.Start.Extended ||
        decision is StudyRules.Start.AppsAdded
    val names = apps.filter { it.packageName in picked }.map { it.name }.sortedBy { it.lowercase() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(
                if (running) "Lockdown is on" else "Lockdown",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                if (running) "It ends at ${clockText(ctx, lockdown.endsAt)}. You can add apps and make it longer, never the other way."
                else "Pick the apps that pull you away and how long to block them. The rest of the phone works as usual, and nothing ends it early.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Section("APPS TO BLOCK") {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    when {
                        picked.isEmpty() -> "None picked yet"
                        names.isEmpty() -> "${picked.size} app${if (picked.size == 1) "" else "s"}"
                        names.size <= 4 -> names.joinToString(", ")
                        else -> names.take(4).joinToString(", ") + " and ${names.size - 4} more"
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
                OutlinedButton(
                    onClick = { showPicker = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text(if (running) "Add apps" else "Choose apps") }
            }
        }
        EndTimeChooser(now = now, runningEnds = if (running) lockdown.endsAt else 0L, endsAt = endsAt, onChange = { endsAt = it; refused = null })
        EndSummary(ctx, now, requestedEnd, decision)
        refused?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        HoldButton(
            label = when {
                !running -> "Hold to start lockdown"
                endsAt > 0L -> "Hold to extend"
                else -> "Hold to add the apps"
            },
            enabled = startable
        ) {
            when (val result = StudyMode.startLockdown(ctx, requestedEnd, picked)) {
                is StudyRules.Start.Started, is StudyRules.Start.Extended, is StudyRules.Start.AppsAdded -> onBack()
                else -> refused = describe(ctx, now, requestedEnd, result).first
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showPicker) {
        AppPickerDialog(
            apps = apps,
            isLoading = loading,
            isSelected = { it in picked },
            onToggle = { pkg ->
                // While a lockdown runs its list only grows: an app it already holds cannot be unticked.
                if (!(running && pkg in lockdown.apps)) picked = if (pkg in picked) picked - pkg else picked + pkg
            },
            onDismiss = { showPicker = false }
        )
    }
}

// ---- Choosing an end time ----

@Composable
private fun EndTimeChooser(now: Long, runningEnds: Long, endsAt: Long, onChange: (Long) -> Unit) {
    val ctx = LocalContext.current
    val running = runningEnds > now
    // A running session grows from its own end; a new one from now.
    val base = if (running) runningEnds else now
    Section(if (running) "MAKE IT LONGER BY" else "RUN FOR") {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(1, 2, 3, 5).forEach { hours ->
                    OutlinedButton(
                        onClick = { onChange(base + hours * 3_600_000L) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text(if (running) "+$hours h" else "$hours h", maxLines = 1) }
                }
            }
            OutlinedButton(
                onClick = {
                    val zoned = Instant.ofEpochMilli(TrustedClock.now(ctx)).atZone(TrustedClock.zone())
                    android.app.TimePickerDialog(
                        ctx,
                        { _, hour, minute ->
                            onChange(StudyRules.nextOccurrence(TrustedClock.now(ctx), TrustedClock.zone(), hour * 60 + minute))
                        },
                        zoned.hour,
                        zoned.minute,
                        DateFormat.is24HourFormat(ctx)
                    ).show()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) { Text("Pick an end time") }
        }
    }
}

@Composable
private fun EndSummary(ctx: Context, now: Long, endsAt: Long, decision: StudyRules.Start?) {
    val (text, isError) = if (decision == null) "Choose how long." to false else describe(ctx, now, endsAt, decision)
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground
    )
}

/** The words for a decision, and whether it is a refusal. */
private fun describe(ctx: Context, now: Long, endsAt: Long, decision: StudyRules.Start): Pair<String, Boolean> = when (decision) {
    is StudyRules.Start.Started, is StudyRules.Start.Extended ->
        "Until ${clockText(ctx, endsAt)}, ${leftText(StudyRules.minutesLeft(now, endsAt))} from now" to false
    is StudyRules.Start.AppsAdded -> "The apps are added. It still ends at ${clockText(ctx, decision.endsAt)}" to false
    StudyRules.Start.TooShort -> "At least 10 minutes." to true
    StudyRules.Start.TooLong -> "At most 12 hours from now." to true
    StudyRules.Start.NotLonger -> "A running one can only be made longer, or gain apps." to true
    StudyRules.Start.NothingPicked -> "Pick at least one app." to true
    StudyRules.Start.NoDeviceOwner -> "This phone has not given the app Device Owner." to true
}

/** The time of day as the phone shows it, 24-hour or not. */
private fun clockText(ctx: Context, millis: Long): String {
    val pattern = if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a"
    return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(Instant.ofEpochMilli(millis).atZone(TrustedClock.zone()))
}

private fun leftText(minutes: Long): String =
    if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

// ---- Settings and the exit ----

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)
    var confirm by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
            Text(
                "Study mode and lockdown cannot be ended early. The only way to take this app off your phone is the $waitName below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Section("ALWAYS ON") {
            ValueRow("Adult sites", "Blocked, any network")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ValueRow("VPN and Private DNS settings", "Refused")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ValueRow("Date and time", "Locked")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ValueRow("Uninstalling this app", "Refused")
        }
        Section("ONLY WHILE STUDYING OR IN LOCKDOWN") {
            ValueRow("Uninstalling other apps", "Refused")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ValueRow("Installs, second space, safe mode", "Off (study mode)")
        }
        Spacer(Modifier.weight(1f))
        Card {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Need your phone back?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Start a $waitName. A session or lockdown that is running keeps going to its end time meanwhile, and you can cancel the wait any time.",
                    style = MaterialTheme.typography.bodyMedium
                )
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
            text = { Text("When it ends you can unlock the phone for good. You can cancel it from the wait screen.") },
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
            val (days, hours) = ExitWait.splitWait(remainingMs)
            if (days > 0) "$days day${if (days == 1L) "" else "s"}\n$hours hour${if (hours == 1L) "" else "s"}" else "$hours hour${if (hours == 1L) "" else "s"}"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            if (finished) "YOUR PHONE CAN BE UNLOCKED" else "THIS APP LETS GO OF YOUR PHONE IN",
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
            ) { Text("Cancel the wait", fontWeight = FontWeight.SemiBold) }
            Text(
                "Until then everything stays as it is. A study session or lockdown that is running keeps going to its end time.",
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
                    // The protection's own settings go first (DNS, sessions): they need Device Owner, which goes next.
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
private fun BulletCard(items: List<String>) {
    Card {
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

/** A button that must be held for a moment: an accidental tap cannot start a session. */
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
