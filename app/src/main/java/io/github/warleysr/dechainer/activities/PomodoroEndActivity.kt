package io.github.warleysr.dechainer.activities

import androidx.compose.material3.FilterChip
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.focus.Pomodoro
import io.github.warleysr.dechainer.ui.theme.DechainerTheme

/**
 * Shown when a focus session ends, over the lock screen too: one question, two answers.
 * Whatever you pick goes into the log next to that session.
 */
class PomodoroEndActivity : ComponentActivity() {
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        Pomodoro.ensureLoaded(this)
        val id = intent.getLongExtra(Pomodoro.EXTRA_SESSION_ID, 0L).takeIf { it > 0L }
            ?: Pomodoro.pendingQuestion.value
        val session = id?.let { Pomodoro.session(it) }
        // Already answered (from the notification, say) or nothing to ask: nothing to show.
        if (session == null || session.done != null) {
            finish()
            return
        }
        setContent {
            DechainerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .systemBarsPadding()
                            .padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            stringResource(R.string.focus_done_title).uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (session.minutes == 1) stringResource(R.string.focus_minutes_value_one) else stringResource(R.string.focus_minutes_value, session.minutes),
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Light,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.height(40.dp))
                        Text(
                            stringResource(R.string.focus_question),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            session.intention?.let { stringResource(R.string.focus_question_planned, it) }
                                ?: stringResource(R.string.focus_question_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(32.dp))
                        var answered by remember { mutableStateOf(false) }
                        fun afterAnswer() {
                            // Answering stops the alarm. If the break is waiting, offer it here;
                            // otherwise (it started by itself) there's nothing left to do.
                            if (Pomodoro.state.value.isIdle) answered = true else finish()
                        }
                        fun reply(done: Boolean) {
                            Pomodoro.answer(this@PomodoroEndActivity, session.id, done)
                            afterAnswer()
                        }
                        if (!answered) {
                            Row(
                                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedButton(onClick = { reply(false) }, modifier = Modifier.weight(1f).height(52.dp)) {
                                    Text(stringResource(R.string.focus_answer_no))
                                }
                                Button(onClick = { reply(true) }, modifier = Modifier.weight(1f).height(52.dp)) {
                                    Text(stringResource(R.string.focus_answer_yes))
                                }
                            }
                        } else {
                            val breakMinutes = Pomodoro.settings.value.minutesFor(Pomodoro.state.value.phase)
                            Button(
                                onClick = { Pomodoro.start(this@PomodoroEndActivity); finish() },
                                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().height(52.dp)
                            ) { Text(stringResource(R.string.focus_start_break, breakMinutes)) }
                            TextButton(onClick = { finish() }) { Text(stringResource(R.string.focus_later)) }
                        }
                    }
                }
            }
        }
    }
}
