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

    /** Splits into non-wrapping [start, end) segments (end may be 1440, i.e. midnight). */
    private fun segments(): List<Pair<Int, Int>> =
        if (startMinute <= endMinute) listOf(startMinute to endMinute)
        else listOf(startMinute to 1440, 0 to endMinute)

    companion object {
        /**
         * The time actually allowed when two independent restrictions both apply (e.g. a group's
         * windows and an app's own windows) — the overlap of both, not a replacement of either.
         * An empty list means "no restriction", so it acts as the identity for intersection.
         */
        fun intersect(a: List<TimeWindow>, b: List<TimeWindow>): List<TimeWindow> {
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a

            val segmentsA = a.flatMap { it.segments() }
            val segmentsB = b.flatMap { it.segments() }
            val result = mutableListOf<TimeWindow>()

            for ((startA, endA) in segmentsA) {
                for ((startB, endB) in segmentsB) {
                    val start = maxOf(startA, startB)
                    val end = minOf(endA, endB)
                    if (start < end) result.add(TimeWindow(start, end))
                }
            }

            return result
        }
    }
}
