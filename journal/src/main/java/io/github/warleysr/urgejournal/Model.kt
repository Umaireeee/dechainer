package io.github.warleysr.urgejournal

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** The questions, in the order the interview may ask them. Which ones come up depends on the answers. */
enum class Q {
    FEELING, INTENSITY, PULL, PLACE,
    PROBE_STRESSED, PROBE_BORED, PROBE_LONELY, PROBE_TIRED, PROBE_ANXIOUS, PROBE_OTHER,
    THOUGHT, PHONE_PLACE, GAP, STOPPER
}

/** Every answer a question can have. Labels live in strings.xml as `opt_<name lowercase>`. */
enum class Opt {
    // How it feels
    BORED, STRESSED, LONELY, TIRED, ANXIOUS, SAD, OTHER_FEELING,
    // How strong
    MILD, MEDIUM, STRONG, OVERWHELMING,
    // What it pulls toward
    SCROLLING, EXPLICIT, VIDEOS_GAMES, OTHER_PULL,
    // Where you are
    BED, COUCH, DESK, BATHROOM, OUTSIDE, OTHER_PLACE,
    // Follow-ups by feeling
    STRESS_STUDY, STRESS_WORK, STRESS_PEOPLE, STRESS_MONEY, STRESS_UNKNOWN,
    BORED_AVOIDING, BORED_NOTHING, BORED_WAITING,
    LONELY_CAN_REACH, LONELY_NO_ONE, LONELY_WONT,
    SLEEP_LOW, SLEEP_MID, SLEEP_OK,
    ANX_FUTURE, ANX_SOCIAL, ANX_BODY, ANX_UNKNOWN,
    HAPPENED_TODAY, NOTHING_PARTICULAR,
    // What you're telling yourself
    JUST_ONCE, DESERVE_IT, NOBODY_KNOWS, CANT_STOP, NO_THOUGHT,
    // Where the phone is (late only)
    PHONE_WITH_ME, PHONE_OUTSIDE, PHONE_OTHER_ROOM,
    // After a slip: what was in place
    GAP_BLOCKED, GAP_UNBLOCKED, GAP_OTHER_DEVICE, GAP_WAITED,
    // After a slip: what would have stopped it
    STOP_DISTANCE, STOP_COMPANY, STOP_EARLIER, STOP_BUSY, STOP_SLEEP, STOP_UNKNOWN
}

val Q.options: List<Opt>
    get() = when (this) {
        Q.FEELING -> listOf(Opt.BORED, Opt.STRESSED, Opt.LONELY, Opt.TIRED, Opt.ANXIOUS, Opt.SAD, Opt.OTHER_FEELING)
        Q.INTENSITY -> listOf(Opt.MILD, Opt.MEDIUM, Opt.STRONG, Opt.OVERWHELMING)
        Q.PULL -> listOf(Opt.SCROLLING, Opt.EXPLICIT, Opt.VIDEOS_GAMES, Opt.OTHER_PULL)
        Q.PLACE -> listOf(Opt.BED, Opt.COUCH, Opt.DESK, Opt.BATHROOM, Opt.OUTSIDE, Opt.OTHER_PLACE)
        Q.PROBE_STRESSED -> listOf(Opt.STRESS_STUDY, Opt.STRESS_WORK, Opt.STRESS_PEOPLE, Opt.STRESS_MONEY, Opt.STRESS_UNKNOWN)
        Q.PROBE_BORED -> listOf(Opt.BORED_AVOIDING, Opt.BORED_NOTHING, Opt.BORED_WAITING)
        Q.PROBE_LONELY -> listOf(Opt.LONELY_CAN_REACH, Opt.LONELY_NO_ONE, Opt.LONELY_WONT)
        Q.PROBE_TIRED -> listOf(Opt.SLEEP_LOW, Opt.SLEEP_MID, Opt.SLEEP_OK)
        Q.PROBE_ANXIOUS -> listOf(Opt.ANX_FUTURE, Opt.ANX_SOCIAL, Opt.ANX_BODY, Opt.ANX_UNKNOWN)
        Q.PROBE_OTHER -> listOf(Opt.HAPPENED_TODAY, Opt.NOTHING_PARTICULAR)
        Q.THOUGHT -> listOf(Opt.JUST_ONCE, Opt.DESERVE_IT, Opt.NOBODY_KNOWS, Opt.CANT_STOP, Opt.NO_THOUGHT)
        Q.PHONE_PLACE -> listOf(Opt.PHONE_WITH_ME, Opt.PHONE_OUTSIDE, Opt.PHONE_OTHER_ROOM)
        Q.GAP -> listOf(Opt.GAP_BLOCKED, Opt.GAP_UNBLOCKED, Opt.GAP_OTHER_DEVICE, Opt.GAP_WAITED)
        Q.STOPPER -> listOf(Opt.STOP_DISTANCE, Opt.STOP_COMPANY, Opt.STOP_EARLIER, Opt.STOP_BUSY, Opt.STOP_SLEEP, Opt.STOP_UNKNOWN)
    }

/** Late enough that tiredness and an empty house do most of the damage. */
fun isLate(hour: Int): Boolean = hour >= 22 || hour < 5

/** Decides which question comes next from the answers so far. */
object QuestionTree {
    private fun probeFor(feeling: Opt): Q = when (feeling) {
        Opt.STRESSED -> Q.PROBE_STRESSED
        Opt.BORED -> Q.PROBE_BORED
        Opt.LONELY -> Q.PROBE_LONELY
        Opt.TIRED -> Q.PROBE_TIRED
        Opt.ANXIOUS -> Q.PROBE_ANXIOUS
        else -> Q.PROBE_OTHER
    }

    /** The full list of questions for these answers; it grows as the feeling becomes known. */
    fun sequence(feeling: Opt?, hour: Int, slipped: Boolean): List<Q> = buildList {
        add(Q.FEELING)
        if (!slipped) add(Q.INTENSITY)
        add(Q.PULL)
        add(Q.PLACE)
        if (feeling != null) add(probeFor(feeling))
        add(Q.THOUGHT)
        if (isLate(hour)) add(Q.PHONE_PLACE)
        if (slipped) {
            add(Q.GAP)
            add(Q.STOPPER)
        }
    }

    fun next(answers: Map<Q, Opt>, hour: Int, slipped: Boolean): Q? =
        sequence(answers[Q.FEELING], hour, slipped).firstOrNull { it !in answers }
}

/** Something to do with your body or surroundings. Text is `step_<name lowercase>`. */
enum class Step { BREATHE, LEAVE_ROOM, COLD_WATER, WALK, MESSAGE_SOMEONE, PHONE_OUT, SMALL_TASK, SLEEP }

/** A change for later, so the next urge meets a different situation. Text is `rule_<name lowercase>`. */
enum class Rule {
    PHONE_CHARGES_OUTSIDE, EARLIER_BLOCK, EXTEND_SCHEDULE, OTHER_DEVICE, OFFLINE_URGE,
    STUDY_START, TELL_SOMEONE, SLEEP_EARLIER
}

/** Why it is probably happening. Text is `reason_<name lowercase>`. */
enum class Reason { TIRED_LATE, STRESS_ESCAPE, BORED_AVOIDING, LONELY, ANXIOUS, THOUGHT_TRAP, GENERAL }

/** A command for Déchaîner. [kind] is one of its `URGE_ACTION` kinds. */
data class DoorAction(val kind: String, val minutes: Int) {
    companion object {
        const val IMPULSE_BLOCK = "IMPULSE_BLOCK"
        const val FOCUS_BLOCK = "FOCUS_BLOCK"
    }
}

data class Plan(
    val reasons: List<Reason>,
    val steps: List<Step>,
    val primary: DoorAction,
    val secondary: DoorAction?,
    val rules: List<Rule>
)

/** Turns answers into a plan. Rules only for now; an AI deep dive can sit on top of this later. */
object Coach {
    private val IMPULSE_MINUTES = listOf(15, 30, 60, 120)

    /** 0 (mild) to 3 (overwhelming). Late hours and a slip both push it up. */
    fun level(answers: Map<Q, Opt>, hour: Int, slipped: Boolean): Int {
        if (slipped) return 3
        val base = when (answers[Q.INTENSITY]) {
            Opt.MILD -> 0
            Opt.MEDIUM -> 1
            Opt.STRONG -> 2
            Opt.OVERWHELMING -> 3
            else -> 1
        }
        return (base + if (isLate(hour)) 1 else 0).coerceAtMost(3)
    }

    fun plan(answers: Map<Q, Opt>, hour: Int, slipped: Boolean): Plan {
        val late = isLate(hour)
        val level = level(answers, hour, slipped)
        val feeling = answers[Q.FEELING]

        val minutes = if (slipped && late) 180 else IMPULSE_MINUTES[level]
        val primary = DoorAction(DoorAction.IMPULSE_BLOCK, minutes)
        // A calm daytime urge born of stress or boredom is a chance to turn it into work.
        val secondary = if (!slipped && !late && (feeling == Opt.STRESSED || feeling == Opt.BORED))
            DoorAction(DoorAction.FOCUS_BLOCK, 25) else null

        val steps = linkedSetOf<Step>()
        if (slipped) {
            steps += Step.LEAVE_ROOM
            steps += Step.COLD_WATER
        } else {
            steps += Step.BREATHE
        }
        if (answers[Q.PLACE] == Opt.BED || answers[Q.PLACE] == Opt.BATHROOM) steps += Step.LEAVE_ROOM
        if (answers[Q.PHONE_PLACE] == Opt.PHONE_WITH_ME) steps += Step.PHONE_OUT
        if (level >= 2) {
            steps += Step.COLD_WATER
            steps += Step.WALK
        }
        when (feeling) {
            Opt.LONELY -> steps += if (answers[Q.PROBE_LONELY] == Opt.LONELY_CAN_REACH) Step.MESSAGE_SOMEONE else Step.WALK
            Opt.STRESSED, Opt.BORED -> steps += if (late) Step.SLEEP else Step.SMALL_TASK
            Opt.TIRED -> steps += Step.SLEEP
            else -> steps += Step.WALK
        }

        val reasons = buildList {
            if (feeling == Opt.TIRED || answers[Q.PROBE_TIRED] == Opt.SLEEP_LOW || late) add(Reason.TIRED_LATE)
            if (feeling == Opt.STRESSED) add(Reason.STRESS_ESCAPE)
            if (feeling == Opt.ANXIOUS) add(Reason.ANXIOUS)
            if (feeling == Opt.BORED) add(Reason.BORED_AVOIDING)
            if (feeling == Opt.LONELY) add(Reason.LONELY)
            if (answers[Q.THOUGHT].let { it == Opt.JUST_ONCE || it == Opt.DESERVE_IT || it == Opt.NOBODY_KNOWS || it == Opt.CANT_STOP }) {
                add(Reason.THOUGHT_TRAP)
            }
            if (isEmpty()) add(Reason.GENERAL)
        }.distinct()

        val rules = linkedSetOf<Rule>()
        when (answers[Q.GAP]) {
            Opt.GAP_UNBLOCKED -> rules += Rule.EXTEND_SCHEDULE
            Opt.GAP_OTHER_DEVICE -> rules += Rule.OTHER_DEVICE
            Opt.GAP_WAITED -> rules += Rule.EARLIER_BLOCK
            Opt.GAP_BLOCKED -> rules += Rule.OFFLINE_URGE
            else -> {}
        }
        when (answers[Q.STOPPER]) {
            Opt.STOP_DISTANCE -> rules += Rule.PHONE_CHARGES_OUTSIDE
            Opt.STOP_COMPANY -> rules += Rule.TELL_SOMEONE
            Opt.STOP_EARLIER -> rules += Rule.EARLIER_BLOCK
            Opt.STOP_BUSY -> rules += Rule.STUDY_START
            Opt.STOP_SLEEP -> rules += Rule.SLEEP_EARLIER
            else -> {}
        }
        if (answers[Q.PHONE_PLACE] == Opt.PHONE_WITH_ME) rules += Rule.PHONE_CHARGES_OUTSIDE
        if (answers[Q.PROBE_TIRED] == Opt.SLEEP_LOW) rules += Rule.SLEEP_EARLIER
        if (answers[Q.PROBE_BORED] == Opt.BORED_AVOIDING || answers[Q.PROBE_STRESSED] == Opt.STRESS_STUDY) rules += Rule.STUDY_START
        if (feeling == Opt.LONELY) rules += Rule.TELL_SOMEONE
        if (late && rules.isEmpty()) rules += Rule.EARLIER_BLOCK

        return Plan(reasons, steps.take(4), primary, secondary, rules.take(2))
    }
}

enum class Outcome { RESISTED, GAVE_IN }

/** One urge, or one slip, as logged. [outcome] is null until you say how it went. */
data class Entry(
    val time: Long,
    val slipped: Boolean,
    val answers: Map<Q, Opt>,
    val outcome: Outcome?
) {
    /** A slip, or an urge that was given in to. */
    val gaveIn: Boolean get() = slipped || outcome == Outcome.GAVE_IN

    fun toJson(): JSONObject = JSONObject().apply {
        put("t", time)
        put("s", slipped)
        put("a", JSONObject().also { o -> answers.forEach { (q, a) -> o.put(q.name, a.name) } })
        outcome?.let { put("o", it.name) }
    }

    companion object {
        fun fromJson(o: JSONObject): Entry? = runCatching {
            val a = o.optJSONObject("a")
            val answers = buildMap<Q, Opt> {
                if (a != null) {
                    a.keys().forEach { key ->
                        val q = runCatching { Q.valueOf(key) }.getOrNull()
                        val opt = runCatching { Opt.valueOf(a.getString(key)) }.getOrNull()
                        if (q != null && opt != null) put(q, opt)
                    }
                }
            }
            Entry(
                time = o.getLong("t"),
                slipped = o.optBoolean("s", false),
                answers = answers,
                outcome = if (o.has("o")) runCatching { Outcome.valueOf(o.getString("o")) }.getOrNull() else null
            )
        }.getOrNull()

        fun listFromJson(text: String?): List<Entry> {
            if (text.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(text)
                (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }

        fun listToJson(entries: List<Entry>): String = JSONArray(entries.map { it.toJson() }).toString()
    }
}

/** The past seven days in a few numbers. */
data class Week(
    val total: Int,
    val resisted: Int,
    val gaveIn: Int,
    val topFeeling: Opt?,
    val peakHour: Int?,
    /** Calendar days since the last slip or give-in, or null if there has never been one. */
    val daysSinceGaveIn: Int?
)

object Insights {
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

    fun week(entries: List<Entry>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Week {
        val recent = entries.filter { it.time in (now - WEEK_MS)..now }
        val top = recent.mapNotNull { it.answers[Q.FEELING] }.groupingBy { it }.eachCount()
            .maxWithOrNull(compareBy<Map.Entry<Opt, Int>> { it.value }.thenBy { -it.key.ordinal })?.key
        val peak = recent.groupingBy { Instant.ofEpochMilli(it.time).atZone(zone).hour }.eachCount()
            .maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { -it.key })?.key
        val last = entries.filter { it.gaveIn && it.time <= now }.maxOfOrNull { it.time }
        val days = last?.let {
            val d: LocalDate = Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
            val today: LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            ChronoUnit.DAYS.between(d, today).toInt().coerceAtLeast(0)
        }
        return Week(
            total = recent.size,
            resisted = recent.count { !it.gaveIn && it.outcome == Outcome.RESISTED },
            gaveIn = recent.count { it.gaveIn },
            topFeeling = top,
            peakHour = peak,
            daysSinceGaveIn = days
        )
    }
}
