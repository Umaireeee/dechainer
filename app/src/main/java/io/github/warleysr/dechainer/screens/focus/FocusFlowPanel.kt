package io.github.warleysr.dechainer.screens.focus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.Rules
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.focus.Flavor
import io.github.warleysr.dechainer.focus.FlowStage
import io.github.warleysr.dechainer.focus.FlowState
import io.github.warleysr.dechainer.focus.FocusFlow
import io.github.warleysr.dechainer.focus.FocusRunner
import io.github.warleysr.dechainer.lock.LockEngine
import io.github.warleysr.dechainer.screens.common.RepeatWhileVisible

/**
 * Whether [FocusFlowPanel] has something to show for [stage]. A usual block that is running shows the
 * ring and its controls instead, and a flow that is over shows nothing.
 */
fun flowPanelShows(stage: FlowStage): Boolean = stage != FlowStage.RUNNING && stage != FlowStage.DONE

/** The stages where the flow is waiting for the owner on a screen of its own: the full-screen activity shows these. */
fun flowAsksNow(stage: FlowStage): Boolean =
    stage == FlowStage.PROMPT || stage == FlowStage.CHECKIN || stage == FlowStage.FINAL_ASK

/** `m:ss`, or `h:mm:ss` from an hour up. Rounds up, so a countdown never shows 0:00 with time left. */
fun clockText(ms: Long): String {
    val total = ((ms.coerceAtLeast(0L)) + 999L) / 1000L
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * The focus flow's own screen (blueprint 6.3): the prompt before a scheduled session, the check-in,
 * the reset, the "not ready" message, the plain timer and a special session's countdown, and the
 * last question after the block. One column, one thing to do. Used by the Focus page and by the
 * full-screen activity that shows over the lock screen.
 */
@Composable
fun FocusFlowPanel(flow: FlowState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(TrustedClock.now(context)) }
    // The clock, once a second, only while on screen. It is also a wake-up: once a deadline has passed, the engine moves the flow on.
    RepeatWhileVisible(1000, key = flow.stage) {
        now = TrustedClock.now(context)
        val wake = FocusFlow.nextWake(flow)
        if (wake != null && wake <= now) LockEngine.requestSync(context)
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (flow.stage) {
            FlowStage.PROMPT -> PromptContent(flow, now)
            FlowStage.CHECKIN, FlowStage.FINAL_ASK -> AskContent(flow, finalQuestion = flow.stage == FlowStage.FINAL_ASK)
            FlowStage.RESET_MEDITATION -> {
                val into = (now - flow.stageStartedAt).coerceAtLeast(0L)
                val prompts = listOf(
                    R.string.focus_flow_reset_prompt_1, R.string.focus_flow_reset_prompt_2, R.string.focus_flow_reset_prompt_3,
                    R.string.focus_flow_reset_prompt_4, R.string.focus_flow_reset_prompt_5
                )
                val prompt = prompts[((into / 60_000L).toInt()).coerceIn(0, prompts.size - 1)]
                Title(stringResource(R.string.focus_flow_reset_meditation_title))
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(prompt),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                Countdown(flow.stageStartedAt + Rules.FOCUS_RESET_MEDITATION_MS - now)
            }
            FlowStage.RESET_OUTSIDE -> {
                Title(stringResource(R.string.focus_flow_reset_outside_title))
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.focus_flow_reset_outside_body),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                Countdown(flow.stageStartedAt + Rules.FOCUS_RESET_OUTSIDE_MS - now)
            }
            FlowStage.RESET_ASK -> {
                Title(stringResource(R.string.focus_flow_ready_title))
                Spacer(Modifier.height(32.dp))
                Column(
                    modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(onClick = { FocusRunner.ready(context, true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.focus_flow_ready_yes))
                    }
                    OutlinedButton(onClick = { FocusRunner.ready(context, false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.focus_flow_ready_no))
                    }
                }
            }
            FlowStage.NOT_READY -> Text(
                stringResource(R.string.focus_flow_not_ready),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center
            )
            // The plain timer and a special session look the same: the time left, and what it is for.
            FlowStage.PLAIN_TIMER, FlowStage.SPECIAL -> {
                Title(
                    stringResource(
                        if (flow.stage == FlowStage.SPECIAL) R.string.focus_flow_special_title else R.string.focus_flow_plain_title
                    )
                )
                Spacer(Modifier.height(16.dp))
                Countdown(flow.plannedEndAt - now)
                PurposeLine(flow)
            }
            FlowStage.RUNNING, FlowStage.DONE -> Unit
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun Countdown(leftMs: Long) {
    Text(
        clockText(leftMs),
        style = MaterialTheme.typography.displayLarge,
        fontWeight = FontWeight.Light,
        color = MaterialTheme.colorScheme.onBackground
    )
}

@Composable
private fun PurposeLine(flow: FlowState) {
    if (flow.purpose.isBlank()) return
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.focus_flow_purpose_line, flow.purpose),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

/** "Usual day or something special today?" and "What is this session for?", with the clock running down to the default. */
@Composable
private fun PromptContent(flow: FlowState, now: Long) {
    val context = LocalContext.current
    var flavor by remember { mutableStateOf<Flavor?>(null) }
    var purpose by remember { mutableStateOf("") }
    val valid = flavor != null && FocusFlow.cleanPurpose(purpose) != null

    Title(stringResource(R.string.focus_flow_prompt_text))
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilterChip(
            selected = flavor == Flavor.USUAL, onClick = { flavor = Flavor.USUAL },
            label = { Text(stringResource(R.string.focus_flow_usual)) }
        )
        FilterChip(
            selected = flavor == Flavor.SPECIAL, onClick = { flavor = Flavor.SPECIAL },
            label = { Text(stringResource(R.string.focus_flow_special)) }
        )
    }
    flavor?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (it == Flavor.USUAL) R.string.focus_flow_usual_hint else R.string.focus_flow_special_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = purpose,
        onValueChange = { purpose = it.replace('\n', ' ').take(80) },
        label = { Text(stringResource(R.string.focus_flow_purpose_label)) },
        placeholder = { Text(stringResource(R.string.focus_flow_purpose_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()
    )
    Spacer(Modifier.height(16.dp))
    Button(
        enabled = valid,
        onClick = { flavor?.let { FocusRunner.choose(context, it, purpose) } },
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().heightIn(min = 52.dp)
    ) { Text(stringResource(R.string.focus_flow_start)) }
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.focus_flow_prompt_countdown, clockText(flow.stageStartedAt + Rules.FOCUS_PROMPT_TIMEOUT_MS - now)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

/** "Did you do the work?", at the end of a focus phase or after the block. */
@Composable
private fun AskContent(flow: FlowState, finalQuestion: Boolean) {
    val context = LocalContext.current
    if (finalQuestion) {
        Text(
            stringResource(R.string.focus_flow_final_hint),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(12.dp))
    }
    Title(stringResource(R.string.focus_question))
    PurposeLine(flow)
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.focus_question_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(32.dp))
    Row(
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = { FocusRunner.answer(context, false) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
            Text(stringResource(R.string.focus_answer_no))
        }
        Button(onClick = { FocusRunner.answer(context, true) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
            Text(stringResource(R.string.focus_answer_yes))
        }
    }
}
