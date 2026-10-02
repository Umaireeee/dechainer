package io.github.warleysr.dechainer.screens.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.security.Pattern

/**
 * A 3x3 pad to draw a pattern on. [onPattern] gets the dots in the order they were touched when the
 * finger lifts; the pad clears itself. Dots a line passes over are taken too. [isError] tints it.
 */
@Composable
fun PatternPad(onPattern: (List<Int>) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, isError: Boolean = false) {
    val selected = remember { mutableStateListOf<Int>() }
    var finger by remember { mutableStateOf<Offset?>(null) }
    var side by remember { mutableFloatStateOf(0f) }
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lineColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.pattern_pad)

    fun touch(at: Offset) {
        val dot = Pattern.dotAt(at.x, at.y, side) ?: return
        if (dot in selected) return
        selected.lastOrNull()?.let { last ->
            Pattern.between(last, dot)?.let { mid -> if (mid !in selected) selected.add(mid) }
        }
        selected.add(dot)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .alpha(if (enabled) 1f else 0.4f)
            .semantics { contentDescription = description }
            .onSizeChanged { side = it.width.toFloat() }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { at -> selected.clear(); finger = at; touch(at) },
                    onDrag = { change, _ -> finger = change.position; touch(change.position) },
                    onDragEnd = {
                        val dots = selected.toList()
                        selected.clear(); finger = null
                        if (dots.isNotEmpty()) onPattern(dots)
                    },
                    onDragCancel = { selected.clear(); finger = null }
                )
            }
    ) {
        val cell = size.width / Pattern.SIDE
        fun center(dot: Int) = Offset((dot % Pattern.SIDE + 0.5f) * cell, (dot / Pattern.SIDE + 0.5f) * cell)
        val stroke = Stroke(width = cell * 0.06f, cap = StrokeCap.Round)
        for (i in 0 until selected.size - 1) drawLine(lineColor, center(selected[i]), center(selected[i + 1]), stroke.width, StrokeCap.Round)
        val tip = finger
        if (selected.isNotEmpty() && tip != null) drawLine(lineColor, center(selected.last()), tip, stroke.width, StrokeCap.Round)
        for (dot in 0 until Pattern.DOTS) {
            val on = dot in selected
            drawCircle(if (on) lineColor else dotColor, radius = cell * (if (on) 0.11f else 0.07f), center = center(dot))
        }
    }
}
