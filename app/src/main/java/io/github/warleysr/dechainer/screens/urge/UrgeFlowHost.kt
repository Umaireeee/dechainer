package io.github.warleysr.dechainer.screens.urge

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.Rules
import io.github.warleysr.dechainer.ai.AiError
import io.github.warleysr.dechainer.ai.AiGateResult
import io.github.warleysr.dechainer.ai.MarkdownBlocks
import io.github.warleysr.dechainer.ai.MdBlock
import io.github.warleysr.dechainer.ai.MdSpan
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.data.DeviceOwnerRepository
import io.github.warleysr.dechainer.screens.common.RepeatWhileVisible
import io.github.warleysr.dechainer.ui.theme.CalmCard
import io.github.warleysr.dechainer.ui.theme.Motion
import io.github.warleysr.dechainer.urge.Breathing
import io.github.warleysr.dechainer.urge.CrisisContact
import io.github.warleysr.dechainer.urge.Question
import io.github.warleysr.dechainer.urge.QuestionType
import io.github.warleysr.dechainer.urge.UrgeFlowRules
import io.github.warleysr.dechainer.urge.UrgeScreen
import io.github.warleysr.dechainer.urge.UrgeSettings
import io.github.warleysr.dechainer.viewmodels.DeepDiveUi
import io.github.warleysr.dechainer.viewmodels.QuestionsUi
import io.github.warleysr.dechainer.viewmodels.UrgeViewModel

/**
 * The urge flow's screens (blueprint 6.2): the choice, the breathing, the writing, the questions and
 * the deep dive. Which one shows comes from the stored entry and the time, never from where the
 * owner happened to be, so the flow opens back into the same step after the app is killed. There is
 * no exit control during the breathing.
 */
@Composable
fun UrgeFlowHost(vm: UrgeViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(TrustedClock.now(context)) }
    RepeatWhileVisible(1000) { now = TrustedClock.now(context) }
    val settings = remember { UrgeSettings(context) }
    val owner = remember { DeviceOwnerRepository.isDeviceOwner() }

    Box(modifier = modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        val entry = state.entry
        if (entry == null) {
            if (state.choosing) UrgeChoice(vm, owner)
        } else {
            val screen = if (state.deepDive != DeepDiveUi.Idle) UrgeScreen.DEEP_DIVE else UrgeFlowRules.screenFor(entry, now)
            if (UrgeFlowRules.stepsAsideForPattern(state.holdPrivate, screen)) {
                // Started from the locked screen: the private steps wait for the pattern.
                LaunchedEffect(entry.id) { vm.leaveForLock() }
            } else when (screen) {
                UrgeScreen.BREATHING -> BreathingScreen(
                    startsAt = entry.lockStartedAt ?: entry.createdAt,
                    endsAt = entry.lockEndedAt ?: now,
                    reason = settings.reason
                )
                UrgeScreen.WRITING -> {
                    LaunchedEffect(entry.id, entry.status) { vm.writingShown() }
                    WritingScreen(vm)
                }
                UrgeScreen.QUESTIONS -> QuestionsScreen(vm, state.questions, state.support, settings.contact)
                UrgeScreen.DEEP_DIVE -> DeepDiveScreen(vm, state.deepDive, state.support, settings.contact, state.entry?.id?.let { it >= 0L } == true)
                UrgeScreen.FINISHED -> LaunchedEffect(entry.id) { vm.finish() }
            }
        }
    }
}

// ---- the choice ----

@Composable
private fun UrgeChoice(vm: UrgeViewModel, deviceOwner: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.urge_choice_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        ChoiceCard(
            title = stringResource(R.string.urge_choice_ongoing),
            description = stringResource(if (deviceOwner) R.string.urge_choice_ongoing_desc else R.string.urge_choice_ongoing_desc_nolock),
            onClick = vm::chooseOngoing
        )
        Spacer(Modifier.height(16.dp))
        ChoiceCard(
            title = stringResource(R.string.urge_choice_slip),
            description = stringResource(R.string.urge_choice_slip_desc),
            onClick = vm::chooseSlip
        )
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = vm::closeChoice) { Text(stringResource(R.string.back)) }
    }
}

@Composable
private fun ChoiceCard(title: String, description: String, onClick: () -> Unit) {
    CalmCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(20.dp).heightIn(min = 64.dp), verticalArrangement = Arrangement.Center) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- breathing ----

/**
 * A circle that grows for 4 seconds and shrinks for 6, a ring showing the time left, and one prompt
 * per minute. The time comes from the trusted clock read once, plus the phone's own running time since,
 * so a changed wall clock cannot make the circle jump.
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

// ---- writing ----

@Composable
private fun WritingScreen(vm: UrgeViewModel) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text(stringResource(R.string.urge_write_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.urge_write_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = vm.noteDraft,
            onValueChange = { vm.noteDraft = it.take(Rules.MAX_NOTE_CHARS) },
            label = { Text(stringResource(R.string.urge_write_label)) },
            minLines = 6,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = vm::submitNote,
            enabled = UrgeFlowRules.noteReady(vm.noteDraft),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        ) { Text(stringResource(R.string.urge_write_continue)) }
        TextButton(onClick = vm::skipNote, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.urge_write_skip))
        }
    }
}

// ---- the questions ----

@Composable
private fun QuestionsScreen(vm: UrgeViewModel, ui: QuestionsUi, support: Boolean, contact: CrisisContact) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        if (support) {
            SupportCard(contact)
            Spacer(Modifier.height(24.dp))
        }
        Text(stringResource(R.string.urge_questions_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        when (ui) {
            QuestionsUi.None, QuestionsUi.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(16.dp))
                Text(stringResource(R.string.urge_questions_loading), style = MaterialTheme.typography.bodyLarge)
            }
            is QuestionsUi.Ready -> {
                ui.questions.forEach { q ->
                    QuestionField(q, vm)
                    Spacer(Modifier.height(24.dp))
                }
                Button(onClick = vm::submitAnswers, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(stringResource(R.string.urge_questions_submit))
                }
                TextButton(onClick = vm::submitAnswers, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.urge_questions_skip))
                }
            }
        }
    }
}

@Composable
private fun QuestionField(q: Question, vm: UrgeViewModel) {
    val answers = vm.answerDraft
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(q.prompt, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        when (q.type) {
            QuestionType.TEXT -> OutlinedTextField(
                value = answers[q.id].orEmpty(),
                onValueChange = { answers[q.id] = it.take(Rules.MAX_ANSWER_CHARS) },
                label = { Text(stringResource(R.string.urge_answer_label)) },
                modifier = Modifier.fillMaxWidth()
            )
            QuestionType.CHOICE -> q.options.forEach { option ->
                val selected = answers[q.id] == option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { answers[q.id] = option }),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Spacer(Modifier.size(12.dp))
                    Text(option, style = MaterialTheme.typography.bodyLarge)
                }
            }
            QuestionType.SCALE -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in 1..5) {
                        val label = n.toString()
                        FilterChip(
                            selected = answers[q.id] == label,
                            onClick = { answers[q.id] = label },
                            label = { Text(label, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.urge_scale_low), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.urge_scale_high), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---- the deep dive ----

@Composable
private fun DeepDiveScreen(vm: UrgeViewModel, ui: DeepDiveUi, support: Boolean, contact: CrisisContact, stored: Boolean) {
    var confirmDelete by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        if (support) {
            SupportCard(contact)
            Spacer(Modifier.height(24.dp))
        }
        when (ui) {
            DeepDiveUi.Idle -> Unit
            is DeepDiveUi.Writing -> {
                Text(stringResource(R.string.urge_dd_title), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(16.dp))
                if (ui.partial.isBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(16.dp))
                        Text(stringResource(R.string.urge_dd_writing), style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    MarkdownText(ui.partial)
                }
            }
            is DeepDiveUi.Ready -> {
                Text(stringResource(R.string.urge_dd_title), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(16.dp))
                MarkdownText(ui.markdown)
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.urge_dd_deleted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = vm::finish, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(stringResource(R.string.urge_dd_done))
                }
            }
            is DeepDiveUi.Pending -> {
                Text(stringResource(R.string.urge_dd_pending_title), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(pendingReason(ui.gate, ui.error)), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                // The text is still stored: say so, and let the owner delete it now.
                Text(
                    stringResource(if (!stored) R.string.urge_dd_not_stored else R.string.urge_dd_pending_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = vm::finish, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(stringResource(R.string.urge_dd_done))
                }
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(top = 8.dp)
                ) { Text(stringResource(R.string.urge_dd_delete)) }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.urge_dd_delete_title)) },
            text = { Text(stringResource(R.string.urge_dd_delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteEntry() }) { Text(stringResource(R.string.urge_dd_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.urge_dd_delete_keep)) }
            }
        )
    }
}

/** The one line that says why the deep dive is not made yet. */
private fun pendingReason(gate: AiGateResult, error: AiError?): Int = when {
    error != null -> when (error) {
        AiError.BAD_KEY -> R.string.urge_ai_err_bad_key
        AiError.NO_CREDITS -> R.string.urge_ai_err_no_credits
        AiError.BAD_MODEL -> R.string.urge_ai_err_bad_model
        AiError.BAD_URL -> R.string.urge_ai_err_bad_url
        AiError.INSECURE_URL -> R.string.urge_ai_err_insecure
        AiError.RATE_LIMIT -> R.string.urge_ai_err_rate
        AiError.NETWORK -> R.string.urge_ai_err_network
        AiError.SERVER -> R.string.urge_ai_err_server
        AiError.EMPTY -> R.string.urge_ai_err_empty
        AiError.STORAGE -> R.string.urge_ai_err_storage
    }
    gate == AiGateResult.NO_KEY -> R.string.urge_dd_why_no_key
    gate == AiGateResult.NO_CONSENT -> R.string.urge_dd_why_no_consent
    gate == AiGateResult.OFFLINE -> R.string.urge_dd_why_offline
    else -> R.string.urge_dd_why_busy
}

// ---- the crisis card ----

/**
 * Shown when the note looks like a crisis (blueprint 6.2): call or text the one person the owner
 * saved. The number lives on the phone only and is never part of any AI request.
 */
@Composable
fun SupportCard(contact: CrisisContact) {
    val context = LocalContext.current
    fun open(intent: Intent) {
        runCatching { context.startActivity(intent) }
    }
    CalmCard(modifier = Modifier.fillMaxWidth(), highlighted = true) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(stringResource(R.string.urge_support_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.urge_support_body), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            if (contact.usable) {
                val who = contact.name.ifBlank { contact.number }
                Button(
                    onClick = { open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(contact.number)))) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) { Text(stringResource(R.string.urge_support_call, who)) }
                OutlinedButton(
                    onClick = {
                        open(
                            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(contact.number)))
                                .putExtra("sms_body", context.getString(R.string.urge_support_sms_body))
                        )
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(top = 8.dp)
                ) { Text(stringResource(R.string.urge_support_text, who)) }
            } else {
                Text(
                    stringResource(R.string.urge_support_nobody),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---- Markdown ----

/** The AI's Markdown reply: headings, paragraphs, bullets, bold and italic. Nothing else is interpreted. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { MarkdownBlocks.parse(markdown) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    styled(block.spans),
                    style = if (block.level <= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                is MdBlock.Paragraph -> Text(styled(block.spans), style = MaterialTheme.typography.bodyLarge)
                is MdBlock.Bullet -> Row {
                    Text("•", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 12.dp))
                    Text(styled(block.spans), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun styled(spans: List<MdSpan>) = buildAnnotatedString {
    spans.forEach { span ->
        withStyle(
            SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null
            )
        ) { append(span.text) }
    }
}
