package io.github.warleysr.dechainer.screens.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.security.EntryAttempt
import io.github.warleysr.dechainer.security.EntryLock
import io.github.warleysr.dechainer.security.SecurityManager
import kotlinx.coroutines.delay

/**
 * The screen shown before anything else when Déchaîner is opened: the opening password. The first
 * time there is none yet, so it asks for one to be chosen. A forgotten password is cleared with the
 * recovery code, never by anything on this screen alone. Nothing behind it is drawn.
 */
@Composable
fun EntryGate(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    var hasPassword by remember { mutableStateOf(SecurityManager.hasEntryPassword(context)) }
    var forgot by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            if (hasPassword) {
                EnterPassword(onUnlocked = onUnlocked, onForgot = { forgot = true })
            } else {
                Text(
                    stringResource(R.string.entry_gate_create_title),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center
                )
                Text(
                    stringResource(R.string.entry_gate_create_text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                NewPasswordForm(onSaved = { hasPassword = true; onUnlocked() })
            }
        }
    }
    if (forgot) {
        ForgotPasswordDialog(
            onCleared = { forgot = false; hasPassword = false },
            onDismiss = { forgot = false }
        )
    }
}

@Composable
private fun EnterPassword(onUnlocked: () -> Unit, onForgot: () -> Unit) {
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var waitMs by remember { mutableLongStateOf(SecurityManager.entryWaitMs(context)) }

    // Counts a wait down; the field is off while it runs.
    LaunchedEffect(waitMs > 0L) {
        while (waitMs > 0L) {
            delay(1000)
            waitMs = SecurityManager.entryWaitMs(context)
        }
    }

    fun submit() {
        if (password.isEmpty() || waitMs > 0L) return
        when (SecurityManager.tryEntryPassword(context, password)) {
            EntryAttempt.OK -> onUnlocked()
            EntryAttempt.WRONG -> { wrong = true; password = "" }
            EntryAttempt.WAIT -> password = ""
        }
        waitMs = SecurityManager.entryWaitMs(context)
    }

    Text(
        stringResource(R.string.entry_gate_title),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it.take(EntryLock.MAX_LENGTH); wrong = false },
        label = { Text(stringResource(R.string.entry_gate_password)) },
        singleLine = true,
        enabled = waitMs <= 0L,
        isError = wrong,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = Modifier.fillMaxWidth()
    )
    when {
        waitMs > 0L -> Text(
            stringResource(R.string.entry_gate_wait, formatSeconds(waitMs)),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        wrong -> Text(
            stringResource(R.string.entry_gate_wrong),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
    }
    Button(onClick = { submit() }, enabled = password.isNotEmpty() && waitMs <= 0L) {
        Text(stringResource(R.string.entry_gate_unlock))
    }
    TextButton(onClick = onForgot) { Text(stringResource(R.string.entry_gate_forgot)) }
}

private fun formatSeconds(ms: Long): String {
    val total = (ms + 999) / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

/** Two fields and a button: choose a new opening password. [onSaved] runs once it is stored. */
@Composable
fun NewPasswordForm(onSaved: () -> Unit) {
    val context = LocalContext.current
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    val field = Modifier.fillMaxWidth()

    fun save() {
        error = when {
            !EntryLock.isValidPassword(first) -> R.string.entry_password_short
            first != second -> R.string.entry_password_mismatch
            !SecurityManager.setEntryPassword(context, first) -> R.string.entry_password_refused
            else -> null
        }
        if (error == null) onSaved()
    }

    OutlinedTextField(
        value = first,
        onValueChange = { first = it.take(EntryLock.MAX_LENGTH); error = null },
        label = { Text(stringResource(R.string.entry_gate_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        modifier = field
    )
    OutlinedTextField(
        value = second,
        onValueChange = { second = it.take(EntryLock.MAX_LENGTH); error = null },
        label = { Text(stringResource(R.string.entry_gate_password_again)) },
        singleLine = true,
        isError = error != null,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { save() }),
        modifier = field
    )
    error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
    Button(onClick = { save() }, enabled = first.isNotEmpty() && second.isNotEmpty()) {
        Text(stringResource(R.string.entry_gate_save))
    }
}

/** Choose a new opening password from Settings. The caller has already asked for the recovery code. */
@Composable
fun ChangePasswordDialog(onDone: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.entry_change_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NewPasswordForm(onSaved = onDone)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** Forgot the opening password: the recovery code clears it, then a new one is chosen at the gate. */
@Composable
private fun ForgotPasswordDialog(onCleared: () -> Unit, onDismiss: () -> Unit) {
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
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (wrong) Text(stringResource(R.string.entry_gate_wrong), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (SecurityManager.validateRecoveryCode(context, code)) {
                    SecurityManager.clearEntryPassword(context)
                    onCleared()
                } else {
                    wrong = true
                }
            }, enabled = code.isNotEmpty()) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
