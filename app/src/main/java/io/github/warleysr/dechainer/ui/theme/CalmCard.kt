package io.github.warleysr.dechainer.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The app's one card: flat and tonal instead of lifted on a shadow, so screens read as calm
 * layers rather than floating tiles. [highlighted] is for the thing that's live right now — an
 * open schedule window, a running study session.
 */
@Composable
fun CalmCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) = Card(
    modifier = modifier,
    shape = MaterialTheme.shapes.large,
    colors = CardDefaults.cardColors(
        containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface
    ),
    // A hairline edge keeps the flat tonal card crisp against the background.
    border = BorderStroke(
        1.dp,
        if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        else MaterialTheme.colorScheme.outlineVariant
    ),
    content = content
)
