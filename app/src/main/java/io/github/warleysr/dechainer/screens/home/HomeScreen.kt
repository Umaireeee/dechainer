package io.github.warleysr.dechainer.screens.home

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.lock.BrickStatus
import io.github.warleysr.dechainer.screens.common.RepeatWhileVisible
import io.github.warleysr.dechainer.viewmodels.Route
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Home (blueprint 6.1): the time, and one button. A tap on the clock or a swipe up opens the menu.
 * While a brick holds the phone it is the same screen, with the end time and the reason in small text
 * under the clock, and the Urge button stays. [menuEnabled] is false then: nothing in the menu changes
 * a lock, so nothing is offered. Nothing here competes with the clock.
 */
@Composable
fun HomeScreen(
    lock: BrickStatus?,
    menuEnabled: Boolean,
    onUrge: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(TrustedClock.now(context)) }
    RepeatWhileVisible(1000) { now = TrustedClock.now(context) }

    val zoned = Instant.ofEpochMilli(now).atZone(TrustedClock.zone())
    val is24 = DateFormat.is24HourFormat(context)
    val clockFormat = remember(is24) { DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm") }
    val clock = clockFormat.format(zoned)
    val date = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM") }.format(zoned)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(menuEnabled) {
                if (!menuEnabled) return@pointerInput
                val threshold = 80.dp.toPx()
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragCancel = { total = 0f },
                    onDragEnd = { if (total < -threshold) onMenu() },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        total += dy
                    }
                )
            }
    ) {
        // A single warm pool of light behind the clock: the only ornament, and it never moves.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(460.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), Color.Transparent)
                    ),
                    shape = CircleShape
                )
        )

        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                date.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.18.em),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                clock,
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 104.sp, lineHeight = 112.sp),
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .clickable(
                        enabled = menuEnabled,
                        onClickLabel = stringResource(R.string.home_open_menu),
                        role = Role.Button,
                        onClick = onMenu
                    )
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
            if (lock != null) {
                Spacer(Modifier.height(22.dp))
                LockNote(lock)
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = onUrge,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.widthIn(min = 220.dp).heightIn(min = 60.dp)
            ) {
                Icon(Icons.Outlined.WbTwilight, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.urge_button), style = MaterialTheme.typography.titleMedium)
            }
            if (menuEnabled) {
                Spacer(Modifier.height(18.dp))
                Text(
                    stringResource(R.string.home_menu_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** The end time and the reason, quiet under the clock while a brick holds the phone. */
@Composable
private fun LockNote(lock: BrickStatus) {
    val until = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .format(Instant.ofEpochMilli(lock.endsAt).atZone(TrustedClock.zone()))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Text(
            stringResource(R.string.locked_until, until),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.home_reason_urge),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

/** The menu (blueprint 6.1): Urge first, then [routes]. A bottom sheet, so it comes from where the thumb is. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(
    routes: List<Route>,
    onUrge: () -> Unit,
    onRoute: (Route) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            MenuRow(Icons.Outlined.WbTwilight, stringResource(R.string.menu_urge), onUrge)
            if (routes.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                routes.forEach { route ->
                    route.icon?.let { icon -> MenuRow(icon, stringResource(route.menuTitle)) { onRoute(route) } }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
