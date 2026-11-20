package io.github.warleysr.dechainer.screens.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.security.SecurityManager
import kotlinx.coroutines.delay

@Composable
fun RecoveryConfirmDialog(
    onConfirm: (String) -> Boolean,
    onDismiss: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var lockoutSeconds by remember {
        mutableIntStateOf(((SecurityManager.getRecoveryLockoutRemainingMillis() + 999L) / 1000L).toInt())
    }
    val sessionActive = SecurityManager.isSessionActive()
    LaunchedEffect(sessionActive) {
        if (sessionActive) onConfirm("")
    }
    if (sessionActive) return

    LaunchedEffect(lockoutSeconds) {
        if (lockoutSeconds > 0) {
            delay(1000L)
            lockoutSeconds = ((SecurityManager.getRecoveryLockoutRemainingMillis() + 999L) / 1000L).toInt()
        }
    }

    val context = LocalContext.current
    val shuffleKeyboard = remember { SecurityManager.isShuffleKeyboardEnabled(context) }

    if (shuffleKeyboard) {
        ShuffleKeyboardRecoveryDialog(
            lockoutSeconds = lockoutSeconds,
            onConfirm = { entered ->
                val ok = onConfirm(entered)
                if (!ok) {
                    lockoutSeconds = ((SecurityManager.getRecoveryLockoutRemainingMillis() + 999L) / 1000L).toInt()
                }
                ok
            },
            onDismiss = onDismiss
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.enter_recovery_code)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { input ->
                            val clean = input.uppercase().filter { it in 'A'..'Z' }.take(16)
                            code = clean
                            isError = false
                        },
                        label = { Text(stringResource(R.string.recovery_code_hint)) },
                        isError = isError || lockoutSeconds > 0,
                        supportingText = {
                            if (isError || lockoutSeconds > 0) {
                                val err = stringResource(R.string.invalid_recovery_code)
                                Text(
                                    if (lockoutSeconds > 0) "$err (${lockoutSeconds}s)" else err,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                Text("${code.length}/16")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = code.length == 16 && lockoutSeconds == 0,
                    onClick = {
                        if (!onConfirm(code)) {
                            isError = true
                            lockoutSeconds = ((SecurityManager.getRecoveryLockoutRemainingMillis() + 999L) / 1000L).toInt()
                        }
                    }
                ) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/** All uppercase letters that can appear in the recovery passphrase. */
private val ALPHABET = ('A'..'Z').toList()

/**
 * Recovery dialog that presents a 26-letter shuffle keyboard.
 * All 26 letters are displayed independently of the stored secret and reshuffled after every tap.
 */
@Composable
private fun ShuffleKeyboardRecoveryDialog(
    lockoutSeconds: Int,
    onConfirm: (String) -> Boolean,
    onDismiss: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(ALPHABET.shuffled()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.enter_recovery_code)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Progress indicator
                Text(
                    text = "${code.length}/16",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isError || lockoutSeconds > 0)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isError || lockoutSeconds > 0) {
                    val err = stringResource(R.string.invalid_recovery_code)
                    Text(
                        text = if (lockoutSeconds > 0) "$err (${lockoutSeconds}s)" else err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // 26-letter grid: 4 rows of 6 + 1 centered row of 2
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (row in 0 until 5) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (row == 4) {
                                Spacer(modifier = Modifier.weight(2f))
                                for (col in 0 until 2) {
                                    val letter = grid[24 + col]
                                    OutlinedButton(
                                        enabled = lockoutSeconds == 0,
                                        onClick = {
                                            if (code.length < 16) {
                                                code += letter
                                                isError = false
                                                grid = ALPHABET.shuffled()
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(42.dp),
                                        contentPadding = PaddingValues(0.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            MaterialTheme.colorScheme.outline
                                        )
                                    ) {
                                        Text(
                                            text = letter.toString(),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.weight(2f))
                            } else {
                                for (col in 0 until 6) {
                                    val letter = grid[row * 6 + col]
                                    OutlinedButton(
                                        enabled = lockoutSeconds == 0,
                                        onClick = {
                                            if (code.length < 16) {
                                                code += letter
                                                isError = false
                                                grid = ALPHABET.shuffled()
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(42.dp),
                                        contentPadding = PaddingValues(0.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            MaterialTheme.colorScheme.outline
                                        )
                                    ) {
                                        Text(
                                            text = letter.toString(),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Backspace button
                if (code.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            code = code.dropLast(1)
                            isError = false
                            grid = ALPHABET.shuffled()
                        }
                    ) {
                        Text(stringResource(R.string.shuffle_keyboard_backspace))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = code.length == 16 && lockoutSeconds == 0,
                onClick = {
                    if (!onConfirm(code))
                        isError = true
                }
            ) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
