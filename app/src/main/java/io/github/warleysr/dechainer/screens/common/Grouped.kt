package io.github.warleysr.dechainer.screens.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The grouped-list grammar used across settings-like screens: rows sit together in one soft
 * card per section, with dividers that start past the icon tile so a group reads as one thing.
 *
 * Trailing slot rule: a chevron means "opens something"; a switch toggles; short text shows a
 * value. Exactly one of these per row, never two.
 */
enum class GroupPos { Top, Middle, Bottom, Single }

private val R = 16.dp

@Composable
fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 8.dp)
    )
}

/** One row of a group. [pos] shapes the card's corners and decides whether a divider follows. */
@Composable
fun GroupedRow(pos: GroupPos, content: @Composable () -> Unit) {
    val shape = when (pos) {
        GroupPos.Top -> RoundedCornerShape(topStart = R, topEnd = R)
        GroupPos.Middle -> RoundedCornerShape(0.dp)
        GroupPos.Bottom -> RoundedCornerShape(bottomStart = R, bottomEnd = R)
        GroupPos.Single -> RoundedCornerShape(R)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        content()
        if (pos == GroupPos.Top || pos == GroupPos.Middle) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 72.dp, end = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

/** ListItem colours for rows inside a group: the group paints the background. */
@Composable
fun groupedRowColors(): ListItemColors = ListItemDefaults.colors(containerColor = Color.Transparent)

/** The rounded tile behind a row's icon: it's what makes a row read as a thing, not a line. */
@Composable
fun IconTile(icon: ImageVector) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** "There's more behind this row." */
@Composable
fun Chevron() {
    Icon(
        Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
