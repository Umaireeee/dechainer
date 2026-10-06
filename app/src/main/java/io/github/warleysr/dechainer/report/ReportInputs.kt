package io.github.warleysr.dechainer.report

import io.github.warleysr.dechainer.focus.CheckinAnswer
import io.github.warleysr.dechainer.urge.UrgeEntry
import io.github.warleysr.dechainer.urge.UrgeJson
import io.github.warleysr.dechainer.urge.UrgeKind
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * The weekly report's input builder (blueprint 6.5): derived data only, as plain text for the model.
 * Pure. The raw urge text has no way in: [urgeFact] drops it, and [ReportData] has no field for it.
 */
object ReportInputs {
    const val MAX_PURPOSE = 120
    const val MAX_GOAL = 160
    const val MAX_ANSWER = 200
    const val MAX_DEEP_DIVE = 1_400
    const val MAX_ADVICE = 600

    /** Maps a stored entry to what a report may know about it. The note ([UrgeEntry.rawText]) is not carried over. */
    fun urgeFact(entry: UrgeEntry, zone: ZoneId): UrgeFact = UrgeFact(
        kind = entry.kind,
        at = Instant.ofEpochMilli(entry.createdAt).atZone(zone).toLocalDateTime(),
        answers = UrgeJson.answersFromJson(entry.answersJson),
        deepDive = entry.deepDive?.takeIf { it.isNotBlank() }
    )

    fun summary(data: ReportData, advice: String = ""): ReportSummary {
        val checks = data.sessions.flatMap { it.answers }
        return ReportSummary(
            urges = data.urges.count { it.kind == UrgeKind.URGE },
            slips = data.urges.count { it.kind == UrgeKind.SLIP },
            focusSessions = data.sessions.size,
            focusMinutes = data.sessions.sumOf { it.minutes },
            checkinYes = checks.count { it == CheckinAnswer.YES },
            checkinNo = checks.count { it == CheckinAnswer.NO },
            goalsDone = data.days.sumOf { it.goalsDone },
            goalsTotal = data.days.sumOf { it.goalsTotal },
            daysMissed = data.days.count { it.violation != null },
            advice = advice.take(MAX_ADVICE)
        )
    }

    fun summaryToJson(s: ReportSummary): String = JSONObject()
        .put("urges", s.urges).put("slips", s.slips)
        .put("focusSessions", s.focusSessions).put("focusMinutes", s.focusMinutes)
        .put("checkinYes", s.checkinYes).put("checkinNo", s.checkinNo)
        .put("goalsDone", s.goalsDone).put("goalsTotal", s.goalsTotal)
        .put("daysMissed", s.daysMissed).put("advice", s.advice)
        .toString()

    /** Forgiving: a stored value that does not parse is null, and nothing is written back from a failed read. */
    fun summaryFromJson(json: String?): ReportSummary? {
        if (json.isNullOrBlank()) return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return ReportSummary(
            o.optInt("urges"), o.optInt("slips"), o.optInt("focusSessions"), o.optInt("focusMinutes"),
            o.optInt("checkinYes"), o.optInt("checkinNo"), o.optInt("goalsDone"), o.optInt("goalsTotal"),
            o.optInt("daysMissed"), o.optString("advice", "")
        )
    }

    /**
     * The "Next week" section of a finished report, kept in its summary so the next report can say
     * whether the advice helped. Empty when the report has no such section.
     */
    fun adviceOf(markdown: String, heading: String = "next week"): String {
        val lines = markdown.lines()
        val marker = "<!-- advice:${heading.lowercase().replace(' ', '-')} -->"
        val aliases = when (heading.lowercase()) {
            "next week" -> listOf(heading, "اگلا ہفتہ", "اگلے ہفتے")
            "next month" -> listOf(heading, "اگلا مہینہ", "اگلے مہینے")
            "next year" -> listOf(heading, "اگلا سال", "اگلے سال")
            else -> listOf(heading)
        }
        val tagged = lines.indexOfFirst { it.trim() == marker }
        val start = if (tagged >= 0) tagged else lines.indexOfFirst { line ->
            line.trim().startsWith("#") && aliases.any { line.contains(it, ignoreCase = true) }
        }
        if (start < 0) return ""
        val following = lines.drop(start + 1).dropWhile { tagged >= 0 && (it.isBlank() || it.trim().startsWith("#")) }
        val body = following.takeWhile { !it.trim().startsWith("#") && !it.trim().startsWith("<!-- advice:") }
        return body.joinToString("\n").trim().take(MAX_ADVICE)
    }

    private fun clip(text: String, max: Int) = text.trim().replace(Regex("\\s+"), " ").take(max)

    /** The text between the model's `<week_data>` tags. */
    fun build(data: ReportData): String = buildString {
        val s = summary(data)
        appendLine("Period: ${data.firstDate} to ${data.lastDate} (7 days, local dates).")
        appendLine()
        appendLine("TOTALS")
        appendLine("urges: ${s.urges}; slips: ${s.slips}")
        appendLine("focus sessions: ${s.focusSessions}; focused minutes: ${s.focusMinutes}; check-in yes: ${s.checkinYes}; check-in no: ${s.checkinNo}")
        appendLine("checklist goals done: ${s.goalsDone} of ${s.goalsTotal}; days that fell short of the plan: ${s.daysMissed}")
        appendLine(ReportPatterns.ratesLine(s))
        appendLine(ReportPatterns.daysLine(data.days))

        appendLine()
        appendLine("PATTERNS")
        ReportPatterns.lines(data.urges, data.days).forEach { appendLine(it) }

        appendLine()
        appendLine("URGES AND SLIPS")
        if (data.urges.isEmpty()) appendLine("none recorded")
        data.urges.sortedBy { it.at }.forEach { u ->
            val day = u.at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
            appendLine("- ${u.kind.name.lowercase()} on ${u.at.toLocalDate()} ($day) at %02d:%02d".format(u.at.hour, u.at.minute))
            u.answers.forEach { a -> appendLine("  Q: ${clip(a.prompt, 160)} | A: ${clip(a.answer, MAX_ANSWER).ifEmpty { "(no answer)" }}") }
            appendLine("  deep dive: ${u.deepDive?.let { clip(it, MAX_DEEP_DIVE) } ?: "(none)"}")
        }

        appendLine()
        appendLine("FOCUS SESSIONS")
        if (data.sessions.isEmpty()) appendLine("none recorded")
        data.sessions.sortedBy { it.date }.forEach { f ->
            val checks = f.answers.joinToString(",") { it.name.lowercase() }.ifEmpty { "none" }
            appendLine("- ${f.date}: ${f.minutes} min, ${f.flavor.name.lowercase()}, purpose: ${clip(f.purpose, MAX_PURPOSE).ifEmpty { "(none)" }}, check-ins: $checks")
        }

        appendLine()
        appendLine("DAILY CHECKLIST")
        if (data.days.isEmpty()) appendLine("no days recorded")
        data.days.sortedBy { it.date }.forEach { d ->
            val done = d.goalsDone
            val why = d.violation?.let { "; fell short: ${it.name.lowercase()}" }.orEmpty()
            appendLine("- ${d.date}: ${d.kind.name.lowercase()} day; $done of ${d.goalsTotal} goals done$why; focus minutes that day: ${d.focusMinutes}")
            d.goals.forEach { g ->
                val target = if (g.type == io.github.warleysr.dechainer.day.GoalType.FOCUS_MINUTES && g.targetMinutes != null) " ${g.targetMinutes} min" else ""
                appendLine("    [${g.state.name.lowercase()}] (${g.type.name.lowercase()}$target) ${clip(g.text, MAX_GOAL)}")
            }
        }

        appendLine()
        appendLine("EARLIER REPORTS (oldest first)")
        if (data.past.isEmpty()) appendLine("none")
        data.past.forEach { p ->
            val x = p.summary
            appendLine("- ${p.firstDate} to ${p.lastDate}: urges ${x.urges}, slips ${x.slips}, focus ${x.focusMinutes} min in ${x.focusSessions} sessions, check-in yes ${x.checkinYes} no ${x.checkinNo}, goals ${x.goalsDone}/${x.goalsTotal}, days short of plan ${x.daysMissed}")
            if (x.advice.isNotBlank()) appendLine("  advice given then: ${clip(x.advice, MAX_ADVICE)}")
        }
    }.trimEnd()
}
