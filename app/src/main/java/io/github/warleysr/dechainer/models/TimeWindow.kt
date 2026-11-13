package io.github.warleysr.dechainer.models

/** A stretch of one day, in minutes from midnight. [end] may be 1440 (midnight). */
data class Span(val start: Int, val end: Int) {
    fun contains(minute: Int) = minute in start until end

    fun formatted(): String = "%02d:%02d - %02d:%02d".format(start / 60, start % 60, end / 60 % 24, end % 60)
}

/**
 * An *allowed* period for an app: it can only be opened between [startMinute] and [endMinute] on
 * the chosen [days] (ISO numbers, 1 = Monday .. 7 = Sunday). A window that ends before it starts
 * runs past midnight into the next day. A disabled window is kept but ignored.
 */
data class TimeWindow(
    val startMinute: Int,
    val endMinute: Int,
    val days: Set<Int> = ALL_DAYS,
    val enabled: Boolean = true
) {
    /** Handles windows that span midnight (e.g. start=22:00, end=02:00). Ignores [days]. */
    fun contains(minuteOfDay: Int): Boolean =
        if (startMinute <= endMinute) minuteOfDay in startMinute until endMinute
        else minuteOfDay >= startMinute || minuteOfDay < endMinute

    /** "18:00 - 22:00". */
    fun formatted(): String = "%02d:%02d - %02d:%02d".format(
        startMinute / 60, startMinute % 60, endMinute / 60, endMinute % 60
    )

    val isEveryDay get() = days.containsAll(ALL_DAYS)

    /**
     * The parts of the given [day] this window allows. The tail of a window that started the day
     * before counts for [day], so a night window is split between the two days it touches.
     */
    fun spansOn(day: Int): List<Span> {
        if (!enabled || startMinute == endMinute) return emptyList()
        val spans = mutableListOf<Span>()
        if (startMinute < endMinute) {
            if (day in days) spans.add(Span(startMinute, endMinute))
        } else {
            if (day in days) spans.add(Span(startMinute, 1440))
            if (previousDay(day) in days && endMinute > 0) spans.add(Span(0, endMinute))
        }
        return spans
    }

    companion object {
        val ALL_DAYS: Set<Int> = (1..7).toSet()

        fun previousDay(day: Int) = if (day == 1) 7 else day - 1

        /**
         * What is allowed on [day] when every list in [sources] restricts independently (an app's
         * own windows, its group's). A source with no enabled windows doesn't restrict at all.
         * Returns null when nothing restricts, otherwise the overlap of all of them, possibly
         * empty ("not allowed at all today"), sorted and with touching spans merged.
         */
        fun allowedSpans(sources: List<List<TimeWindow>>, day: Int): List<Span>? {
            val restricting = sources.filter { list -> list.any { it.enabled } }
            if (restricting.isEmpty()) return null
            return restricting
                .map { list -> merge(list.flatMap { it.spansOn(day) }) }
                .reduce { a, b -> intersect(a, b) }
        }

        /** Whether [minute] of [day] is allowed under [windows] (an empty list allows everything). */
        fun isAllowed(windows: List<TimeWindow>, day: Int, minute: Int): Boolean =
            allowedSpans(listOf(windows), day)?.any { it.contains(minute) } ?: true

        /** Sorted copy of [spans] with overlapping or touching ones joined. */
        fun merge(spans: List<Span>): List<Span> {
            val result = mutableListOf<Span>()
            for (span in spans.sortedBy { it.start }) {
                val last = result.lastOrNull()
                if (last != null && span.start <= last.end) {
                    result[result.lastIndex] = Span(last.start, maxOf(last.end, span.end))
                } else {
                    result.add(span)
                }
            }
            return result
        }

        private fun intersect(a: List<Span>, b: List<Span>): List<Span> {
            val result = mutableListOf<Span>()
            for (x in a) for (y in b) {
                val start = maxOf(x.start, y.start)
                val end = minOf(x.end, y.end)
                if (start < end) result.add(Span(start, end))
            }
            return merge(result)
        }
    }
}
