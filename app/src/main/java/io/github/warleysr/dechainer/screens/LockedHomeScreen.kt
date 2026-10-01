package io.github.warleysr.dechainer.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.lock.BrickStatus
import io.github.warleysr.dechainer.lock.LockMode
import io.github.warleysr.dechainer.screens.common.RepeatWhileVisible
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * What the phone shows while an urge lock or a punishment day holds it: when it ends, and one calm
 * sentence about why. Nothing to tap: it has no settings and no way out. The urge lock also shows what
 * is left, because ten minutes are easier to wait out when they are counted.
 */
@Composable
fun LockedHomeScreen(status: BrickStatus, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(TrustedClock.now(context)) }
    RepeatWhileVisible(1000) { now = TrustedClock.now(context) }

    val until = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .format(Instant.ofEpochMilli(status.endsAt).atZone(TrustedClock.zone()))
    val urge = status.primary == LockMode.URGE_LOCK

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.padding(bottom = 16.dp))
        Text(
            stringResource(if (urge) R.string.locked_urge_title else R.string.locked_punishment_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.locked_until, until),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        if (urge) {
            val left = ((status.endsAt - now) / 1000).coerceAtLeast(0L)
            Spacer(Modifier.height(16.dp))
            Text(
                "%02d:%02d".format(left / 60, left % 60),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(if (urge) R.string.locked_urge_body else R.string.locked_punishment_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}
