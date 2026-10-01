package io.github.warleysr.dechainer.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.lock.SettingsFreeze
import io.github.warleysr.dechainer.screens.common.RecoveryGateDialog
import io.github.warleysr.dechainer.screens.common.SecretEntry
import io.github.warleysr.dechainer.screens.common.rememberRecoveryGate
import io.github.warleysr.dechainer.security.AppLock
import io.github.warleysr.dechainer.security.AppLockKind
import io.github.warleysr.dechainer.security.AppLockRules
import io.github.warleysr.dechainer.security.UnlockResult
import kotlinx.coroutines.delay

private enum class Step { OVERVIEW, VERIFY_OLD, CHOOSE_KIND, FIRST, CONFIRM }

/**
 * Settings for the app lock. Turning it on is one tap and two entries of the same secret. Changing it
 * asks for the current one first. Turning it off loosens, so it goes through the recovery code. All
 * of it is refused on a punishment day, like every other setting.
 */
@Composable
fun AppLockSettingsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val gate = rememberRecoveryGate()
    val current = AppLock.kind(ctx)
    val frozen = SettingsFreeze.isFrozen(ctx)

    var step by remember { mutableStateOf(Step.OVERVIEW) }
    var newKind by remember { mutableStateOf(AppLockKind.PIN) }
    var first by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<Int?>(null) }
    var weak by remember { mutableStateOf(false) }
    var waitMs by remember { mutableLongStateOf(0L) }

    fun reset(to: Step = Step.OVERVIEW, msg: Int? = null) { step = to; first = ""; message = msg; weak = false }

    // While old-secret checks are being slowed down, count it out.
    LaunchedEffect(waitMs > 0L) {
        while (waitMs > 0L) { delay(1000); waitMs = AppLock.waitRemainingMs(ctx) }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (frozen) Text(stringResource(R.string.urge_frozen), color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())

        when (step) {
            Step.OVERVIEW -> {
                Text(stringResource(R.string.applock_explain), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(when (current) {
                        null -> R.string.applock_state_off
                        AppLockKind.PIN -> R.string.applock_state_pin
                        AppLockKind.PATTERN -> R.string.applock_state_pattern
                    }),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                )
                message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth()) }
                if (current == null) {
                    Button({ newKind = AppLockKind.PIN; reset(Step.FIRST) }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !frozen) { Text(stringResource(R.string.applock_set_pin)) }
                    OutlinedButton({ newKind = AppLockKind.PATTERN; reset(Step.FIRST) }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !frozen) { Text(stringResource(R.string.applock_set_pattern)) }
                } else {
                    Button({ reset(Step.VERIFY_OLD) }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !frozen) { Text(stringResource(R.string.applock_change)) }
                    OutlinedButton({ AppLock.lock() }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.applock_lock_now)) }
                    TextButton({
                        // Turning it off loosens: the recovery code first.
                        gate.run { reset(msg = if (AppLock.remove(ctx)) R.string.applock_removed else R.string.urge_frozen) }
                    }, enabled = !frozen) { Text(stringResource(R.string.applock_remove)) }
                }
                Text(stringResource(R.string.applock_tip), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
            }

            Step.VERIFY_OLD -> {
                val kind = current
                if (kind == null) { LaunchedEffect(Unit) { reset() }; return@Column }
                Text(stringResource(if (kind == AppLockKind.PIN) R.string.applock_enter_current_pin else R.string.applock_enter_current_pattern), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
                if (waitMs > 0L) {
                    val s = ((waitMs + 999) / 1000).toInt()
                    Text(stringResource(R.string.applock_wait, "%d:%02d".format(s / 60, s % 60)), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
                SecretEntry(kind, enabled = waitMs == 0L, onSecret = { secret ->
                    when (val r = AppLock.tryUnlock(ctx, secret)) {
                        UnlockResult.Unlocked -> reset(Step.CHOOSE_KIND)
                        is UnlockResult.Wrong -> { message = R.string.applock_wrong_short; waitMs = AppLock.waitRemainingMs(ctx) }
                        is UnlockResult.WaitFirst -> waitMs = r.waitMs
                    }
                }, onTooShort = { message = R.string.applock_too_short })
                TextButton({ reset() }) { Text(stringResource(R.string.cancel)) }
            }

            Step.CHOOSE_KIND -> {
                Text(stringResource(R.string.applock_choose_new), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Button({ newKind = AppLockKind.PIN; reset(Step.FIRST) }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.applock_set_pin)) }
                OutlinedButton({ newKind = AppLockKind.PATTERN; reset(Step.FIRST) }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.applock_set_pattern)) }
                TextButton({ reset() }) { Text(stringResource(R.string.cancel)) }
            }

            Step.FIRST, Step.CONFIRM -> {
                val confirming = step == Step.CONFIRM
                Text(
                    stringResource(when {
                        confirming && newKind == AppLockKind.PIN -> R.string.applock_confirm_pin
                        confirming -> R.string.applock_confirm_pattern
                        newKind == AppLockKind.PIN -> R.string.applock_new_pin
                        else -> R.string.applock_new_pattern
                    }),
                    style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center
                )
                Text(
                    stringResource(when {
                        message != null -> message!!
                        weak -> R.string.applock_weak
                        newKind == AppLockKind.PIN -> R.string.applock_pin_rules
                        else -> R.string.applock_pattern_rules
                    }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message != null || weak) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                Spacer(Modifier.height(4.dp))
                SecretEntry(newKind, enabled = true, onSecret = { secret ->
                    message = null
                    if (!confirming) {
                        val ok = if (newKind == AppLockKind.PIN) AppLockRules.validPin(secret) && !AppLockRules.weakPin(secret) else true
                        if (!ok) { weak = true } else { weak = false; first = secret; step = Step.CONFIRM }
                    } else if (secret != first) {
                        reset(Step.FIRST, R.string.applock_mismatch)
                    } else {
                        val saved = AppLock.set(ctx, newKind, secret)
                        reset(msg = if (saved) R.string.applock_saved else R.string.urge_frozen)
                    }
                }, onTooShort = { message = R.string.applock_too_short })
                TextButton({ reset() }) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
    RecoveryGateDialog(gate)
}
