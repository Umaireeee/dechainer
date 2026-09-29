package io.github.warleysr.urgejournal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class JournalLogicTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private fun ms(day: Int, hour: Int) =
        LocalDateTime.of(2026, 9, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun theInterviewBranchesOnTheFeeling() {
        assertEquals(Q.FEELING, QuestionTree.next(emptyMap(), 12, false))
        val stressed = mapOf(Q.FEELING to Opt.STRESSED, Q.INTENSITY to Opt.MILD, Q.PULL to Opt.SCROLLING, Q.PLACE to Opt.DESK)
        assertEquals(Q.PROBE_STRESSED, QuestionTree.next(stressed, 12, false))
        val lonely = stressed + (Q.FEELING to Opt.LONELY)
        assertEquals(Q.PROBE_LONELY, QuestionTree.next(lonely, 12, false))
    }

    @Test
    fun lateNightAsksWhereThePhoneIs() {
        assertTrue(Q.PHONE_PLACE in QuestionTree.sequence(Opt.BORED, 23, false))
        assertFalse(Q.PHONE_PLACE in QuestionTree.sequence(Opt.BORED, 14, false))
    }

    @Test
    fun aSlipSkipsIntensityAndAsksWhatWasMissing() {
        val seq = QuestionTree.sequence(Opt.TIRED, 12, true)
        assertFalse(Q.INTENSITY in seq)
        assertEquals(listOf(Q.GAP, Q.STOPPER), seq.takeLast(2))
    }

    @Test
    fun theInterviewEnds() {
        val all = mutableMapOf<Q, Opt>()
        var guard = 0
        while (true) {
            val q = QuestionTree.next(all, 12, false) ?: break
            all[q] = q.options.first()
            assertTrue("interview never ends", ++guard < 30)
        }
        assertNull(QuestionTree.next(all, 12, false))
    }

    @Test
    fun strongerAndLaterUrgesGetLongerBlocks() {
        val mild = mapOf(Q.FEELING to Opt.BORED, Q.INTENSITY to Opt.MILD)
        val strong = mapOf(Q.FEELING to Opt.BORED, Q.INTENSITY to Opt.STRONG)
        assertEquals(15, Coach.plan(mild, 12, false).primary.minutes)
        assertEquals(60, Coach.plan(strong, 12, false).primary.minutes)
        // Late pushes a level up.
        assertEquals(30, Coach.plan(mild, 23, false).primary.minutes)
        assertEquals(120, Coach.plan(strong, 23, false).primary.minutes)
    }

    @Test
    fun aSlipGetsTheStrongestResponseAndARuleFromTheGap() {
        val a = mapOf(Q.FEELING to Opt.TIRED, Q.GAP to Opt.GAP_BLOCKED, Q.STOPPER to Opt.STOP_DISTANCE)
        val day = Coach.plan(a, 14, true)
        assertEquals(120, day.primary.minutes)
        assertEquals(180, Coach.plan(a, 23, true).primary.minutes)
        assertTrue(Rule.OFFLINE_URGE in day.rules || Rule.PHONE_CHARGES_OUTSIDE in day.rules)
        assertEquals(DoorAction.IMPULSE_BLOCK, day.primary.kind)
    }

    @Test
    fun aCalmDaytimeStressUrgeOffersFocusButNotAtNight() {
        val a = mapOf(Q.FEELING to Opt.STRESSED, Q.INTENSITY to Opt.MEDIUM)
        assertEquals(DoorAction.FOCUS_BLOCK, Coach.plan(a, 14, false).secondary?.kind)
        assertNull(Coach.plan(a, 23, false).secondary)
    }

    @Test
    fun blocksStayInsideDeChainersLimits() {
        for (h in 0..23) for (i in Opt.entries.filter { it in Q.INTENSITY.options }) for (s in listOf(false, true)) {
            val m = Coach.plan(mapOf(Q.INTENSITY to i), h, s).primary.minutes
            assertTrue(m in 15..360)
        }
    }

    @Test
    fun everyPlanHasStepsAndAReason() {
        for (f in Q.FEELING.options) {
            val p = Coach.plan(mapOf(Q.FEELING to f), 12, false)
            assertTrue(p.steps.isNotEmpty())
            assertTrue(p.reasons.isNotEmpty())
            assertTrue(p.steps.size <= 4)
        }
    }

    @Test
    fun entriesRoundTripAndSkipBadRows() {
        val e = Entry(5L, false, mapOf(Q.FEELING to Opt.BORED, Q.PLACE to Opt.BED), Outcome.RESISTED)
        val slip = Entry(6L, true, mapOf(Q.FEELING to Opt.LONELY), null)
        val back = Entry.listFromJson(Entry.listToJson(listOf(e, slip)))
        assertEquals(listOf(e, slip), back)
        assertEquals(emptyList<Entry>(), Entry.listFromJson("nope"))
        assertEquals(emptyList<Entry>(), Entry.listFromJson(null))
        assertEquals(1, Entry.listFromJson("""[{"s":false},{"t":2,"s":true,"a":{"FEELING":"BORED"}}]""").size)
    }

    @Test
    fun weekCountsSlipsAsGivenIn() {
        val now = ms(20, 12)
        val entries = listOf(
            Entry(ms(10, 23), false, mapOf(Q.FEELING to Opt.BORED), Outcome.GAVE_IN), // too old
            Entry(ms(15, 23), false, mapOf(Q.FEELING to Opt.BORED), Outcome.RESISTED),
            Entry(ms(16, 23), false, mapOf(Q.FEELING to Opt.BORED), Outcome.RESISTED),
            Entry(ms(18, 2), true, mapOf(Q.FEELING to Opt.LONELY), null)
        )
        val w = Insights.week(entries, now, zone)
        assertEquals(3, w.total)
        assertEquals(2, w.resisted)
        assertEquals(1, w.gaveIn)
        assertEquals(Opt.BORED, w.topFeeling)
        assertEquals(23, w.peakHour)
        // The old give-in doesn't count; the last one is the slip on the 18th.
        assertEquals(2, w.daysSinceGaveIn)
        assertNull(Insights.week(emptyList(), now, zone).daysSinceGaveIn)
    }

    /** Every enum that becomes text must have a string, or the app would show a raw name. */
    @Test
    fun everyLabelHasAString() {
        val xml = listOf("src/main/res/values/strings.xml", "journal/src/main/res/values/strings.xml")
            .map(::File).firstOrNull { it.exists() }
        assertNotNull("strings.xml not found from ${File(".").absolutePath}", xml)
        val names = Regex("""<string name="([a-z0-9_]+)"""").findAll(xml!!.readText()).map { it.groupValues[1] }.toSet()
        val missing = buildList {
            Q.entries.forEach { if ("q_${it.name.lowercase()}" !in names) add("q_${it.name.lowercase()}") }
            Opt.entries.forEach { if ("opt_${it.name.lowercase()}" !in names) add("opt_${it.name.lowercase()}") }
            Step.entries.forEach { if ("step_${it.name.lowercase()}" !in names) add("step_${it.name.lowercase()}") }
            Rule.entries.forEach { if ("rule_${it.name.lowercase()}" !in names) add("rule_${it.name.lowercase()}") }
            Reason.entries.forEach { if ("reason_${it.name.lowercase()}" !in names) add("reason_${it.name.lowercase()}") }
        }
        assertEquals("missing strings: $missing", emptyList<String>(), missing)
    }
}
