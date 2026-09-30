package io.github.warleysr.dechainer.screens.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R

/** What a screen is, what it does, and how it works — shown from the ⓘ in the top bar. */
data class ScreenInfo(
    @StringRes val title: Int,
    @StringRes val what: Int,
    @StringRes val does: Int,
    @StringRes val how: Int
)

object ScreenInfos {
    /** The explainer for [screen], or null for screens that need none. */
    fun forScreen(screen: String): ScreenInfo? = when (screen) {
        "focus", "focus_log" -> ScreenInfo(R.string.focus_tab, R.string.info_focus_what, R.string.info_focus_does, R.string.info_focus_how)
        "apps" -> ScreenInfo(R.string.apps, R.string.info_apps_what, R.string.info_apps_does, R.string.info_apps_how)
        "schedules", "schedule_editor" -> ScreenInfo(R.string.schedules, R.string.info_schedules_what, R.string.info_schedules_does, R.string.info_schedules_how)
        "config" -> ScreenInfo(R.string.settings, R.string.info_config_what, R.string.info_config_does, R.string.info_config_how)
        "restrictions" -> ScreenInfo(R.string.protections, R.string.info_restrictions_what, R.string.info_restrictions_does, R.string.info_restrictions_how)
        "impulse_lock" -> ScreenInfo(R.string.impulse_lock, R.string.info_impulse_what, R.string.info_impulse_does, R.string.info_impulse_how)
        "setup_device_owner" -> ScreenInfo(R.string.info_owner_title, R.string.info_owner_what, R.string.info_owner_does, R.string.info_owner_how)
        else -> null
    }
}

/** The ⓘ button. Shows nothing for screens without an explainer. */
@Composable
fun ScreenInfoButton(screen: String) {
    val info = ScreenInfos.forScreen(screen) ?: return
    var open by rememberSaveable(screen) { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.info_button))
    }
    if (open) ScreenInfoDialog(info) { open = false }
}

@Composable
private fun ScreenInfoDialog(info: ScreenInfo, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(info.title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Section(R.string.info_heading_what, info.what)
                Spacer(Modifier.height(16.dp))
                Section(R.string.info_heading_does, info.does)
                Spacer(Modifier.height(16.dp))
                Section(R.string.info_heading_how, info.how)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.info_got_it)) } }
    )
}

@Composable
private fun Section(@StringRes heading: Int, @StringRes body: Int) {
    Text(
        stringResource(heading).uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
