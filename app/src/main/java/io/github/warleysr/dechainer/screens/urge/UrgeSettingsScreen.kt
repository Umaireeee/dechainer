package io.github.warleysr.dechainer.screens.urge

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.ai.AiSettings
import io.github.warleysr.dechainer.ai.Provider
import io.github.warleysr.dechainer.urge.DeepDiveScheduler
import io.github.warleysr.dechainer.urge.UrgeSettings
import kotlin.concurrent.thread

/**
 * Urge and AI (blueprint D4, D5 and section 8): the personal reason, the one person to call, and the
 * AI provider, key, model and consent.
 */
@Composable
fun UrgeSettingsScreen() {
    val context = LocalContext.current
    val ai = remember { AiSettings(context) }
    val urge = remember { UrgeSettings(context) }

    var message by remember { mutableStateOf<Int?>(null) }

    var reason by remember { mutableStateOf(urge.reason) }
    var contactName by remember { mutableStateOf(urge.contact.name) }
    var contactNumber by remember { mutableStateOf(urge.contact.number) }

    var provider by remember { mutableStateOf(ai.provider) }
    var keyInput by remember { mutableStateOf("") }
    var keySaved by remember { mutableStateOf(ai.key.isNotBlank()) }
    var model by remember { mutableStateOf(ai.modelOverride) }
    var customBase by remember { mutableStateOf(ai.customBase) }
    var consent by remember { mutableStateOf(ai.consent) }

    // A new key or a new consent may be all a waiting deep dive needed: queue the retry.
    fun retryWaiting() {
        thread {
            DeepDiveScheduler.enqueueIfPending(context, replace = true)
            io.github.warleysr.dechainer.report.ReportScheduler.ensureQueued(context)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }

        // ---- the reason ----
        Header(R.string.urge_reason_section)
        Hint(R.string.urge_reason_hint)
        OutlinedTextField(
            value = reason,
            onValueChange = { reason = it },
            label = { Text(stringResource(R.string.urge_reason_field)) },
            modifier = Modifier.fillMaxWidth()
        )
        SaveButton {
            urge.setReason(reason)
            message = R.string.urge_saved
            reason = urge.reason
        }

        // ---- the person to call ----
        Spacer(Modifier.height(16.dp))
        Header(R.string.urge_contact_section)
        Hint(R.string.urge_contact_hint)
        OutlinedTextField(
            value = contactName,
            onValueChange = { contactName = it },
            label = { Text(stringResource(R.string.urge_contact_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = contactNumber,
            onValueChange = { contactNumber = it },
            label = { Text(stringResource(R.string.urge_contact_number)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )
        SaveButton {
            urge.setContact(contactName, contactNumber)
            message = R.string.urge_saved
            contactName = urge.contact.name
            contactNumber = urge.contact.number
        }

        // ---- the AI ----
        Spacer(Modifier.height(16.dp))
        Header(R.string.ai_section)
        Hint(R.string.ai_privacy)
        Text(stringResource(R.string.ai_provider), style = MaterialTheme.typography.labelLarge)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Provider.entries.forEach { p ->
                FilterChip(
                    selected = provider == p,
                    onClick = {
                        ai.setProvider(p)
                        provider = p
                        model = ai.modelOverride
                        consent = ai.consent
                        message = null
                    },
                    label = { Text(p.label) }
                )
            }
        }
        if (provider.keyHint.isNotEmpty()) {
            Text(
                stringResource(R.string.ai_key_get, provider.keyHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text(stringResource(R.string.ai_key_label)) },
            supportingText = { Text(stringResource(if (keySaved) R.string.ai_key_saved else R.string.ai_key_none)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        SaveButton {
            when (ai.saveKey(keyInput)) {
                AiSettings.KeySave.SAVED -> {
                    keyInput = ""
                    keySaved = ai.key.isNotBlank()
                    // A detected key can change the provider under us: keep the chip in step.
                    provider = ai.provider
                    consent = ai.consent
                    message = R.string.urge_saved
                    retryWaiting()
                }
                AiSettings.KeySave.PROVIDER_NEEDED -> {
                    keyInput = ""
                    keySaved = ai.key.isNotBlank()
                    message = R.string.ai_provider_required
                }
                AiSettings.KeySave.CANNOT_ENCRYPT -> message = R.string.ai_key_cannot_encrypt
            }
        }
        if (provider == Provider.CUSTOM) {
            OutlinedTextField(
                value = customBase,
                onValueChange = { customBase = it },
                label = { Text(stringResource(R.string.ai_base_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
        }
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text(stringResource(R.string.ai_model_label)) },
            supportingText = { if (provider.defaultModel.isNotEmpty()) Text(stringResource(R.string.ai_model_default, provider.defaultModel)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        SaveButton {
            // Reject a plain http address here, up front (blueprint 8), not only when a call is tried.
            if (provider == Provider.CUSTOM) {
                when (io.github.warleysr.dechainer.ai.AiClient.addressProblem(customBase)) {
                    io.github.warleysr.dechainer.ai.AiError.INSECURE_URL -> { message = R.string.ai_base_insecure; return@SaveButton }
                    null -> Unit
                    else -> { message = R.string.ai_base_bad; return@SaveButton }
                }
            }
            ai.setModel(model)
            if (provider == Provider.CUSTOM) ai.setCustomBase(customBase)
            message = R.string.urge_saved
            consent = ai.consent
            retryWaiting()
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(R.string.ai_consent_label, provider.label), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.ai_consent_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = consent,
                onCheckedChange = { agreed ->
                    ai.setConsent(agreed)
                    consent = agreed
                    message = null
                    if (agreed) retryWaiting()
                }
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Header(res: Int) {
    Text(
        stringResource(res).uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun Hint(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SaveButton(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.urge_save)) }
}
