package io.github.warleysr.dechainer.screens.urge

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.screens.common.RepeatWhileVisible
import io.github.warleysr.dechainer.ui.theme.Motion
import io.github.warleysr.dechainer.urge.Breathing
import io.github.warleysr.dechainer.urge.UrgeFlowRules
import io.github.warleysr.dechainer.urge.UrgeScreen
import io.github.warleysr.dechainer.urge.UrgeSettings
import io.github.warleysr.dechainer.viewmodels.UrgeViewModel

/**
 * The urge flow's screen (blueprint 6.2): the breathing, for the chosen length, and then a quiet
 * close. There is no choice, no writing and no questions. There is no exit control during the
 * breathing. Which screen shows comes from the stored entry and the time, never from where the
 * owner happened to be, so the flow opens back into the same step after the app is killed.
 */
@Composable
fun UrgeFlowHost(vm: UrgeViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(TrustedClock.now(context)) }
    RepeatWhileVisible(1000) { now = TrustedClock.now(context) }
    val settings = remember { UrgeSettings(context) }

    Box(modifier = modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        val entry = state.entry ?: return@Box
        when (UrgeFlowRules.screenFor(entry, now)) {
            UrgeScreen.BREATHING -> BreathingScreen(
                startsAt = entry.lockStartedAt ?: entry.createdAt,
                endsAt = entry.lockEndedAt ?: now,
                reason = settings.reason
            )
            UrgeScreen.FINISHED -> CompletionScreen(onClose = vm::finish)
        }
    }
}

// ---- breathing ----

/**
 * A circle that grows for 4 seconds and shrinks for 6, a ring showing the time left, and one prompt
 * per minute, cycling. The time comes from the trusted clock read once, plus the phone's own running
 * time since, so a changed wall clock cannot make the circle jump.
 */
@Composable
private fun BreathingScreen(startsAt: Long, endsAt: Long, reason: String) {
    val context = LocalContext.current
    val prompts = stringArrayResource(R.array.urge_breath_prompts)
    val trustedAtStart = remember { TrustedClock.now(context) }
    val realAtStart = remember { SystemClock.elapsedRealtime() }
    val total = (endsAt - startsAt).coerceAtLeast(0L)

    // Ticks every frame; only the drawing and the small derived values below read it.
    var frame by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { frame = it } }

    val elapsed by remember {
        derivedStateOf {
            val base = trustedAtStart - startsAt
            if (frame < 0L) base else base + (SystemClock.elapsedRealtime() - realAtStart)
        }
    }
    val phase by remember { derivedStateOf { Breathing.phaseAt(elapsed) } }
    val secondsLeft by remember { derivedStateOf { ((total - elapsed) / 1000).coerceAtLeast(0L) } }
    val promptIndex by remember { derivedStateOf { Breathing.promptIndex(elapsed) } }

    val ringColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val circleColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val leftText = "%d:%02d".format(secondsLeft / 60, secondsLeft % 60)
    val leftDescription = stringResource(R.string.urge_breath_left, leftText)

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(280.dp)) {
                val stroke = 6.dp.toPx()
                val radius = size.minDimension / 2f - stroke
                drawCircle(trackColor, radius = radius, style = Stroke(stroke))
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * Breathing.ringLeft(elapsed, total),
                    useCenter = false,
                    topLeft = Offset(stroke, stroke),
                    size = Size(size.width - 2 * stroke, size.height - 2 * stroke),
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
                val inner = (radius - 3 * stroke) * (0.35f + 0.65f * Breathing.circleAt(elapsed))
                drawCircle(circleColor, radius = inner)
            }
            Text(
                stringResource(if (phase == Breathing.Phase.INHALE) R.string.urge_breath_in else R.string.urge_breath_out),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            leftText,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = leftDescription }
        )
        Spacer(Modifier.height(32.dp))
        AnimatedContent(
            targetState = promptIndex,
            transitionSpec = { fadeIn(tween(Motion.SCREEN_MS, delayMillis = 60)) togetherWith fadeOut(tween(Motion.SCREEN_OUT_MS)) },
            label = "breath-prompt",
            modifier = Modifier.heightIn(min = 120.dp)
        ) { index ->
            val text = if (Breathing.usesReason(index, reason)) stringResource(R.string.urge_breath_reason, reason)
            else prompts[index.coerceIn(0, prompts.lastIndex)]
            Text(
                text,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

// ---- the quiet close ----

@Composable
private fun CompletionScreen(onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            stringResource(R.string.urge_complete_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.urge_complete_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(stringResource(R.string.urge_complete_close))
        }
    }
}

