package io.github.warleysr.urgejournal

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.format.TextStyle as DayStyle
import java.util.Locale

/** Looks up `<prefix><name>` in strings.xml, falling back to the raw name if it is missing. */
@Composable
fun label(prefix: String, key: Enum<*>): String {
    val ctx = LocalContext.current
    val id = ctx.resources.getIdentifier(prefix + key.name.lowercase(), "string", ctx.packageName)
    return if (id != 0) ctx.getString(id) else key.name
}

/** The one big call to action: a slowly breathing ember. Calm on purpose; nothing flashes. */
@Composable
fun Ember(text: String, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "ember")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "emberScale"
    )
    Box(
        Modifier
            .size(196.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFFEBC98F), Color(0xFFD9A55B), Color(0xFFB07C35))))
            .clickable(onClick = onClick),
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

/** An answer to tap. Bordered rather than filled, so a screen of them stays quiet. */
@Composable
fun OptionCard(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        )
    }
}

/** A quiet capitalised heading for a block of content. */
@Composable
fun Eyebrow(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

/** A soft card with a hairline edge. */
@Composable
fun Panel(highlight: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            if (highlight) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
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

@Composable
fun Stat(value: String, caption: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Seven days at a glance: amber for urges ridden out, terracotta for the ones given in to. */
@Composable
fun WeekBars(bars: List<Insights.DayBar>) {
    val max = (bars.maxOfOrNull { it.resisted + it.gaveIn } ?: 0).coerceAtLeast(1)
    val ok = MaterialTheme.colorScheme.primary
    val bad = MaterialTheme.colorScheme.error
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(88.dp)) {
            val gap = 10.dp.toPx()
            val n = bars.size.coerceAtLeast(1)
            val w = (size.width - gap * (n - 1)) / n
            val radius = CornerRadius(8.dp.toPx())
            bars.forEachIndexed { i, b ->
                val x = i * (w + gap)
                drawRoundRect(track, Offset(x, 0f), Size(w, size.height), radius)
                val total = b.resisted + b.gaveIn
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
            bars.forEach { b ->
                Text(
                    b.date.dayOfWeek.getDisplayName(DayStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
