package io.github.warleysr.dechainer.report

import io.github.warleysr.dechainer.day.GoalState
import io.github.warleysr.dechainer.urge.UrgeKind
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * The counts every report hands the model ready-made, so it reasons over patterns instead of
 * recounting raw lines (models miscount). Pure; derived data only.
 */
object ReportPatterns {
    /** The parts of the day the patterns are counted in. */
    enum class Band(val label: String, val hours: IntRange) {
        NIGHT("night 00-05", 0..5),
        MORNING("morning 06-11", 6..11),
        AFTERNOON("afternoon 12-17", 12..17),
        EVENING("evening 18-21", 18..21),
        LATE("late evening 22-23", 22..23);

        companion object {
            fun of(hour: Int): Band = entries.first { hour in it.hours }
        }
    }

    /** A goal that kept coming back without being done: the sign of a plan that is not working. */
    data class RepeatedGoal(val text: String, val days: Int, val notDone: Int)

    /** A whole percentage, or null when there is nothing to divide. */
    fun percent(part: Int, whole: Int): Int? = if (whole <= 0) null else part * 100 / whole

    /** Urges and slips counted per part of the day, in day order; empty parts are left out. */
    fun byBand(urges: List<UrgeFact>): List<Triple<Band, Int, Int>> =
        Band.entries.map { b ->
            val inBand = urges.filter { Band.of(it.at.hour) == b }
            Triple(b, inBand.count { it.kind == UrgeKind.URGE }, inBand.count { it.kind == UrgeKind.SLIP })
        }.filter { it.second + it.third > 0 }

    /** Urges and slips counted per day of the week, Monday first; empty days are left out. */
    fun byWeekday(urges: List<UrgeFact>): List<Triple<DayOfWeek, Int, Int>> =
        DayOfWeek.entries.map { d ->
            val onDay = urges.filter { it.at.dayOfWeek == d }
            Triple(d, onDay.count { it.kind == UrgeKind.URGE }, onDay.count { it.kind == UrgeKind.SLIP })
        }.filter { it.second + it.third > 0 }

    /**
     * Goals written on at least [minDays] different days (compared without case or spacing) and left
     * undone on at least two of them. Most repeated first.
     */
    fun repeatedGoals(days: List<DayFact>, minDays: Int = 3): List<RepeatedGoal> {
        val seen = HashMap<String, Pair<String, MutableList<GoalState>>>()
        days.forEach { d ->
            d.goals.distinctBy { norm(it.text) }.forEach { g ->
                val key = norm(g.text)
                if (key.isNotEmpty()) seen.getOrPut(key) { g.text.trim() to mutableListOf() }.second += g.state
            }
        }
        return seen.values
            .map { (text, states) -> RepeatedGoal(text, states.size, states.count { it != GoalState.DONE }) }
            .filter { it.days >= minDays && it.notDone >= 2 }
            .sortedWith(compareByDescending<RepeatedGoal> { it.days }.thenBy { it.text })
    }

    /** The lines of the PATTERNS block, shared by the weekly, monthly and yearly inputs. */
    fun lines(urges: List<UrgeFact>, days: List<DayFact>): List<String> = buildList {
        val bands = byBand(urges)
        add("by part of the day: " + if (bands.isEmpty()) "none" else bands.joinToString("; ") { (b, u, s) -> "${b.label}: urges $u, slips $s" })
        val week = byWeekday(urges)
        add("by day of the week: " + if (week.isEmpty()) "none" else week.joinToString("; ") { (d, u, s) ->
            "${d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}: urges $u, slips $s"
        })
        val repeated = repeatedGoals(days)
        if (repeated.isNotEmpty()) {
            add("goals that keep coming back undone: " + repeated.take(5).joinToString("; ") {
                "\"${it.text.take(ReportInputs.MAX_GOAL)}\" planned on ${it.days} days, not done on ${it.notDone}"
            })
        }
    }

    /** The rates line under TOTALS: the share of entries that were slips, the focus yes rate, the share of goals done. */
    fun ratesLine(s: ReportSummary): String {
        fun pct(v: Int?) = v?.let { "$it%" } ?: "n/a"
        return "slips as a share of all entries: ${pct(percent(s.slips, s.urges + s.slips))}; " +
            "check-in yes rate: ${pct(percent(s.checkinYes, s.checkinYes + s.checkinNo))}; " +
            "goals done: ${pct(percent(s.goalsDone, s.goalsTotal))}"
    }

    /** The day results line: days on plan, days short and why, rest days. */
    fun daysLine(days: List<DayFact>): String {
        val closed = days.filter { it.goals.isNotEmpty() || it.violation != null }
        val short = days.filter { it.violation != null }
        val why = short.groupingBy { it.violation!!.name.lowercase() }.eachCount().entries.sortedBy { it.key }
            .joinToString(", ") { "${it.key} ${it.value}" }
        val rest = days.count { it.kind == io.github.warleysr.dechainer.day.DayKind.REST }
        return "days with a plan or a result: ${closed.size}; days that fell short: ${short.size}" +
            (if (why.isNotEmpty()) " ($why)" else "") + "; rest days: $rest"
    }

    private fun norm(text: String) = text.trim().lowercase().replace(Regex("\\s+"), " ")
}
