package io.github.warleysr.urgejournal

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull
import java.time.format.TextStyle as DayStyle
import java.util.Locale

/**
 * Looks up `<prefix><name>` in strings.xml, falling back to the raw name if it is missing. Looked
 * up once per screen, not on every recomposition: a name lookup by reflection is not free.
 */
@Composable
fun label(prefix: String, key: Enum<*>): String {
    val ctx = LocalContext.current
    return remember(ctx, prefix, key) {
        val id = ctx.resources.getIdentifier(prefix + key.name.lowercase(), "string", ctx.packageName)
        if (id != 0) ctx.getString(id) else key.name
    }
}

/** How long the ember has to be held before it starts a ride, so a bump in a pocket never does. */
private const val HOLD_MS = 700L

// How a press on the ember ended, if it did within HOLD_MS.
private const val STILL_DOWN = 0
private const val LIFTED = 1
private const val CANCELLED = 2

/**
 * The one big call to action: a slowly breathing ember. Calm on purpose; nothing flashes. It starts
 * on a hold, not a tap: a quick tap only calls [onTap], which the screen uses to say "hold it".
 */
@Composable
fun Ember(text: String, onTap: () -> Unit, onStart: () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val tap by rememberUpdatedState(onTap)
    val start by rememberUpdatedState(onStart)
    val transition = rememberInfiniteTransition(label = "ember")
    // Read inside graphicsLayer, so each frame redraws the layer and nothing is recomposed.
    val scale = transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "emberScale"
    )
    // A soft glow behind the ember, so it reads as light rather than a flat disc.
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(290.dp)) {
        Box(
            Modifier
                .size(290.dp)
                .background(Brush.radialGradient(listOf(Color(0x40D9A55B), Color(0x00D9A55B))))
        )
        Box(
            Modifier
                .size(196.dp)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFFF1D6A0), Color(0xFFD9A55B), Color(0xFFAE7A34))))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        // Lifted early is a tap; still down after HOLD_MS starts the ride; a scroll that
                        // takes the gesture over (the page moves under the finger) is neither.
                        var ended = STILL_DOWN
                        withTimeoutOrNull(HOLD_MS) {
                            ended = if (waitForUpOrCancellation() != null) LIFTED else CANCELLED
                        }
                        when (ended) {
                            LIFTED -> tap()
                            STILL_DOWN -> {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                start()
                                waitForUpOrCancellation()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text,
                color = Color(0xFF2A1F10),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(28.dp)
            )
        }
    }
}

/** An answer to tap. Bordered rather than filled, so a screen of them stays quiet. */
@Composable
fun OptionCard(text: String, selected: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp)
        )
    }
}

/** A label with a switch on the right; the whole row is the tap target. */
@Composable
fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = null)
    }
}

/** A quiet capitalised heading for a block of content. */
@Composable
fun Eyebrow(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp)
    )
}

/** A soft card with a hairline edge. */
@Composable
fun Panel(highlight: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        // Raised a step above the page, so a panel reads as a surface and not as an outline.
        color = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            if (highlight) MaterialTheme.colorScheme.primary.copy(alpha = 0.30f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

/** A list of short points with a small ember bullet. */
@Composable
fun Bullets(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .padding(top = 9.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Text(item, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Steps to do in order, each with a numbered ember disc. */
@Composable
fun NumberedSteps(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEachIndexed { i, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Text(item, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(top = 2.dp))
            }
        }
    }
}

@Composable
fun Stat(value: String, caption: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Bars at a glance: amber for urges ridden out, terracotta for the ones given in to. With [onPick]
 * a bar can be tapped, and the [selected] one is drawn brighter. [labels] replaces the weekday
 * letters, which is how the twelve-week view shows the day each week starts.
 */
@Composable
fun WeekBars(
    bars: List<Insights.DayBar>,
    labels: List<String>? = null,
    selected: Int? = null,
    onPick: ((Int) -> Unit)? = null
) {
    val max = (bars.maxOfOrNull { it.resisted + it.gaveIn + it.open } ?: 0).coerceAtLeast(1)
    val ok = MaterialTheme.colorScheme.primary
    val bad = MaterialTheme.colorScheme.error
    val track = MaterialTheme.colorScheme.surfaceVariant
    val picked = MaterialTheme.colorScheme.outlineVariant
    val pending = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val tap = if (onPick != null) {
        Modifier.pointerInput(bars.size) {
            detectTapGestures { offset ->
                val slot = size.width.toFloat() / bars.size.coerceAtLeast(1)
                onPick((offset.x / slot).toInt().coerceIn(0, (bars.size - 1).coerceAtLeast(0)))
            }
        }
    } else Modifier
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(88.dp).then(tap)) {
            val gap = (if (bars.size > 10) 5.dp else 10.dp).toPx()
            val n = bars.size.coerceAtLeast(1)
            val w = (size.width - gap * (n - 1)) / n
            val radius = CornerRadius(8.dp.toPx().coerceAtMost(w / 2))
            bars.forEachIndexed { i, b ->
                val x = i * (w + gap)
                drawRoundRect(if (i == selected) picked else track, Offset(x, 0f), Size(w, size.height), radius)
                val total = b.resisted + b.gaveIn
                if (b.open > 0) {
                    val ho = size.height * (total + b.open) / max
                    drawRoundRect(pending, Offset(x, size.height - ho), Size(w, ho), radius)
                }
                if (total > 0) {
                    val h = size.height * total / max
                    drawRoundRect(bad, Offset(x, size.height - h), Size(w, h), radius)
                    if (b.resisted > 0) {
                        val hr = h * b.resisted / total
                        drawRoundRect(ok, Offset(x, size.height - hr), Size(w, hr), radius)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            bars.forEachIndexed { i, b ->
                Text(
                    labels?.getOrNull(i) ?: b.date.dayOfWeek.getDisplayName(DayStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** Progress as dots: filled for answered, ringed for where you are. */
@Composable
fun Dots(done: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            Box(
                Modifier
                    .size(if (i == done) 10.dp else 7.dp)
                    .clip(CircleShape)
                    .background(
                        if (i <= done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    )
            )
        }
    }
}

/** The waiting state while the deep dive is written: a slow pulse and one honest line. */
@Composable
fun Breathing(text: String) {
    val transition = rememberInfiniteTransition(label = "breath")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathAlpha"
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier
                .size(14.dp)
                .scale(0.8f + alpha * 0.4f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
        )
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A slow circle to breathe with: it grows for 4 seconds and shrinks for 6, and holds the time left
 * in its middle. It only moves; nothing flashes.
 */
@Composable
fun BreathCircle(inhale: Boolean, centerText: String, caption: String) {
    val transition = rememberInfiniteTransition(label = "breathCircle")
    val scale = transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 0.72f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 10_000
                0.72f at 0
                1f at 4_000
                0.72f at 10_000
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "breathScale"
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(260.dp)) {
            Box(
                Modifier
                    .size(260.dp)
                    .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0x55D9A55B), Color(0x11D9A55B))))
            )
            Box(
                Modifier
                    .size(170.dp)
                    .graphicsLayer {
                        val inner = 0.85f + scale.value * 0.15f
                        scaleX = inner
                        scaleY = inner
                    }
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFFF1D6A0), Color(0xFFD9A55B), Color(0xFFAE7A34)))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    centerText,
                    color = Color(0xFF2A1F10),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
        Text(
            caption,
            style = MaterialTheme.typography.titleLarge,
            color = if (inhale) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
