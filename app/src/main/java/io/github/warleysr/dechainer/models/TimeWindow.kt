package io.github.warleysr.dechainer.models

data class TimeWindow(val startMinute: Int, val endMinute: Int) {
    /** Handles windows that span midnight (e.g. start=22:00, end=02:00). */
    fun contains(minuteOfDay: Int): Boolean =
        if (startMinute <= endMinute) minuteOfDay in startMinute until endMinute
        else minuteOfDay >= startMinute || minuteOfDay < endMinute

    /** Minutes remaining until this window closes, assuming [minuteOfDay] is inside it. */
    fun minutesUntilEnd(minuteOfDay: Int): Int =
        if (endMinute > minuteOfDay) endMinute - minuteOfDay else (1440 - minuteOfDay) + endMinute

    fun formatted(): String = "%02d:%02d - %02d:%02d".format(
        startMinute / 60, startMinute % 60, endMinute / 60, endMinute % 60
    )
}
