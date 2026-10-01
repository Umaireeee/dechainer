package io.github.warleysr.dechainer.screens.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.security.AppLockKind
import io.github.warleysr.dechainer.security.AppLockRules
import kotlinx.coroutines.delay

/**
 * Where the owner types a PIN or draws a pattern, for the app lock's unlock screen and its settings.
 * The PIN is kept here while it is typed and handed over once, when the owner confirms it; the
 * pattern is handed over when the finger lifts. [onTooShort] says a pattern had fewer dots than the
 * minimum, which is never counted as a wrong try. [enabled] is false while the app is making the
 * owner wait.
 */
@Composable
fun SecretEntry(
    kind: AppLockKind,
    enabled: Boolean,
    onSecret: (String) -> Unit,
    onTooShort: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (kind) {
        AppLockKind.PIN -> PinPad(enabled, onSecret, modifier)
        AppLockKind.PATTERN -> PatternPad(enabled, { dots ->
            if (dots.size >= AppLockRules.PATTERN_MIN) onSecret(AppLockRules.patternSecret(dots)) else onTooShort()
        }, modifier)
    }
}

// ---- PIN ----

@Composable
private fun PinPad(enabled: Boolean, onSubmit: (String) -> Unit, modifier: Modifier = Modifier) {
    var pin by remember { mutableStateOf("") }
    val digits = pin.length
    val okEnabled = enabled && digits >= AppLockRules.PIN_MIN
    val typed = stringResource(R.string.applock_pin_typed, digits)

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // The dots show how many digits are in, never which.
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.height(24.dp).semantics { contentDescription = typed }
        ) {
            val shown = maxOf(digits, AppLockRules.PIN_MIN)
            repeat(shown) { i ->
                Box(
                    Modifier.size(14.dp).clip(CircleShape)
                        .background(if (i < digits) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("<", "0", "ok"))
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { key ->
                    when (key) {
                        "<" -> PadKey(stringResource(R.string.applock_backspace), enabled && digits > 0, icon = Icons.AutoMirrored.Outlined.Backspace) { pin = pin.dropLast(1) }
                        "ok" -> PadKey(stringResource(R.string.applock_confirm), okEnabled, emphasis = true, icon = Icons.Outlined.Check) {
                            val secret = pin
                            pin = ""
                            onSubmit(secret)
                        }
                        else -> PadKey(key, enabled && digits < AppLockRules.PIN_MAX, label = key) { pin += key }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PadKey(
    description: String,
    enabled: Boolean,
    emphasis: Boolean = false,
    label: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    val container = if (emphasis && enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (emphasis && enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(container.copy(alpha = if (enabled) 1f else 0.4f))
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val tint = content.copy(alpha = if (enabled) 1f else 0.4f)
        if (label != null) Text(label, style = MaterialTheme.typography.headlineSmall, color = tint)
        if (icon != null) Icon(icon, contentDescription = null, tint = tint)
    }
}

// ---- pattern ----

@Composable
private fun PatternPad(enabled: Boolean, onDone: (List<Int>) -> Unit, modifier: Modifier = Modifier) {
    var path by remember { mutableStateOf(emptyList<Int>()) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    var drawing by remember { mutableStateOf(false) }
    var doneTick by remember { mutableIntStateOf(0) }
    val onDoneNow by rememberUpdatedState(onDone)
    val canDraw by rememberUpdatedState(enabled)

    // The finished line stays a moment so the owner sees what was drawn, then clears.
    LaunchedEffect(doneTick) {
        if (doneTick > 0) {
            delay(350)
            if (!drawing) path = emptyList()
        }
    }

    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outline
    val description = stringResource(R.string.applock_pattern_area)

    Canvas(
        modifier = modifier
            .size(PATTERN_SIZE)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!canDraw) return@awaitEachGesture
                    val cell = size.width / AppLockRules.GRID.toFloat()
                    val reach = cell * 0.36f

                    fun hit(p: Offset) {
                        for (i in 0 until AppLockRules.GRID * AppLockRules.GRID) {
                            val c = Offset((i % AppLockRules.GRID + 0.5f) * cell, (i / AppLockRules.GRID + 0.5f) * cell)
                            if ((p - c).getDistance() <= reach) { path = AppLockRules.extend(path, i); return }
                        }
                    }

                    drawing = true
                    path = emptyList()
                    finger = down.position
                    hit(down.position)
                    drag(down.id) { change ->
                        change.consume()
                        finger = change.position
                        hit(change.position)
                    }
                    drawing = false
                    finger = null
                    val finished = path
                    doneTick++
                    if (finished.isNotEmpty()) onDoneNow(finished)
                }
            }
    ) {
        val cell = size.width / AppLockRules.GRID
        fun center(i: Int) = Offset((i % AppLockRules.GRID + 0.5f) * cell, (i / AppLockRules.GRID + 0.5f) * cell)
        val line = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
        path.zipWithNext().forEach { (a, b) -> drawLine(on, center(a), center(b), strokeWidth = line.width, cap = StrokeCap.Round) }
        val f = finger
        val last = path.lastOrNull()
        if (f != null && last != null) drawLine(on.copy(alpha = 0.5f), center(last), f, strokeWidth = line.width, cap = StrokeCap.Round)
        for (i in 0 until AppLockRules.GRID * AppLockRules.GRID) {
            val inPath = i in path
            drawCircle(if (inPath) on else off, radius = if (inPath) 14.dp.toPx() else 9.dp.toPx(), center = center(i))
            if (inPath) drawCircle(on.copy(alpha = 0.25f), radius = 26.dp.toPx(), center = center(i))
        }
    }
}

private val PATTERN_SIZE = 280.dp
