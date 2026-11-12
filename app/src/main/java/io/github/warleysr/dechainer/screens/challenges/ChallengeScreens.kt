package io.github.warleysr.dechainer.screens.challenges

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R

/** Full-screen frame of the unlock game, with a "give up" button that cancels the unlock. */
@Composable
fun ChallengeScaffold(
    onGiveUp: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            content()
        }
        TextButton(
            onClick = onGiveUp,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            ),
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Text(stringResource(R.string.challenge_give_up), style = MaterialTheme.typography.labelLarge)
        }
    }
}
