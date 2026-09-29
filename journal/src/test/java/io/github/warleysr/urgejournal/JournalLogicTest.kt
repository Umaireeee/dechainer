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

    // ---- AI ----

    @Test
    fun theModelReplyIsParsedEvenInsideCodeFences() {
        val reply = """
            Here you go:
            ```json
            {"headline":"You're using the phone to escape stress.","why":"Tired plus stressed lowers self-control.",
             "right_now":["Stand up","Cold water"],"today":["Eat something"],"this_week":["If it's 23:00, then phone charges outside."],
             "long_term":["Find a healthier release"],"understand":[{"title":"Urge surfing","body":"Urges peak and pass."}],
             "pattern":"","encouragement":"You logged it, and that counts."}
            ```
        """.trimIndent()
        val r = ReportParser.parse(reply)!!
        assertEquals("You're using the phone to escape stress.", r.headline)
        assertEquals(listOf("Stand up", "Cold water"), r.rightNow)
        assertEquals("Urge surfing" to "Urges peak and pass.", r.understand.first())
        assertTrue(r.pattern.isEmpty())
    }

    @Test
    fun junkRepliesAreNotAReport() {
        assertNull(ReportParser.parse("I can't help with that."))
        assertNull(ReportParser.parse("{}"))
        assertNull(ReportParser.parse("{ not json }"))
    }

    @Test
    fun httpResultsMapToUsefulErrors() {
        val ok = """{"choices":[{"message":{"content":"hello"}}]}"""
        assertEquals(AiResult.Ok("hello"), AiClient.interpret(200, ok))
        assertEquals(AiResult.Failed(AiError.BAD_KEY), AiClient.interpret(401, ""))
        assertEquals(AiResult.Failed(AiError.NO_CREDITS), AiClient.interpret(402, ""))
        assertEquals(AiResult.Failed(AiError.RATE_LIMIT), AiClient.interpret(429, ""))
        assertEquals(AiResult.Failed(AiError.SERVER), AiClient.interpret(503, ""))
        assertEquals(AiResult.Failed(AiError.EMPTY), AiClient.interpret(200, """{"choices":[]}"""))
    }

    @Test
    fun theCrisisCheckCatchesTheObviousAndLeavesOrdinaryNotesAlone() {
        assertTrue(Safety.needsSupport("Sometimes I just want to die"))
        assertTrue(Safety.needsSupport("thinking about self-harm"))
        assertFalse(Safety.needsSupport("I was bored and stressed about my exam"))
        assertFalse(Safety.needsSupport(""))
    }

    @Test
    fun thePromptCarriesTheAnswersAndTheNoteButNothingElse() {
        val e = Entry(
            ms(20, 23), false,
            mapOf(Q.FEELING to Opt.LONELY, Q.INTENSITY to Opt.STRONG, Q.PROBE_LONELY to Opt.LONELY_NO_ONE),
            null, note = "Roommate is away"
        )
        val text = Prompt.user(e, "Last 30 days: 5 entries.")
        assertTrue("Feeling: lonely" in text)
        assertTrue("no one around" in text)
        assertTrue("Roommate is away" in text)
        assertTrue("History digest: Last 30 days: 5 entries." in text)
    }

    @Test
    fun aSlipIsFlaggedInThePrompt() {
        val e = Entry(ms(20, 2), true, mapOf(Q.FEELING to Opt.TIRED, Q.GAP to Opt.GAP_BLOCKED), null)
        assertTrue("SLIPPED" in Prompt.user(e, ""))
        assertTrue("phone was blocked" in Prompt.user(e, ""))
    }

    @Test
    fun theWeekChartHasOneBarPerDayOldestFirst() {
        val now = ms(20, 12)
        val entries = listOf(
            Entry(ms(20, 9), false, emptyMap(), Outcome.RESISTED),
            Entry(ms(20, 10), true, emptyMap(), null),
            Entry(ms(18, 9), false, emptyMap(), Outcome.RESISTED)
        )
        val bars = Insights.days(entries, now, zone)
        assertEquals(7, bars.size)
        assertEquals(14, bars.first().date.dayOfMonth)
        assertEquals(1, bars.last().resisted)
        assertEquals(1, bars.last().gaveIn)
        assertEquals(1, bars[4].resisted)
    }

    @Test
    fun notesAndReportsSurviveStorage() {
        val e = Entry(9L, false, mapOf(Q.FEELING to Opt.BORED), null, note = "long day", report = "{\"headline\":\"x\"}")
        assertEquals(listOf(e), Entry.listFromJson(Entry.listToJson(listOf(e))))
    }

    @Test
    fun theDigestIsAnonymousAndOnlyAppearsWithEnoughHistory() {
        val now = ms(20, 12)
        assertEquals("", Insights.summary(listOf(Entry(ms(19, 1), false, emptyMap(), null)), now, zone))
        val many = (1..5).map { Entry(ms(15 + it % 4, 23), it % 2 == 0, mapOf(Q.FEELING to Opt.TIRED), null, note = "SECRET NOTE") }
        val digest = Insights.summary(many, now, zone)
        assertTrue("tired" in digest)
        assertFalse("SECRET NOTE" in digest)
    }

    // ---- Providers ----

    @Test
    fun aPastedKeyPicksTheProvider() {
        assertEquals(Provider.OPENROUTER, Provider.detect("sk-or-v1-abcdef"))
        assertEquals(Provider.GOOGLE, Provider.detect("AIzaSyExample"))
        assertEquals(Provider.OPENAI, Provider.detect("sk-proj-abc"))
        assertNull(Provider.detect("something-else"))
        assertNull(Provider.detect(""))
    }

    @Test
    fun endpointsAreBuiltFromTheBaseAddress() {
        assertEquals("https://openrouter.ai/api/v1/chat/completions", AiClient.endpoint(Provider.OPENROUTER.baseUrl))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            AiClient.endpoint(Provider.GOOGLE.baseUrl)
        )
        assertEquals("https://api.groq.com/openai/v1/chat/completions", AiClient.endpoint(" https://api.groq.com/openai/v1/ "))
    }

    @Test
    fun everyRealProviderHasADefaultModelInItsSuggestions() {
        for (p in Provider.entries.filter { it != Provider.CUSTOM }) {
            assertTrue(p.baseUrl.startsWith("https://"))
            assertTrue(p.defaultModel.isNotBlank())
            assertTrue(p.defaultModel in p.models)
        }
    }

    @Test
    fun serviceErrorsKeepTheirOwnMessage() {
        val google = """[{"error":{"code":400,"message":"API key not valid. Please pass a valid API key."}}]"""
        val r = AiClient.interpret(400, google) as AiResult.Failed
        assertEquals(AiError.BAD_KEY, r.error)
        assertTrue("API key not valid" in r.detail)

        val openrouter = """{"error":{"message":"No endpoints found for nope/model","code":404}}"""
        val m = AiClient.interpret(404, openrouter) as AiResult.Failed
        assertEquals(AiError.BAD_MODEL, m.error)
        assertTrue("nope/model" in m.detail)

        assertEquals(AiError.BAD_MODEL, (AiClient.interpret(400, """{"error":{"message":"Unknown model: Gemini"}}""") as AiResult.Failed).error)
    }

    @Test
    fun theModelListDropsNonChatModelsAndThePrefix() {
        val json = """{"object":"list","data":[
            {"id":"models/gemini-3.8-flash"},{"id":"models/text-embedding-004"},
            {"id":"models/imagen-4"},{"id":"models/gemini-flash-latest"},{"id":"models/gemini-3.8-flash"}]}"""
        assertEquals(listOf("gemini-3.8-flash", "gemini-flash-latest"), AiClient.parseModels(json))
        assertEquals(emptyList<String>(), AiClient.parseModels("nope"))
        assertEquals(emptyList<String>(), AiClient.parseModels("""{"data":[]}"""))
    }
}
