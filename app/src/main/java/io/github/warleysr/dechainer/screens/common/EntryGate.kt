package io.github.warleysr.dechainer.screens.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.security.EntryAttempt
import io.github.warleysr.dechainer.security.Pattern
import io.github.warleysr.dechainer.security.SecurityManager
import kotlinx.coroutines.delay

/**
 * The screen shown before anything else when Déchaîner is opened: draw the opening pattern. The
 * first time there is none yet, so it asks for one to be drawn twice. [onUrge] starts the urge flow
 * from here, without the pattern: an urge never waits behind a lock. Nothing behind this is drawn.
 */
@Composable
fun EntryGate(onUnlocked: () -> Unit, onUrge: () -> Unit) {
    PatternScreen(
        title = R.string.entry_gate_title,
        onUnlocked = onUnlocked,
        footer = { OutlinedButton(onClick = onUrge) { Text(stringResource(R.string.entry_gate_urge)) } }
    )
}

/**
 * The private pages (your data) ask for the opening pattern again unless one was drawn in the last
 * few minutes (opening the app counts).
 */
@Composable
fun PrivateArea(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(SecurityManager.isPrivateOpen()) }
    // Ask only when the opening lock is on and a pattern exists. With the lock off, or before a
    // pattern was ever drawn, these pages are reachable directly.
    val gated = SecurityManager.isEntryLockEnabled(context) && SecurityManager.hasEntryPattern(context)
    if (open || !gated) content() else PatternScreen(title = R.string.private_title, onUnlocked = { open = true })
}

@Composable
private fun PatternScreen(title: Int, onUnlocked: () -> Unit, footer: (@Composable () -> Unit)? = null) {
    val context = LocalContext.current
    var hasPattern by remember { mutableStateOf(SecurityManager.hasEntryPattern(context)) }
    var forgot by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState())
                .padding(32.dp)
                .widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            if (hasPattern) {
                DrawPattern(title = title, onUnlocked = onUnlocked, onForgot = { forgot = true })
            } else {
                Text(stringResource(R.string.entry_gate_create_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text(
                    stringResource(R.string.entry_gate_create_text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                NewPatternForm(onSaved = { hasPattern = true; onUnlocked() })
            }
            footer?.invoke()
        }
    }
    if (forgot) {
        ForgotPatternDialog(
            onCleared = { forgot = false; hasPattern = false },
            onDismiss = { forgot = false }
        )
    }
}

@Composable
private fun DrawPattern(title: Int, onUnlocked: () -> Unit, onForgot: () -> Unit) {
    val context = LocalContext.current
    var wrong by remember { mutableStateOf(false) }
    var waitMs by remember { mutableLongStateOf(SecurityManager.entryWaitMs(context)) }

    // Counts a wait down; the pad is off while it runs.
    LaunchedEffect(waitMs > 0L) {
        while (waitMs > 0L) {
            delay(1000)
            waitMs = SecurityManager.entryWaitMs(context)
        }
    }

    Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    Text(
        stringResource(R.string.entry_gate_draw),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    PatternPad(
        enabled = waitMs <= 0L,
        isError = wrong,
        onPattern = { dots ->
            when (SecurityManager.tryEntryPattern(context, dots)) {
                EntryAttempt.OK -> onUnlocked()
                EntryAttempt.WRONG -> wrong = true
                EntryAttempt.WAIT -> Unit
            }
            waitMs = SecurityManager.entryWaitMs(context)
        }
    )
    when {
        waitMs > 0L -> Text(
            stringResource(R.string.entry_gate_wait, formatSeconds(waitMs)),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        wrong -> Text(stringResource(R.string.entry_gate_wrong), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
    TextButton(onClick = onForgot) { Text(stringResource(R.string.entry_gate_forgot)) }
}

private fun formatSeconds(ms: Long): String {
    val total = (ms + 999) / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

/** Draw a new pattern twice. [onSaved] runs once it is stored. */
@Composable
fun NewPatternForm(onSaved: () -> Unit) {
    val context = LocalContext.current
    var first by remember { mutableStateOf<List<Int>?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }

    Text(
        stringResource(if (first == null) R.string.entry_draw_new else R.string.entry_draw_again),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center
    )
    PatternPad(
        isError = error != null,
        onPattern = { dots ->
            val firstDraw = first
            error = null
            when {
                firstDraw == null ->
                    if (Pattern.isValid(dots)) first = dots else error = R.string.entry_pattern_short
                firstDraw != dots -> { first = null; error = R.string.entry_pattern_mismatch }
                !SecurityManager.setEntryPattern(context, dots) -> { first = null; error = R.string.entry_pattern_refused }
                else -> onSaved()
            }
        }
    )
    error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
}

/** Choose a new opening pattern from Settings. The caller has already asked for the recovery code. */
@Composable
fun ChangePatternDialog(onDone: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.entry_change_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NewPatternForm(onSaved = onDone)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** Forgot the pattern: the recovery code clears it, then a new one is drawn at the gate. */
@Composable
private fun ForgotPatternDialog(onCleared: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.entry_forgot_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.entry_forgot_text))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().filter { c -> c in 'A'..'Z' }; wrong = false },
                    label = { Text(stringResource(R.string.entry_forgot_code)) },
                    singleLine = true,
                    isError = wrong,
                    visualTransformation = PasswordVisualTransformation()
                )
                if (wrong) Text(stringResource(R.string.entry_gate_wrong), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (SecurityManager.validateRecoveryCode(context, code)) {
                    SecurityManager.clearEntryPattern(context)
                    onCleared()
                } else {
                    wrong = true
                }
            }, enabled = code.isNotEmpty()) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
