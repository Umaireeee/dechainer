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

/** How each question and answer reads in the prompt sent to the AI (plain English, no app jargon). */
object Plain {
    fun question(q: Q): String = when (q) {
        Q.FEELING -> "Feeling"
        Q.INTENSITY -> "Urge strength"
        Q.PULL -> "Pulled toward"
        Q.PLACE -> "Location"
        Q.THOUGHT -> "What they were telling themselves"
        Q.PHONE_PLACE -> "Where the phone was (late at night)"
        Q.GAP -> "What was in place when the slip happened"
        Q.STOPPER -> "What they think would have stopped it"
        else -> "Detail"
    }

    private val overrides = mapOf(
        Opt.SLEEP_LOW to "slept under 5 hours",
        Opt.SLEEP_MID to "slept 5 to 7 hours",
        Opt.SLEEP_OK to "slept 7+ hours",
        Opt.GAP_BLOCKED to "their phone was blocked, and it happened anyway",
        Opt.GAP_UNBLOCKED to "nothing was blocking it at the time",
        Opt.GAP_OTHER_DEVICE to "used another device",
        Opt.GAP_WAITED to "waited for the block to end",
        Opt.LONELY_CAN_REACH to "could reach someone",
        Opt.LONELY_NO_ONE to "no one around",
        Opt.LONELY_WONT to "could reach someone but didn't want to",
        Opt.NO_THOUGHT to "no particular thought, it just happens",
        Opt.EXPLICIT to "explicit content",
        Opt.VIDEOS_GAMES to "videos or games",
        Opt.BORED_AVOIDING to "bored, avoiding something they should do",
        Opt.BORED_NOTHING to "bored, nothing to do",
        Opt.BORED_WAITING to "bored, waiting for something",
        Opt.HAPPENED_TODAY to "something happened today",
        Opt.NOTHING_PARTICULAR to "nothing in particular",
        Opt.OTHER_FEELING to "another feeling",
        Opt.OTHER_PULL to "something else",
        Opt.OTHER_PLACE to "somewhere else"
    )

    fun answer(o: Opt): String = overrides[o] ?: o.name.lowercase().replace('_', ' ')

    fun step(s: Step): String = s.name.lowercase().replace('_', ' ')

    fun after(a: After): String = when (a) {
        After.GONE -> "the urge passed"
        After.WEAKER -> "the urge got weaker and they felt okay"
        After.STILL -> "the urge was still strong"
    }
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

    /**
     * The full list of questions for these answers; it grows as the feeling becomes known. [quick]
     * is the short version asked after a ride: how it feels, where, and what you were telling
     * yourself. A slip always gets the full one.
     */
    fun sequence(feeling: Opt?, hour: Int, slipped: Boolean, quick: Boolean = false): List<Q> = buildList {
        if (quick && !slipped) {
            add(Q.FEELING)
            add(Q.PLACE)
            add(Q.THOUGHT)
            if (isLate(hour)) add(Q.PHONE_PLACE)
            return@buildList
        }
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

    fun next(answers: Map<Q, Opt>, hour: Int, slipped: Boolean, quick: Boolean = false): Q? =
        sequence(answers[Q.FEELING], hour, slipped, quick).firstOrNull { it !in answers }
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
        /** Every app but calls, emergency apps, alarms, Déchaîner and this one, for a few minutes. */
        const val RIDE_LOCK = "RIDE_LOCK"
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

    /** A step needs this many tries before what happened to it counts as evidence. */
    const val MIN_TRIES = 3

    /** Success rate of each step you have tried enough times, smoothed so two lucky tries don't crown it. */
    fun stepScores(history: List<Entry>): Map<Step, Double> {
        val tries = mutableMapOf<Step, Int>()
        val wins = mutableMapOf<Step, Int>()
        history.forEach { e ->
            val after = e.after ?: return@forEach
            e.tried.distinct().forEach { step ->
                tries[step] = (tries[step] ?: 0) + 1
                if (after == After.GONE || after == After.WEAKER) wins[step] = (wins[step] ?: 0) + 1
            }
        }
        return tries.filter { it.value >= MIN_TRIES }
            .mapValues { (step, n) -> ((wins[step] ?: 0) + 1.0) / (n + 2.0) }
    }

    /** Puts what has worked for you first, and adds a proven step the rules didn't pick. */
    fun rank(steps: List<Step>, history: List<Entry>): List<Step> {
        val scores = stepScores(history)
        if (scores.isEmpty()) return steps
        val proven = scores.filter { it.value >= 0.65 && it.key !in steps }.keys
        return (steps + proven).sortedByDescending { scores[it] ?: 0.5 }
    }

    /** The one thing to do during a ride: what has worked for you, else leaving the room. */
    fun rideStep(history: List<Entry>): Step =
        stepScores(history).filter { it.value >= 0.6 && it.key != Step.BREATHE }
            .maxByOrNull { it.value }?.key ?: Step.LEAVE_ROOM

    fun plan(answers: Map<Q, Opt>, hour: Int, slipped: Boolean, history: List<Entry> = emptyList()): Plan {
        val late = isLate(hour)
        val level = level(answers, hour, slipped)
        val feeling = answers[Q.FEELING]

        val minutes = if (slipped && late) 180 else IMPULSE_MINUTES[level]
        val primary = DoorAction(DoorAction.IMPULSE_BLOCK, minutes)
        // By day, an urge (or a slip) is best followed by real work: a short committed focus block
        // turns the pull into a start, and after a slip it stops one lapse becoming a lost day.
        val secondary = if (!late) DoorAction(DoorAction.FOCUS_BLOCK, 25) else null

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

        return Plan(reasons, rank(steps.toList(), history).take(4), primary, secondary, rules.take(2))
    }
}

/** The evening answer to "did today go the way you planned?". Text is `day_<name lowercase>`. */
enum class DayResult { PLANNED, PARTLY, NOT }

enum class Outcome { RESISTED, GAVE_IN }

/** How the urge stood after riding it out. Text is `after_<name lowercase>`. */
enum class After {
    GONE, WEAKER, STILL;

    /** Passed or weaker means it was ridden out; still strong means the person is still in it. */
    val outcome: Outcome? get() = if (this == STILL) null else Outcome.RESISTED
}

/** One urge, or one slip, as logged. [outcome] is null until you say how it went. */
data class Entry(
    val time: Long,
    val slipped: Boolean,
    val answers: Map<Q, Opt>,
    val outcome: Outcome?,
    /** Anything the person added in their own words. */
    val note: String = "",
    /** The AI deep dive as the model returned it, kept so it can be read again. */
    val report: String? = null,
    /** What the person did while riding it out; feeds the coach's ranking of steps. */
    val tried: List<Step> = emptyList(),
    /** How the urge stood after the ride, if it was ridden. */
    val after: After? = null
) {
    /** A slip, or an urge that was given in to. */
    val gaveIn: Boolean get() = slipped || outcome == Outcome.GAVE_IN

    /** An urge that was ridden out. An entry still open (no answer yet) is neither ridden out nor given in to. */
    val ridden: Boolean get() = !gaveIn && outcome == Outcome.RESISTED

    fun toJson(): JSONObject = JSONObject().apply {
        put("t", time)
        put("s", slipped)
        put("a", JSONObject().also { o -> answers.forEach { (q, a) -> o.put(q.name, a.name) } })
        outcome?.let { put("o", it.name) }
        if (note.isNotBlank()) put("n", note)
        report?.let { put("r", it) }
        if (tried.isNotEmpty()) put("tr", JSONArray(tried.map { it.name }))
        after?.let { put("af", it.name) }
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
                outcome = if (o.has("o")) runCatching { Outcome.valueOf(o.getString("o")) }.getOrNull() else null,
                note = o.optString("n", ""),
                report = if (o.has("r")) o.getString("r") else null,
                tried = o.optJSONArray("tr")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> runCatching { Step.valueOf(a.getString(i)) }.getOrNull() }
                }.orEmpty(),
                after = if (o.has("af")) runCatching { After.valueOf(o.getString("af")) }.getOrNull() else null
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

        /** Adds [incoming] to [existing], keeping one entry per time (the existing one wins). */
        fun merge(existing: List<Entry>, incoming: List<Entry>): List<Entry> {
            val known = existing.map { it.time }.toSet()
            return (existing + incoming.filter { it.time !in known }.distinctBy { it.time }).sortedBy { it.time }
        }
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
            resisted = recent.count { it.ridden },
            gaveIn = recent.count { it.gaveIn },
            topFeeling = top,
            peakHour = peak,
            daysSinceGaveIn = days
        )
    }

    /** One bar of the seven-day chart: urges ridden out, and given in to (slips count here). */
    data class DayBar(val date: LocalDate, val resisted: Int, val gaveIn: Int)

    /** The last [days] calendar days ending today, oldest first, with a bar for each. */
    fun days(entries: List<Entry>, now: Long, zone: ZoneId = ZoneId.systemDefault(), days: Int = 7): List<DayBar> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val byDay = entries.groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }
        return (days - 1 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            val list = byDay[d].orEmpty()
            DayBar(d, list.count { it.ridden }, list.count { it.gaveIn })
        }
    }


    /** Days since the first entry, up to [window]; the honest denominator for "days without a slip". */
    private fun span(entries: List<Entry>, now: Long, zone: ZoneId, window: Int): Int {
        val first = entries.minOfOrNull { it.time } ?: return 0
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = Instant.ofEpochMilli(first).atZone(zone).toLocalDate()
        return (ChronoUnit.DAYS.between(start, today).toInt() + 1).coerceIn(0, window)
    }

    /**
     * Days without a slip out of the last [window] days (or fewer, for a new journal). A softer
     * measure than a streak: one slip costs one day, not everything.
     */
    fun cleanDays(entries: List<Entry>, now: Long, zone: ZoneId = ZoneId.systemDefault(), window: Int = 30): Pair<Int, Int>? {
        val days = span(entries, now, zone, window)
        if (days == 0) return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val oldest = today.minusDays(days - 1L)
        val slipDays = entries.filter { it.gaveIn && it.time <= now }
            .map { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }
            .filter { !it.isBefore(oldest) }
            .toSet().size
        return (days - slipDays).coerceAtLeast(0) to days
    }

    /** A stretch of the day where urges keep landing, from the last 30 days. */
    data class HotWindow(val startHour: Int, val count: Int, val total: Int, val topFeeling: Opt?) {
        val endHour: Int get() = (startHour + WINDOW_HOURS) % 24

        /** Half an hour before the window opens, as minutes from midnight. */
        val nudgeMinute: Int get() = ((startHour * 60 - 30) + 24 * 60) % (24 * 60)
    }

    const val WINDOW_HOURS = 4

    /**
     * The four-hour stretch that holds most urges, but only when there is enough to say so: at
     * least six in the last 30 days, at least four of them in the stretch and at least 40 percent.
     * A few entries can look like a pattern by chance, so it stays quiet until they don't. When
     * several stretches tie, the one that starts on an hour with an urge wins, so it begins where
     * the trouble begins rather than hours earlier.
     */
    fun hotWindow(entries: List<Entry>, now: Long, zone: ZoneId = ZoneId.systemDefault()): HotWindow? {
        val recent = entries.filter { it.time in (now - 30L * 24 * 60 * 60 * 1000)..now }
        if (recent.size < 6) return null
        val hours = recent.map { Instant.ofEpochMilli(it.time).atZone(zone).hour }
        fun inside(start: Int, hour: Int) = (hour - start + 24) % 24 < WINDOW_HOURS
        val best = (0 until 24).map { start ->
            val hit = hours.count { inside(start, it) }
            Triple(start, hit, if (hours.any { it == start }) 0 else 1)
        }.sortedWith(compareBy({ -it.second }, { it.third }, { it.first })).first()
        val (start, count, _) = best
        if (count < 4 || count * 10 < recent.size * 4) return null
        val feeling = recent.filter { inside(start, Instant.ofEpochMilli(it.time).atZone(zone).hour) }
            .mapNotNull { it.answers[Q.FEELING] }.groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key
        return HotWindow(start, count, recent.size, feeling)
    }

    /** How the day went against the plan, answered in the evening. */
    fun planDays(days: Map<LocalDate, DayResult>, today: LocalDate, window: Int = 7): Pair<Int, Int>? {
        val recent = days.filterKeys { !it.isAfter(today) && ChronoUnit.DAYS.between(it, today) < window }
        if (recent.isEmpty()) return null
        return recent.count { it.value == DayResult.PLANNED } to recent.size
    }

    /**
     * Whether the last two weeks look heavier than the two before: many more entries, or several
     * overwhelming ones. Used only to offer a gentle nudge toward real support, never a warning.
     */
    fun heavier(entries: List<Entry>, now: Long): Boolean {
        val day = 24L * 60 * 60 * 1000
        val last = entries.filter { it.time in (now - 14 * day)..now }
        val before = entries.count { it.time in (now - 28 * day) until (now - 14 * day) }
        val overwhelming = last.count { it.answers[Q.INTENSITY] == Opt.OVERWHELMING }
        return overwhelming >= 3 || (last.size >= 6 && last.size >= before * 3 / 2 + 1)
    }

    /** Counts only, safe to hand to someone you trust. No notes, no feelings, no places. */
    fun shareText(entries: List<Entry>, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val w = week(entries, now, zone)
        val clean = cleanDays(entries, now, zone, 7)
        return buildString {
            append("My week: ${w.total} urges logged, ${w.resisted} ridden out, ${w.gaveIn} given in to.")
            if (clean != null) append(" ${clean.first} of the last ${clean.second} days without a slip.")
        }
    }

    /** The last [weeks] weeks ending with this one, oldest first; a bar's date is that week's Monday. */
    fun weeks(entries: List<Entry>, now: Long, zone: ZoneId = ZoneId.systemDefault(), weeks: Int = 12): List<DayBar> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val thisMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        return (weeks - 1 downTo 0).map { back ->
            val start = thisMonday.minusWeeks(back.toLong())
            val list = entriesIn(entries, start, start.plusDays(6), zone)
            DayBar(start, list.count { it.ridden }, list.count { it.gaveIn })
        }
    }

    /** Entries made on any day from [from] to [to], both included. */
    fun entriesIn(entries: List<Entry>, from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Entry> =
        entries.filter {
            val d = Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate()
            !d.isBefore(from) && !d.isAfter(to)
        }

    /** Feelings named in [entries], most common first. */
    fun feelingCounts(entries: List<Entry>): List<Pair<Opt, Int>> =
        entries.mapNotNull { it.answers[Q.FEELING] }.groupingBy { it }.eachCount()
            .entries.sortedWith(compareBy({ -it.value }, { it.key.ordinal })).map { it.key to it.value }

    /** A short, anonymous digest of recent history for the AI: counts and patterns, no notes. */
    fun summary(entries: List<Entry>, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val recent = entries.filter { it.time in (now - 30L * 24 * 60 * 60 * 1000)..now }
        if (recent.size < 3) return ""
        val feelings = recent.mapNotNull { it.answers[Q.FEELING] }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(3).joinToString(", ") { "${Plain.answer(it.key)} (${it.value})" }
        val hours = recent.groupingBy { Instant.ofEpochMilli(it.time).atZone(zone).hour }.eachCount()
            .entries.sortedByDescending { it.value }.take(3).joinToString(", ") { "%02d:00 (%d)".format(it.key, it.value) }
        val late = recent.count { isLate(Instant.ofEpochMilli(it.time).atZone(zone).hour) }
        val slips = recent.count { it.gaveIn }
        val days = daysSince(entries, now, zone)
        return buildString {
            append("Last 30 days: ${recent.size} entries, $slips given in to or slipped. ")
            append("Most common feelings: $feelings. Busiest hours: $hours. $late of them were late at night (22:00 to 05:00). ")
            if (days != null) append("Days since last slip: $days.")
        }
    }

    private fun daysSince(entries: List<Entry>, now: Long, zone: ZoneId): Int? {
        val last = entries.filter { it.gaveIn && it.time <= now }.maxOfOrNull { it.time } ?: return null
        val d = Instant.ofEpochMilli(last).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(d, today).toInt().coerceAtLeast(0)
    }
}

/**
 * An if-then plan the person wrote or edited themselves. Plans they own work better than assigned
 * ones, so the coach only suggests wording; this is what they saved. [feeling] and [late] say when
 * it applies: a plan with neither applies whenever.
 */
data class MyPlan(val id: Long, val text: String, val feeling: Opt?, val late: Boolean) {
    /** How specific the plan is to this moment, or -1 if it doesn't apply. */
    fun score(now: Opt?, isLateNow: Boolean): Int {
        if (feeling != null && feeling != now) return -1
        if (late && !isLateNow) return -1
        return (if (feeling != null) 1 else 0) + (if (late) 1 else 0)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("i", id)
        put("x", text)
        feeling?.let { put("f", it.name) }
        if (late) put("l", true)
    }

    companion object {
        fun fromJson(o: JSONObject): MyPlan? = runCatching {
            MyPlan(
                id = o.getLong("i"),
                text = o.getString("x").trim().also { require(it.isNotEmpty()) },
                feeling = if (o.has("f")) runCatching { Opt.valueOf(o.getString("f")) }.getOrNull() else null,
                late = o.optBoolean("l", false)
            )
        }.getOrNull()

        fun listFromJson(text: String?): List<MyPlan> {
            if (text.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(text)
                (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }

        fun listToJson(plans: List<MyPlan>): String = JSONArray(plans.map { it.toJson() }).toString()

        /**
         * During a ride nothing has been asked yet, so the feeling is unknown: show the newest plan
         * that could apply, and prefer one made for late nights when it is late.
         */
        fun forRide(plans: List<MyPlan>, hour: Int): MyPlan? =
            plans.filter { !it.late || isLate(hour) }
                .sortedWith(compareBy<MyPlan>({ if (it.late) 0 else 1 }, { -it.id }))
                .firstOrNull()

        /** The saved plan that fits this moment best, or null. */
        fun best(plans: List<MyPlan>, feeling: Opt?, hour: Int): MyPlan? =
            plans.map { it to it.score(feeling, isLate(hour)) }.filter { it.second >= 0 }
                .maxByOrNull { it.second }?.first
    }
}

object Times {
    /** The next moment after [now] that is [minuteOfDay] minutes past local midnight. */
    fun nextDaily(minuteOfDay: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val nowAt = Instant.ofEpochMilli(now).atZone(zone)
        var at = nowAt.toLocalDate().atStartOfDay(zone).plusMinutes(minuteOfDay.toLong())
        if (!at.toInstant().isAfter(nowAt.toInstant())) at = at.plusDays(1)
        return at.toInstant().toEpochMilli()
    }

    fun clock(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
}

/** Which entries the log shows. */
enum class LogFilter {
    ALL, THROUGH, GAVE_IN;

    fun matches(e: Entry): Boolean = when (this) {
        ALL -> true
        THROUGH -> !e.gaveIn && e.outcome == Outcome.RESISTED
        GAVE_IN -> e.gaveIn
    }
}

/** The whole journal as a spreadsheet: one row per entry, opens in Excel or Sheets. */
object CsvExport {
    private const val HEADER = "time,kind,feeling,strength,pulled_toward,place,thought,outcome,after_ride,tried,note"

    private fun cell(text: String): String =
        if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text

    private fun name(o: Opt?): String = o?.name?.lowercase() ?: ""

    fun csv(entries: List<Entry>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        appendLine(HEADER)
        entries.sortedBy { it.time }.forEach { e ->
            val time = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(e.time).atZone(zone))
            val outcome = when {
                e.slipped -> "slipped"
                e.outcome == Outcome.RESISTED -> "got through"
                e.outcome == Outcome.GAVE_IN -> "gave in"
                else -> "open"
            }
            appendLine(
                listOf(
                    time,
                    if (e.slipped) "slip" else "urge",
                    name(e.answers[Q.FEELING]),
                    name(e.answers[Q.INTENSITY]),
                    name(e.answers[Q.PULL]),
                    name(e.answers[Q.PLACE]),
                    name(e.answers[Q.THOUGHT]),
                    outcome,
                    e.after?.name?.lowercase() ?: "",
                    e.tried.joinToString(" ") { it.name.lowercase() },
                    e.note
                ).joinToString(",") { cell(it) }
            )
        }
    }
}
