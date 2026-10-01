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

@Composable
fun RecoveryConfirmDialog(
    onConfirm: (String) -> Boolean,
    onDismiss: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    if (SecurityManager.isSessionActive()) {
        onConfirm(code)
        return
    }

    val context = LocalContext.current
    val shuffleKeyboard = remember { SecurityManager.isShuffleKeyboardEnabled(context) }

    if (shuffleKeyboard) {
        ShuffleKeyboardRecoveryDialog(
            onConfirm = onConfirm,
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
                        isError = isError,
                        supportingText = {
                            if (isError) {
                                Text(
                                    recoveryErrorText(context),
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
                    enabled = code.length == 16,
                    onClick = {
                        if (!onConfirm(code)) {
                            isError = true
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
 * All 26 letters in a new random order. The recovery code is stored only as a hash now, so the
 * keyboard cannot know which letter comes next; it reshuffles after every tap instead.
 */
private fun buildGrid(): List<Char> = ALPHABET.shuffled()

private const val GRID_COLUMNS = 6
private const val GRID_ROWS = 5

/**
 * Recovery dialog that presents a shuffle keyboard.
 * Only one cell per row contains the character at the current code position.
 * The entire grid reshuffles after every tap.
 */
@Composable
private fun ShuffleKeyboardRecoveryDialog(
    onConfirm: (String) -> Boolean,
    onDismiss: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    var grid by remember { mutableStateOf(buildGrid()) }

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
                    color = if (isError)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isError) {
                    Text(
                        text = recoveryErrorText(LocalContext.current),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Every letter, reshuffled after each tap
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (row in 0 until GRID_ROWS) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            for (col in 0 until GRID_COLUMNS) {
                                val index = row * GRID_COLUMNS + col
                                if (index >= grid.size) {
                                    Spacer(Modifier.weight(1f))
                                    continue
                                }
                                val letter = grid[index]
                                OutlinedButton(
                                    onClick = {
                                        if (code.length < 16) {
                                            code += letter
                                            isError = false
                                            grid = buildGrid()
                                        }
                                    },
                                    modifier = Modifier.weight(1f).aspectRatio(1f),
                                    contentPadding = PaddingValues(0.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline
                                    )
                                ) {
                                    Text(
                                        text = letter.toString(),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium
                                    )
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
                            grid = buildGrid()
                        }
                    ) {
                        Text(stringResource(R.string.shuffle_keyboard_backspace))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = code.length == 16,
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

/** "Wrong code", or, once too many tries in a row have been wrong, how long the app now refuses to try. */
private fun recoveryErrorText(context: android.content.Context): String {
    val wait = SecurityManager.recoveryWaitMs(context)
    if (wait <= 0L) return context.getString(R.string.invalid_recovery_code)
    val s = ((wait + 999) / 1000).toInt()
    return context.getString(R.string.recovery_wait, "%d:%02d".format(s / 60, s % 60))
}
