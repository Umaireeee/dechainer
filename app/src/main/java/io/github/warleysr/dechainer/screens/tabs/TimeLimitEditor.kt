package io.github.warleysr.dechainer.screens.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.models.TimeLimit
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Formats a duration as "1h 30min", "2h" or "45min". */
fun formatLimitMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}min"
        h > 0 -> "${h}h"
        else -> "${m}min"
    }
}

/**
 * Short description of a limit, for buttons and list rows. Assumes [limit] is set. [compact]
 * leaves out the "varies by day" note, for rows with little room.
 */
@Composable
fun timeLimitSummary(limit: TimeLimit, compact: Boolean = false): String {
    if (!limit.isPerDay) return formatLimitMinutes(limit.dailyMinutes)
    val today = limit.todayMinutes()
    return when {
        compact && today > 0 -> stringResource(R.string.time_limit_today, formatLimitMinutes(today))
        compact -> stringResource(R.string.time_limit_none_today_short)
        today > 0 -> stringResource(R.string.time_limit_varies_today, formatLimitMinutes(today))
        else -> stringResource(R.string.time_limit_none_today)
    }
}

class TimeLimitEditorState(initial: TimeLimit) {
    var perDay by mutableStateOf(initial.isPerDay)
    var dailyMinutes by mutableIntStateOf(if (initial.isPerDay) initial.todayMinutes() else initial.dailyMinutes)
    var selectedDay by mutableStateOf(LocalDate.now().dayOfWeek)

    /** Indexed by [DayOfWeek.ordinal]. */
    val weeklyMinutes = mutableStateListOf(*DayOfWeek.entries.map { initial.minutesFor(it) }.toTypedArray())

    val currentMinutes: Int get() = if (perDay) weeklyMinutes[selectedDay.ordinal] else dailyMinutes

    fun setCurrentMinutes(minutes: Int) {
        if (perDay) weeklyMinutes[selectedDay.ordinal] = minutes else dailyMinutes = minutes
    }

    fun changePerDay(enabled: Boolean) {
        // Start the per-day editor from the daily limit, unless the days were already customized.
        if (enabled && weeklyMinutes.distinct().size <= 1) {
            weeklyMinutes.indices.forEach { weeklyMinutes[it] = dailyMinutes }
        }
        perDay = enabled
    }

    fun toTimeLimit(): TimeLimit =
        if (perDay) TimeLimit.perDay(weeklyMinutes.toList()) else TimeLimit.daily(dailyMinutes)
}

@Composable
fun rememberTimeLimitEditorState(initial: TimeLimit) = remember { TimeLimitEditorState(initial) }

@Composable
fun TimeLimitEditor(state: TimeLimitEditorState) {
    val locale = Locale.getDefault()
    val orderedDays = remember(locale) {
        val first = WeekFields.of(locale).firstDayOfWeek
        (0L until 7L).map { first.plus(it) }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { state.changePerDay(!state.perDay) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.time_limit_per_day),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = state.perDay, onCheckedChange = { state.changePerDay(it) })
        }

        if (state.perDay) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                orderedDays.forEach { day ->
                    DayChip(
                        label = day.getDisplayName(TextStyle.SHORT, locale).trimEnd('.'),
                        minutes = state.weeklyMinutes[day.ordinal],
                        selected = day == state.selectedDay,
                        onClick = { state.selectedDay = day },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        // Re-created per day/mode so the wheels scroll to the value being edited.
        key(state.perDay, state.selectedDay) {
            DurationWheels(minutes = state.currentMinutes, onChange = { state.setCurrentMinutes(it) })
        }

        Spacer(Modifier.height(12.dp))
        val minutes = state.currentMinutes
        val dayPrefix = if (state.perDay) {
            state.selectedDay.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) } + ": "
        } else ""
        if (minutes == 0) {
            Text(dayPrefix + stringResource(R.string.none), style = MaterialTheme.typography.labelSmall)
        } else {
            Text(
                dayPrefix + formatLimitMinutes(minutes),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun DayChip(label: String, minutes: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val content = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                  else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .background(container, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = content,
            maxLines = 1
        )
        Text(
            if (minutes > 0) shortDuration(minutes) else "—",
            fontSize = 10.sp,
            color = content,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/** Compact form for the day chips: "1h30", "2h", "45m". */
private fun shortDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h${m.toString().padStart(2, '0')}"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

/** Hours and minutes wheels spanning the available width. */
@Composable
fun DurationWheels(minutes: Int, onChange: (Int) -> Unit, maxHours: Int = 23) {
    var hours by remember(minutes) { mutableIntStateOf(minutes / 60) }
    var mins by remember(minutes) { mutableIntStateOf(minutes % 60) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NumberPickerWheel(
            value = hours,
            range = 0..maxHours,
            onValueChange = { hours = it; onChange(hours * 60 + mins) },
            label = stringResource(R.string.hours),
            modifier = Modifier.weight(1f)
        )
        Text(
            ":",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp).padding(top = 20.dp)
        )
        NumberPickerWheel(
            value = mins,
            range = 0..59,
            onValueChange = { mins = it; onChange(hours * 60 + mins) },
            label = stringResource(R.string.minutes),
            modifier = Modifier.weight(1f)
        )
    }
}

/** Two wheels (hours, minutes) for a time of day, spanning the available width. */
@Composable
fun TimeOfDayWheels(label: String, hour: Int, minute: Int, onHourChange: (Int) -> Unit, onMinuteChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NumberPickerWheel(
                value = hour,
                range = 0..23,
                onValueChange = onHourChange,
                label = stringResource(R.string.hours),
                modifier = Modifier.weight(1f)
            )
            Text(
                ":",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp).padding(top = 20.dp)
            )
            NumberPickerWheel(
                value = minute,
                range = 0..59,
                onValueChange = onMinuteChange,
                label = stringResource(R.string.minutes),
                modifier = Modifier.weight(1f)
            )
        }
    }
}
