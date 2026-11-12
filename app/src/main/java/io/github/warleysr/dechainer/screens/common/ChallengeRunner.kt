package io.github.warleysr.dechainer.screens.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.warleysr.dechainer.screens.challenges.ChallengeScaffold
import io.github.warleysr.dechainer.screens.challenges.TetrisChallenge
import io.github.warleysr.dechainer.security.SecurityManager

/** The unlock delay: Tetris for the configured number of minutes; [onDone] fires when it ends. */
@Composable
fun TetrisUnlock(onDone: () -> Unit, onGiveUp: () -> Unit) {
    val context = LocalContext.current
    ChallengeScaffold(onGiveUp = onGiveUp) {
        TetrisChallenge(
            minutes = SecurityManager.getTetrisMinutes(context),
            onSuccess = onDone
        )
    }
}
