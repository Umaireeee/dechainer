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
            Step.entries.forEach { if ("try_${it.name.lowercase()}" !in names) add("try_${it.name.lowercase()}") }
            DayResult.entries.forEach { if ("day_${it.name.lowercase()}" !in names) add("day_${it.name.lowercase()}") }
            After.entries.forEach { if ("after_${it.name.lowercase()}" !in names) add("after_${it.name.lowercase()}") }
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
        assertNull(Provider.detect("sk-plain123")) // DeepSeek and others also use this shape
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
        assertEquals("https://api.deepseek.com/chat/completions", AiClient.endpoint(Provider.DEEPSEEK.baseUrl))
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

    // ---- Final-version additions ----

    @Test
    fun afterASlipByDayThePlanOffersFocusToGetBackOnTrack() {
        val a = mapOf(Q.FEELING to Opt.TIRED)
        assertEquals(DoorAction.FOCUS_BLOCK, Coach.plan(a, 14, true).secondary?.kind)
        assertNull(Coach.plan(a, 23, true).secondary)
    }

    @Test
    fun theAboutTextReachesThePromptsAndNotesStayOutOfTheWeeklyOne() {
        val e = Entry(ms(20, 14), false, mapOf(Q.FEELING to Opt.BORED), null, note = "PRIVATE NOTE")
        assertTrue("About them (their own words): ACCA exams in December" in Prompt.user(e, "", "ACCA exams in December"))
        val weekly = Prompt.weeklyUser(listOf(e), ms(20, 15), "ACCA exams in December", zone)
        assertTrue("ACCA exams in December" in weekly)
        assertFalse("PRIVATE NOTE" in weekly)
        assertTrue("Last 7 days: 1 entries" in weekly)
        assertTrue("SUNDAY" in weekly || "MONDAY" in weekly)
    }

    @Test
    fun aboutTextIsCappedWhereItIsSent() {
        val long = "x".repeat(ABOUT_LIMIT + 500)
        val e = Entry(ms(20, 14), false, mapOf(Q.FEELING to Opt.BORED), null)
        val sent = Prompt.user(e, "", long).lines().first { it.startsWith("About them") }
        assertTrue(sent.length <= "About them (their own words): ".length + ABOUT_LIMIT)
    }

    @Test
    fun mergingABackupKeepsOneEntryPerTime() {
        val mine = listOf(Entry(1L, false, mapOf(Q.FEELING to Opt.BORED), Outcome.RESISTED), Entry(3L, true, emptyMap(), null))
        val backup = listOf(Entry(1L, false, mapOf(Q.FEELING to Opt.LONELY), null), Entry(2L, false, emptyMap(), null), Entry(2L, true, emptyMap(), null))
        val merged = Entry.merge(mine, backup)
        assertEquals(listOf(1L, 2L, 3L), merged.map { it.time })
        // The entry already on this phone wins.
        assertEquals(Opt.BORED, merged.first().answers[Q.FEELING])
    }

    @Test
    fun theWeeklyReviewReplyParsesLikeADeepDive() {
        val reply = """{"headline":"A steadier week","why":"Most urges came late.","right_now":["Start the block at 21:30"],
            "today":[],"this_week":["If it is 22:00, then the phone charges in the hall."],"long_term":["Sleep more"],
            "understand":[],"pattern":"Late nights","encouragement":"Three ridden out is real."}"""
        val r = ReportParser.parse(reply)!!
        assertEquals("A steadier week", r.headline)
        assertEquals(listOf("Start the block at 21:30"), r.rightNow)
        assertTrue(r.today.isEmpty())
        assertEquals("Late nights", r.pattern)
    }

    @Test
    fun theDepthSettingChangesWhatTheAiIsAskedFor() {
        val deep = Prompt.systemFor(true)
        val short = Prompt.systemFor(false)
        assertTrue("thorough, substantial read" in deep)
        assertTrue("3 to 4 sentences" in deep)
        assertTrue("keep it tight" in short)
        assertFalse("3 to 4 sentences" in short)
        // Both keep the fixed reply shape and the safety rule.
        for (s in listOf(deep, short)) {
            assertTrue("\"right_now\"" in s)
            assertTrue("crisis" in s)
        }
    }

    @Test
    fun aReplyInTheNewShapeParsesAndAnOldOneStillReads() {
        val modern = ReportParser.parse(
            """{"headline":"h","reality_check":"r","realization":"z","right_now":["a","b"],"your_line":"If x, then y.",
               "trap":"t","reply":"p","question":"q?","why":"","today":[],"this_week":[],"long_term":[],"understand":[],
               "pattern":"","encouragement":"e"}"""
        )!!
        assertEquals("r", modern.realityCheck)
        assertEquals("z", modern.realization)
        assertEquals("If x, then y.", modern.yourLine)
        assertEquals("q?", modern.question)
        assertTrue(modern.isModern)
        val old = ReportParser.parse("""{"headline":"h","why":"w","right_now":["a"],"today":[],"this_week":[],"long_term":[],"understand":[],"pattern":"","encouragement":"e"}""")!!
        assertFalse(old.isModern)
        assertEquals("w", old.why)
    }

    // ---- Ride, check-in, plans, patterns ----

    private fun ride(day: Int, hour: Int, after: After, vararg tried: Step) =
        Entry(ms(day, hour), false, emptyMap(), after.outcome, tried = tried.toList(), after = after)

    @Test
    fun aRideEntryRoundTripsWithWhatWasTriedAndHowItEnded() {
        val e = ride(5, 23, After.WEAKER, Step.LEAVE_ROOM, Step.COLD_WATER)
        val back = Entry.listFromJson(Entry.listToJson(listOf(e)))
        assertEquals(listOf(e), back)
        assertEquals(Outcome.RESISTED, back.first().outcome)
        // Old entries, written before rides existed, still read fine.
        assertEquals(1, Entry.listFromJson("""[{"t":2,"s":false,"a":{"FEELING":"BORED"}}]""").size)
        assertNull(ride(5, 23, After.STILL).outcome)
    }

    @Test
    fun theQuickInterviewIsThreeQuestionsAndAlwaysFullAfterASlip() {
        assertEquals(listOf(Q.FEELING, Q.PLACE, Q.THOUGHT), QuestionTree.sequence(Opt.BORED, 14, false, quick = true))
        assertEquals(listOf(Q.FEELING, Q.PLACE, Q.THOUGHT, Q.PHONE_PLACE), QuestionTree.sequence(Opt.BORED, 23, false, quick = true))
        assertEquals(QuestionTree.sequence(Opt.TIRED, 12, true), QuestionTree.sequence(Opt.TIRED, 12, true, quick = true))
        assertEquals(Q.FEELING, QuestionTree.next(emptyMap(), 12, false, quick = true))
    }

    @Test
    fun stepsThatWorkedForYouMoveUpOnceThereIsEvidence() {
        val answers = mapOf(Q.FEELING to Opt.BORED, Q.INTENSITY to Opt.MILD)
        val plain = Coach.plan(answers, 12, false)
        // Two tries is not evidence; the plan is unchanged.
        val few = listOf(ride(1, 12, After.GONE, Step.COLD_WATER), ride(2, 12, After.GONE, Step.COLD_WATER))
        assertEquals(plain.steps, Coach.plan(answers, 12, false, few).steps)
        // Four good tries is: cold water now leads, even though the rules didn't pick it.
        val many = (1..4).map { ride(it, 12, After.GONE, Step.COLD_WATER) }
        val ranked = Coach.plan(answers, 12, false, many).steps
        assertEquals(Step.COLD_WATER, ranked.first())
        assertTrue(ranked.size <= 4)
        // A step that keeps failing drops to the back.
        val bad = (1..4).map { ride(it, 12, After.STILL, Step.BREATHE) }
        assertEquals(Step.BREATHE, Coach.plan(answers, 12, false, bad).steps.last())
    }

    @Test
    fun theRideStepDefaultsToLeavingTheRoomAndLearnsFromYou() {
        assertEquals(Step.LEAVE_ROOM, Coach.rideStep(emptyList()))
        val walks = (1..4).map { ride(it, 12, After.WEAKER, Step.WALK) }
        assertEquals(Step.WALK, Coach.rideStep(walks))
    }

    @Test
    fun cleanDaysAreCountedAgainstTheDaysYouHaveBeenLogging() {
        val now = ms(20, 12)
        assertNull(Insights.cleanDays(emptyList(), now, zone))
        val entries = listOf(
            Entry(ms(11, 9), false, mapOf(Q.FEELING to Opt.BORED), Outcome.RESISTED),
            Entry(ms(14, 22), true, mapOf(Q.FEELING to Opt.TIRED), null),
            Entry(ms(14, 23), true, mapOf(Q.FEELING to Opt.TIRED), null) // same day: still one day
        )
        // Logging since the 11th: ten days including today, one of them with a slip.
        assertEquals(9 to 10, Insights.cleanDays(entries, now, zone))
        // A journal older than the window is measured over the window only.
        val old = listOf(Entry(ms(1, 9), false, emptyMap(), Outcome.RESISTED))
        assertEquals(20 to 20, Insights.cleanDays(old, now, zone))
    }

    @Test
    fun aHotWindowNeedsEnoughEntriesAndAClearMajority() {
        val now = ms(28, 12)
        fun at(day: Int, hour: Int) = Entry(ms(day, hour), false, mapOf(Q.FEELING to Opt.TIRED), Outcome.RESISTED)
        // Too few entries: quiet, however tidy.
        assertNull(Insights.hotWindow(listOf(at(20, 23), at(21, 23), at(22, 23), at(23, 23)), now, zone))
        // Six entries, five inside 23:00 to 03:00: a pattern, starting where the trouble starts.
        val late = listOf(at(20, 23), at(21, 23), at(22, 0), at(23, 23), at(24, 2), at(25, 14))
        val hot = Insights.hotWindow(late, now, zone)
        assertNotNull(hot)
        assertEquals(23, hot!!.startHour)
        assertEquals(3, hot.endHour)
        assertEquals(5, hot.count)
        assertEquals(6, hot.total)
        assertEquals(Opt.TIRED, hot.topFeeling)
        // The heads-up comes half an hour before the window opens.
        assertEquals(22 * 60 + 30, hot.nudgeMinute)
        // Spread all over the day: no pattern.
        val spread = listOf(at(20, 7), at(21, 13), at(22, 19), at(23, 23), at(24, 9), at(25, 15))
        assertNull(Insights.hotWindow(spread, now, zone))
    }

    @Test
    fun aWindowAcrossAnHourBoundaryIsNotSplit() {
        val now = ms(28, 12)
        fun at(day: Int, hour: Int) = Entry(ms(day, hour), false, emptyMap(), Outcome.RESISTED)
        // Urges at 21, 22 and 23 would be cut in two by fixed windows; a sliding one keeps them.
        val evening = listOf(at(20, 21), at(21, 22), at(22, 23), at(23, 21), at(24, 22), at(25, 10))
        val hot = Insights.hotWindow(evening, now, zone)
        assertNotNull(hot)
        assertEquals(21, hot!!.startHour)
        assertEquals(5, hot.count)
    }

    @Test
    fun theTwelveWeekViewGroupsByWeekStartingMonday() {
        val now = ms(30, 12) // Wednesday 30 Sep 2026
        fun at(day: Int, outcome: Outcome?, slipped: Boolean = false) = Entry(ms(day, 12), slipped, emptyMap(), outcome)
        val entries = listOf(
            at(28, Outcome.RESISTED), // this week's Monday
            at(30, null, slipped = true),
            at(27, Outcome.RESISTED), // Sunday: last week
            at(21, Outcome.GAVE_IN)   // Monday of last week
        )
        val weeks = Insights.weeks(entries, now, zone, 12)
        assertEquals(12, weeks.size)
        assertEquals(java.time.LocalDate.of(2026, 9, 28), weeks.last().date)
        assertEquals(1, weeks.last().resisted)
        assertEquals(1, weeks.last().gaveIn)
        assertEquals(1, weeks[10].resisted)
        assertEquals(1, weeks[10].gaveIn)
    }

    @Test
    fun aDayOrWeekCanBePickedFromTheChart() {
        val a = Entry(ms(10, 8), false, emptyMap(), null)
        val b = Entry(ms(10, 22), false, emptyMap(), null)
        val c = Entry(ms(12, 9), false, emptyMap(), null)
        val d10 = java.time.LocalDate.of(2026, 9, 10)
        assertEquals(listOf(a, b), Insights.entriesIn(listOf(a, b, c), d10, d10, zone))
        assertEquals(listOf(a, b, c), Insights.entriesIn(listOf(a, b, c), d10, d10.plusDays(6), zone))
        assertEquals(emptyList<Entry>(), Insights.entriesIn(listOf(a, b, c), d10.plusDays(20), d10.plusDays(26), zone))
    }

    @Test
    fun theLogCountsFeelingsAndFiltersByHowItWentPlusCsvExport() {
        val es = listOf(
            Entry(1, false, mapOf(Q.FEELING to Opt.BORED), Outcome.RESISTED),
            Entry(2, false, mapOf(Q.FEELING to Opt.BORED), Outcome.GAVE_IN),
            Entry(3, true, mapOf(Q.FEELING to Opt.LONELY), null),
            Entry(4, false, emptyMap(), null)
        )
        assertEquals(listOf(Opt.BORED to 2, Opt.LONELY to 1), Insights.feelingCounts(es))
        assertEquals(4, es.count { LogFilter.ALL.matches(it) })
        assertEquals(1, es.count { LogFilter.THROUGH.matches(it) })
        assertEquals(2, es.count { LogFilter.GAVE_IN.matches(it) })

        val csv = CsvExport.csv(
            listOf(Entry(ms(5, 23), false, mapOf(Q.FEELING to Opt.BORED), Outcome.RESISTED, note = "said \"no\", twice", tried = listOf(Step.WALK, Step.COLD_WATER), after = After.WEAKER)),
            zone
        )
        val lines = csv.lines()
        assertEquals("time,kind,feeling,strength,pulled_toward,place,thought,outcome,after_ride,tried,note", lines[0])
        assertEquals("2026-09-05 23:00,urge,bored,,,,,got through,weaker,walk cold_water,\"said \"\"no\"\", twice\"", lines[1])
    }

    @Test
    fun aRideStubIsReplacedByItsFullEntry() {
        val stub = Entry(ms(5, 23), false, emptyMap(), null)
        val full = stub.copy(outcome = Outcome.RESISTED, after = After.GONE, tried = listOf(Step.WALK))
        val list = listOf(stub)
        val next = if (list.any { it.time == full.time }) list.map { if (it.time == full.time) full else it } else list + full
        assertEquals(listOf(full), next)
    }

    @Test
    fun heavierMeansMuchMoreLatelyOrSeveralOverwhelmingOnes() {
        val now = ms(28, 12)
        fun at(day: Int, intensity: Opt? = null) =
            Entry(ms(day, 12), false, if (intensity != null) mapOf(Q.INTENSITY to intensity) else emptyMap(), Outcome.RESISTED)
        assertFalse(Insights.heavier(emptyList(), now))
        val surge = (16..21).map { at(it) }
        assertTrue(Insights.heavier(surge, now))
        // The same number, but the fortnight before was just as busy: steady, not heavier.
        val steady = surge + (2..7).map { at(it) }
        assertFalse(Insights.heavier(steady, now))
        val overwhelmed = listOf(at(20, Opt.OVERWHELMING), at(22, Opt.OVERWHELMING), at(24, Opt.OVERWHELMING))
        assertTrue(Insights.heavier(overwhelmed, now))
    }

    @Test
    fun theShareTextHasCountsOnly() {
        val now = ms(20, 12)
        val e = listOf(Entry(ms(19, 23), false, mapOf(Q.FEELING to Opt.LONELY, Q.PLACE to Opt.BED), Outcome.RESISTED, note = "private words"))
        val text = Insights.shareText(e, now, zone)
        assertTrue(text.contains("1 urges logged"))
        assertFalse(text.contains("private"))
        assertFalse(text.contains("lonely", ignoreCase = true))
        assertFalse(text.contains("bed", ignoreCase = true))
    }

    @Test
    fun myPlansMatchTheMomentAndRoundTrip() {
        val late = MyPlan(3, "If I feel tired, late at night, then the phone charges in the kitchen.", Opt.TIRED, true)
        val bored = MyPlan(4, "If I feel bored, then I start one question.", Opt.BORED, false)
        val any = MyPlan(5, "If in doubt, I stand up.", null, false)
        val plans = listOf(late, bored, any)
        assertEquals(late, MyPlan.best(plans, Opt.TIRED, 23))
        // Tired but in the afternoon: the late plan doesn't apply, so the general one does.
        assertEquals(any, MyPlan.best(plans, Opt.TIRED, 14))
        assertEquals(bored, MyPlan.best(plans, Opt.BORED, 14))
        assertNull(MyPlan.best(listOf(bored), Opt.LONELY, 14))
        // During a ride the feeling is unknown: late plans only when it is late, newest first.
        assertEquals(late, MyPlan.forRide(plans, 23))
        assertEquals(any, MyPlan.forRide(plans, 14))
        assertNull(MyPlan.forRide(emptyList(), 14))
        assertEquals(plans, MyPlan.listFromJson(MyPlan.listToJson(plans)))
        assertEquals(emptyList<MyPlan>(), MyPlan.listFromJson("nope"))
    }

    @Test
    fun theDailyHeadsUpIsAlwaysInTheFuture() {
        val at = 21 * 60 + 30
        assertEquals(ms(20, 21) + 30 * 60_000L, Times.nextDaily(at, ms(20, 12), zone))
        // Already past today's time: tomorrow's.
        assertEquals(ms(21, 21) + 30 * 60_000L, Times.nextDaily(at, ms(20, 22), zone))
        assertEquals("21:30", Times.clock(at))
        assertEquals("07:05", Times.clock(7 * 60 + 5))
    }

    @Test
    fun theRideIsInThePromptButNotThePrivateNote() {
        val e = Entry(ms(5, 23), false, mapOf(Q.FEELING to Opt.BORED), Outcome.RESISTED,
            tried = listOf(Step.LEAVE_ROOM), after = After.WEAKER)
        val prompt = Prompt.user(e, "")
        assertTrue(prompt.contains("weaker"))
        assertTrue(prompt.contains("leave room"))
    }

    @Test
    fun anOpenRideIsNeitherRiddenOutNorGivenIn() {
        val now = ms(20, 12)
        val open = Entry(ms(20, 9), false, emptyMap(), null)
        val still = Entry(ms(20, 10), false, emptyMap(), After.STILL.outcome, after = After.STILL)
        val done = Entry(ms(20, 11), false, emptyMap(), After.GONE.outcome, after = After.GONE)
        assertFalse(open.ridden)
        assertFalse(still.ridden)
        assertTrue(done.ridden)
        val bar = Insights.days(listOf(open, still, done), now, zone).last()
        assertEquals(1, bar.resisted)
        assertEquals(0, bar.gaveIn)
        assertEquals(1, Insights.week(listOf(open, still, done), now, zone).resisted)
    }

    @Test
    fun theCrisisCheckHandlesCurlyApostrophesAndCommonPhrases() {
        assertTrue(Safety.needsSupport("I don\u2019t want to live like this"))
        assertTrue(Safety.needsSupport("i just want to end it all"))
        assertTrue(Safety.needsSupport("Everyone would be better off dead"))
        assertFalse(Safety.needsSupport("I was bored and scrolled for an hour"))
    }

    @Test
    fun planDaysCountDaysThatWentToPlan() {
        val today = java.time.LocalDate.of(2026, 9, 28)
        assertNull(Insights.planDays(emptyMap(), today))
        val days = mapOf(
            today to DayResult.PLANNED,
            today.minusDays(1) to DayResult.PARTLY,
            today.minusDays(2) to DayResult.PLANNED,
            today.minusDays(10) to DayResult.PLANNED // outside the week
        )
        assertEquals(2 to 3, Insights.planDays(days, today))
    }

    @Test
    fun theRideLockCommandIsNamedTheWayDeChainerExpectsIt() {
        assertEquals("RIDE_LOCK", DoorAction.RIDE_LOCK)
        assertEquals("IMPULSE_BLOCK", DoorAction.IMPULSE_BLOCK)
        assertEquals("FOCUS_BLOCK", DoorAction.FOCUS_BLOCK)
    }

    // ---- Batch 1: storage that never loses data ----

    @Test
    fun oneBadEntryDoesNotCostTheOthersAndIsKept() {
        val good = Entry(5L, false, mapOf(Q.FEELING to Opt.BORED), Outcome.RESISTED).toJson()
        val text = org.json.JSONArray().put(good).put("junk").put(org.json.JSONObject().put("s", true)).put(
            Entry(6L, true, emptyMap(), null).toJson()
        ).toString()
        val read = Entry.parseList(text)
        assertTrue(read.rootOk)
        assertEquals(listOf(5L, 6L), read.items.map { it.time })
        assertEquals(2, read.unreadable.size)
        // Saving puts the unreadable rows back.
        val saved = Entry.composeList(read.items + Entry(7L, false, emptyMap(), null), read.unreadable)
        val again = Entry.parseList(saved)
        assertEquals(listOf(5L, 6L, 7L), again.items.map { it.time })
        assertEquals(2, again.unreadable.size)
    }

    @Test
    fun aStoredValueThatIsNotAListIsNotEmptyItIsUnreadable() {
        assertFalse(Entry.parseList("{oops").rootOk)
        assertFalse(MyPlan.parseList("not json").rootOk)
        assertTrue(Entry.parseList(null).rootOk)
        assertTrue(Entry.parseList("").items.isEmpty())
    }

    @Test
    fun oneBadPlanDoesNotCostTheOthers() {
        val text = org.json.JSONArray()
            .put(MyPlan(1, "If tired, then bed.", Opt.TIRED, true).toJson())
            .put(org.json.JSONObject().put("x", "   "))
            .put(MyPlan(2, "If bored, then walk.", Opt.BORED, false).toJson())
            .toString()
        val read = MyPlan.parseList(text)
        assertEquals(listOf(1L, 2L), read.items.map { it.id })
        assertEquals(1, read.unreadable.size)
    }

    // ---- Batch 3 ----

    @Test
    fun theEmptyNoteARideLeavesIsNotDataUntilItIsAnswered() {
        val now = ms(20, 12)
        val stub = Entry(ms(20, 9), false, emptyMap(), null)
        assertTrue(stub.isStub)
        assertFalse(stub.copy(outcome = Outcome.RESISTED).isStub)
        assertFalse(stub.copy(after = After.STILL).isStub)
        assertFalse(stub.copy(note = "hard evening").isStub)
        assertFalse(Entry(ms(20, 9), true, emptyMap(), null).isStub) // a slip is never a stub
        // Statistics ignore stubs: an accidental tap is not an urge.
        assertEquals(0, Insights.week(listOf(stub), now, zone).total)
        assertEquals(0, Insights.week(listOf(stub, stub.copy(time = ms(19, 9))), now, zone).total)
        assertEquals(1, Insights.week(listOf(stub, stub.copy(time = ms(19, 9), outcome = Outcome.RESISTED)), now, zone).total)
    }

    @Test
    fun stubsDoNotMakeAPatternOrAHeavierFortnight() {
        val now = ms(28, 12)
        val stubs = (16..25).map { Entry(ms(it, 23), false, emptyMap(), null) }
        assertNull(Insights.hotWindow(stubs, now, zone))
        assertFalse(Insights.heavier(stubs, now))
        assertEquals("", Insights.summary(stubs, now, zone))
    }

    @Test
    fun theCheckInIsPutBackAfterARebootUnlessTheRideIsTooOld() {
        val delay = 20 * 60_000L
        val maxAge = 6 * 60 * 60_000L
        val start = 1_000_000_000L
        // Rebooted 5 minutes into the ride: the original moment is still ahead.
        assertEquals(start + delay, Times.checkInAt(start, start + 5 * 60_000L, delay, maxAge))
        // Rebooted an hour in: the moment has passed, so it fires a minute from now.
        assertEquals(start + 3_600_000L + 60_000L, Times.checkInAt(start, start + 3_600_000L, delay, maxAge))
        // Too old, or no ride waiting: nothing.
        assertNull(Times.checkInAt(start, start + maxAge + 1, delay, maxAge))
        assertNull(Times.checkInAt(0L, start, delay, maxAge))
    }

    @Test
    fun aRideAskedForFromOutsideCountsDownAndNeverRestartsARunningOne() {
        val now = 10_000_000L
        val ten = 10 * 60L
        assertEquals(RideRequest.Decision.COUNTDOWN, RideRequest.decide(0L, now, ten, alreadyCountingDown = false))
        // A ride started 3 minutes ago is shown again, not restarted (a double tap on the tile).
        assertEquals(RideRequest.Decision.RESUME, RideRequest.decide(now - 3 * 60_000L, now, ten, false))
        // An old ride no longer counts as running.
        assertEquals(RideRequest.Decision.COUNTDOWN, RideRequest.decide(now - 11 * 60_000L, now, ten, false))
        // A second request during the countdown does nothing.
        assertEquals(RideRequest.Decision.IGNORE, RideRequest.decide(0L, now, ten, alreadyCountingDown = true))
        assertEquals(5, RideRequest.COUNTDOWN_SECONDS)
    }

    @Test
    fun theRideShowsTheRealLockStateNotJustThatARequestWasSent() {
        val now = 5_000L
        assertEquals(LockState.CHECKING, RideStatus.lockState(null, checked = false, now = now))
        assertEquals(LockState.UNREACHABLE, RideStatus.lockState(null, checked = true, now = now))
        assertEquals(LockState.NOT_DEVICE_OWNER, RideStatus.lockState(DoorStatus(false, now + 1000, 0), true, now))
        assertEquals(LockState.ACTIVE, RideStatus.lockState(DoorStatus(true, now + 1000, 0), true, now))
        assertEquals(LockState.NOT_ACTIVE, RideStatus.lockState(DoorStatus(true, 0L, 0L), true, now))
        assertEquals(LockState.NOT_ACTIVE, RideStatus.lockState(DoorStatus(true, now - 1, 0L), true, now))
    }

    @Test
    fun oldExportFilesAreDeletedAndNewOnesKept() {
        val dir = java.nio.file.Files.createTempDirectory("exports").toFile()
        try {
            val old = java.io.File(dir, "old.csv").apply { writeText("x"); setLastModified(1_000L) }
            val fresh = java.io.File(dir, "fresh.csv").apply { writeText("y"); setLastModified(9_000L) }
            assertEquals(1, ExportFiles.cleanup(dir, olderThanMs = 5_000L, now = 10_000L))
            assertFalse(old.exists())
            assertTrue(fresh.exists())
            // Zero age removes everything, which is what happens just before a new export is written.
            assertEquals(1, ExportFiles.cleanup(dir, olderThanMs = 0L, now = 10_000L))
            assertEquals(0, ExportFiles.cleanup(java.io.File(dir, "missing"), 0L, 10_000L))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun clockTimesAreFormattedInThePhonesZone() {
        assertEquals("14:32", Times.clockAt(ms(5, 14) + 32 * 60_000L, zone))
    }

    // ---- Batch 4 (journal) ----

    @Test
    fun aKeyIsNeverSavedAsPlainTextWithoutAgreement() {
        assertEquals(KeyStorage.SEALED, KeyPolicy.decide("sealed-text", allowPlain = false))
        assertEquals(KeyStorage.SEALED, KeyPolicy.decide("sealed-text", allowPlain = true))
        assertEquals(KeyStorage.REFUSED, KeyPolicy.decide(null, allowPlain = false))
        assertEquals(KeyStorage.PLAIN, KeyPolicy.decide(null, allowPlain = true))
    }

    @Test
    fun googleFallsBackToTheLatestFlashModelOnAnUnknownModel() {
        assertEquals("gemini-flash-latest", AiClient.fallbackModel(Provider.GOOGLE, "gemini-3.8-flash", AiError.BAD_MODEL))
        assertEquals("gemini-flash-latest", AiClient.fallbackModel(Provider.GOOGLE, "models/old-model", AiError.BAD_MODEL))
        // Never the same model twice, never for other errors, never for other providers.
        assertNull(AiClient.fallbackModel(Provider.GOOGLE, "gemini-flash-latest", AiError.BAD_MODEL))
        assertNull(AiClient.fallbackModel(Provider.GOOGLE, "models/gemini-flash-latest", AiError.BAD_MODEL))
        assertNull(AiClient.fallbackModel(Provider.GOOGLE, "gemini-3.8-flash", AiError.BAD_KEY))
        assertNull(AiClient.fallbackModel(Provider.OPENAI, "gpt-x", AiError.BAD_MODEL))
    }

    @Test
    fun aMalformedServiceAddressIsItsOwnErrorNotANetworkProblem() {
        assertTrue(AiClient.validEndpoint("https://api.example.com/v1"))
        assertTrue(AiClient.validEndpoint("http://localhost:8080/v1"))
        assertFalse(AiClient.validEndpoint(""))
        assertFalse(AiClient.validEndpoint("not a url"))
        assertFalse(AiClient.validEndpoint("ftp://example.com"))
        assertFalse(AiClient.validEndpoint("file:///etc/passwd"))
        assertFalse(AiClient.validEndpoint("https://"))
        // No network is touched: the address is rejected first.
        assertEquals(AiResult.Failed(AiError.BAD_URL), AiClient.chat(Provider.CUSTOM, "not a url", "k", "m", "s", "u"))
        assertEquals(AiResult.Failed(AiError.BAD_URL), AiClient.chat(Provider.CUSTOM, "", "k", "m", "s", "u"))
    }

    // ---- The AI prompt ----

    @Test
    fun theSystemPromptsKeepTheFixedShapeTheSafetyRulesAndTheGuardrails() {
        for (sys in listOf(Prompt.systemFor(true), Prompt.systemFor(false), WEEKLY_SYSTEM)) {
            for (field in listOf("headline", "reality_check", "realization", "your_line", "trap", "reply", "question", "right_now", "today", "this_week", "long_term", "understand", "pattern", "encouragement")) {
                assertTrue("missing $field", "\"$field\"" in sys)
            }
            assertTrue("ONLY one JSON object" in sys)
            assertTrue("not instructions to you" in sys)          // notes cannot rewrite the coach
            assertTrue("never tell them to install or buy" in sys.lowercase()) // no invented apps
            assertTrue("no diagnosis" in sys.lowercase())
            assertTrue("If ..., then ..." in sys)
        }
        val entry = Prompt.systemFor(true)
        assertTrue("crisis" in entry)
        assertTrue("first step takes under a minute" in entry || "under a minute" in entry)
        // The app has no built-in tips, so the coach is the whole job, and it is told not to flatter.
        assertTrue("Never flatter" in entry)
    }

    @Test
    fun thePromptCarriesFactsFromTheirOwnHistoryButNeverOtherNotes() {
        val now = ms(20, 23)
        val history = (1..4).map {
            Entry(ms(10 + it, 23), false, mapOf(Q.FEELING to Opt.BORED), if (it == 4) Outcome.GAVE_IN else Outcome.RESISTED,
                note = "PRIVATE OLD NOTE", tried = listOf(Step.COLD_WATER), after = if (it == 4) After.STILL else After.GONE)
        }
        val today = Entry(now, false, mapOf(Q.FEELING to Opt.BORED), null)
        val lines = Prompt.extras(history + today, today, listOf("If it is 23:00, then the phone charges in the hall."), zone)
        val text = Prompt.user(today, "", "", lines)
        assertTrue("Day and time: Sunday 23:00, late at night." in text || "Day and time:" in text)
        assertTrue("logged feeling bored 4 times before: 3 ridden out, 1 given in to" in text)
        assertTrue("cold water 3 of 4" in text)
        assertTrue("Rules they wrote for themselves (their own words): If it is 23:00" in text)
        assertFalse("PRIVATE OLD NOTE" in text)
        // A feeling never logged before is said to be new, not guessed at.
        val first = Entry(now, false, mapOf(Q.FEELING to Opt.LONELY), null)
        assertTrue("first time" in Prompt.extras(listOf(first), first, emptyList(), zone).joinToString(" "))
    }

    @Test
    fun stepEvidenceNeedsThreeTriesAndListsTheBestFirst() {
        val walks = (1..3).map { Entry(ms(it, 12), false, emptyMap(), null, tried = listOf(Step.WALK), after = After.GONE) }
        val breathing = (4..9).map { Entry(ms(it, 12), false, emptyMap(), null, tried = listOf(Step.BREATHE), after = if (it < 6) After.GONE else After.STILL) }
        val once = listOf(Entry(ms(10, 12), false, emptyMap(), null, tried = listOf(Step.SLEEP), after = After.GONE))
        val ev = Insights.stepEvidence(walks + breathing + once)
        assertEquals(listOf(Step.WALK, Step.BREATHE), ev.map { it.step })
        assertEquals(3 to 3, ev[0].wins to ev[0].tries)
        assertEquals(2 to 6, ev[1].wins to ev[1].tries)
    }

    @Test
    fun theWeeklyPromptGetsRideResultsAndStepCountsButStillNoNotes() {
        val now = ms(20, 15)
        val es = (14..19).map {
            Entry(ms(it, 22), false, mapOf(Q.FEELING to Opt.TIRED), Outcome.RESISTED, note = "SECRET",
                tried = listOf(Step.LEAVE_ROOM), after = if (it % 2 == 0) After.GONE else After.WEAKER)
        }
        val weekly = Prompt.weeklyUser(es, now, "", zone)
        assertTrue("Rides this week: 6 (3 passed, 3 got weaker, 0 still strong)." in weekly)
        assertTrue("leave room 6 of 6" in weekly)
        assertFalse("SECRET" in weekly)
    }

    // ---- Streaming ----

    private fun sse(vararg lines: String) = java.io.BufferedReader(java.io.StringReader(lines.joinToString("\n")))

    @Test
    fun aStreamedReplyIsReadPieceByPieceAndIgnoresNoise() {
        val shown = mutableListOf<String>()
        val text = Sse.read(
            sse(
                """data: {"id":"x","choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""",
                """data: {"choices":[{"delta":{"content":"{\"headline\": \"You"}}]}""",
                ": OPENROUTER PROCESSING",
                "",
                """data: {"choices":[{"delta":{"content":" are tired\","}}]}""",
                """data: {"choices":[{"delta":{"content":null},"finish_reason":"stop"}]}""",
                "data: [DONE]",
                """data: {"choices":[{"delta":{"content":"never read"}}]}"""
            )
        ) { shown += it }
        assertEquals("{\"headline\": \"You are tired\",", text)
        // The screen is told everything written so far, each time more arrives.
        assertEquals(listOf("{\"headline\": \"You", "{\"headline\": \"You are tired\","), shown)
    }

    @Test
    fun aServiceThatSendsTheLastPieceAsAWholeMessageStillWorks() {
        assertEquals("all of it", Sse.delta("""data: {"choices":[{"message":{"role":"assistant","content":"all of it"}}]}"""))
        assertNull(Sse.delta("data: not json"))
        assertNull(Sse.delta("event: ping"))
        assertTrue(Sse.isDone("data: [DONE]"))
        assertFalse(Sse.isDone("data: {}"))
    }

    @Test
    fun anErrorInTheMiddleOfAStreamIsReportedNotSwallowed() {
        val failure = try {
            Sse.read(sse("""data: {"choices":[{"delta":{"content":"ab"}}]}""", """data: {"error":{"message":"rate limited"}}""")) { }
            null
        } catch (e: Sse.StreamFailure) {
            e.message
        }
        assertEquals("rate limited", failure)
    }

    @Test
    fun aPartlyWrittenReportShowsWhatHasArrivedSoFar() {
        // Cut in the middle of a sentence: the words so far are shown.
        assertEquals("You are tir", ReportParser.parsePartial("""{"headline":"You are tir""")!!.headline)
        // Cut inside a list: finished items and the growing one.
        val r = ReportParser.parsePartial("""```json
            {"headline":"A","why":"B","right_now":["Stand up","Leave the ro""")!!
        assertEquals("A", r.headline)
        assertEquals(listOf("Stand up", "Leave the ro"), r.rightNow)
        // Cut inside a key, or right after a colon or comma: the dangling part is dropped, not an error.
        assertEquals("A", ReportParser.parsePartial("""{"headline":"A","wh""")!!.headline)
        assertEquals("A", ReportParser.parsePartial("""{"headline":"A","why":""")!!.headline)
        assertEquals("A", ReportParser.parsePartial("""{"headline":"A",""")!!.headline)
        // Inside a list of objects.
        val u = ReportParser.parsePartial("""{"headline":"A","understand":[{"title":"Urge surfing","body":"They pass""")!!
        assertEquals(listOf("Urge surfing" to "They pass"), u.understand)
        // A half-written escape at the very end.
        assertEquals("say", ReportParser.parsePartial("{\"headline\":\"say \\")!!.headline)
        assertEquals("caf", ReportParser.parsePartial("{\"headline\":\"caf\\u00")!!.headline)
        // Nothing to show yet.
        assertNull(ReportParser.parsePartial(""))
        assertNull(ReportParser.parsePartial("Sure, here is"))
        assertNull(ReportParser.parsePartial("{"))
    }

    @Test
    fun aFinishedReplyReadsTheSameWhetherOrNotItWasStreamed() {
        val full = """{"headline":"H","why":"W","right_now":["a","b"],"today":["t"],"this_week":["If 23:00, then charge outside."],
            "long_term":["l"],"understand":[{"title":"T","body":"B"}],"pattern":"P","encouragement":"E"}"""
        assertEquals(ReportParser.parse(full), ReportParser.parsePartial(full))
        // And every prefix of it can be read without an error.
        for (n in 1..full.length) {
            ReportParser.parsePartial(full.substring(0, n))
        }
    }

    @Test
    fun aStreamingCallWithABadAddressFailsWithoutTouchingTheNetwork() {
        assertEquals(AiResult.Failed(AiError.BAD_URL), AiClient.chatStream(Provider.CUSTOM, "not a url", "k", "m", "s", "u") { })
    }

    @Test
    fun aListSaysTodayAndYesterdayInsteadOfADate() {
        val today = java.time.LocalDate.of(2026, 3, 1)
        assertEquals(0, Times.dayKind(today, today))
        assertEquals(1, Times.dayKind(today.minusDays(1), today))
        assertEquals(2, Times.dayKind(today.minusDays(2), today))
        assertEquals(2, Times.dayKind(today.plusDays(1), today))
    }

    @Test
    fun aDayWithOnlyAnUnansweredEntryIsNotDrawnAsEmpty() {
        val now = ms(20, 12)
        val open = Entry(ms(20, 9), false, mapOf(Q.FEELING to Opt.BORED), null)
        val bar = Insights.days(listOf(open), now, java.time.ZoneId.systemDefault(), 3).last()
        assertEquals(0, bar.resisted)
        assertEquals(0, bar.gaveIn)
        assertEquals(1, bar.open)
    }

    @Test
    fun aReplyCutOffMidSentenceIsStillReadNotShownAsRawJson() {
        val cut = """{"headline":"h","reality_check":"r","realization":"z","right_now":["a","b"],"your_line":"If x, then y.",
            "trap":"t","reply":"p","question":"q?","why":"","today":["one"],"understand":[{"title":"T","body":"B"}],
            "pattern":"Your danger window has been 01:00 to 02:00, and loneliness has"""
        val r = ReportParser.parse(cut)!!
        assertEquals("h", r.headline)
        assertEquals("If x, then y.", r.yourLine)
        assertEquals(listOf("a", "b"), r.rightNow)
        assertEquals(listOf("one"), r.today)
        assertTrue(r.pattern.startsWith("Your danger window"))
    }

    @Test
    fun aReplyCutOffNearTheEndIsKeptButAFragmentIsNot() {
        val nearlyDone = """{"headline":"This is the 3pm dodge.","reality_check":"Four of nine urges came at your desk.","right_now":["Put the phone in the drawer.","Write one sen"""
        assertTrue(AiClient.usablePartial(nearlyDone))
        // Only the start of a headline: nothing worth showing yet.
        assertFalse(AiClient.usablePartial("""{"headline":"This is the 3pm"""))
        assertFalse(AiClient.usablePartial(""))
        assertFalse(AiClient.usablePartial("Sorry, something went"))
    }

    @Test
    fun aCrisisNoteGetsTheCoachToStayNotToCoach() {
        val sys = Prompt.systemFor(true)
        // The crisis rule names the exact fields, so the reply lands where the screen shows it.
        assertTrue("Stay with them" in sys)
        assertTrue("never leave them with nothing to do" in sys)
        val e = Entry(ms(20, 2), false, mapOf(Q.FEELING to Opt.SAD), null, note = "I don't want to live like this anymore")
        assertTrue("flagged these words as a possible crisis" in Prompt.user(e, ""))
        val calm = Entry(ms(20, 2), false, mapOf(Q.FEELING to Opt.BORED), null, note = "bored after dinner")
        assertFalse("possible crisis" in Prompt.user(calm, ""))
    }

    @Test
    fun theWeeklyReviewComparesWithTheWeekBeforeAndFollowsUp() {
        val now = ms(20, 15)
        val lastWeek = (8..11).map { Entry(ms(it, 22), false, mapOf(Q.FEELING to Opt.TIRED), Outcome.GAVE_IN) }
        val thisWeek = (15..16).map { Entry(ms(it, 22), false, mapOf(Q.FEELING to Opt.TIRED), Outcome.RESISTED) }
        val previous = Prompt.PreviousReview(
            7, """{"headline":"h","your_line":"If it is 22:00, then the phone charges in the hall.","right_now":["Move the charger tonight."],"this_week":["Log every urge after 21:00."]}"""
        )
        val weekly = Prompt.weeklyUser(lastWeek + thisWeek, now, "", zone, previous)
        assertTrue("The 7 days before that: 4 entries, 0 ridden out, 4 given in to or slipped." in weekly)
        assertTrue("Your previous review, 7 days ago, asked them to try:" in weekly)
        assertTrue("- If it is 22:00, then the phone charges in the hall." in weekly)
        assertTrue("- Log every urge after 21:00." in weekly)
        // A brand-new journal has no week before to compare with, and says nothing about one.
        assertFalse("days before that" in Prompt.weeklyUser(thisWeek, now, "", zone))
        assertTrue("remembers" in WEEKLY_SYSTEM)
    }

    @Test
    fun theDeadWhyFieldIsNoLongerAskedFor() {
        for (sys in listOf(Prompt.systemFor(true), WEEKLY_SYSTEM)) assertFalse("\"why\"" in sys)
    }

    @Test
    fun aLateEntryGetsTheLateNightCountSoTheCoachNeedNotGuess() {
        val history = listOf(
            Entry(ms(12, 23), false, mapOf(Q.FEELING to Opt.STRESSED), Outcome.GAVE_IN),
            Entry(ms(14, 23), true, mapOf(Q.FEELING to Opt.TIRED), null),
            Entry(ms(15, 15), false, mapOf(Q.FEELING to Opt.BORED), Outcome.GAVE_IN),
            Entry(ms(16, 22), false, mapOf(Q.FEELING to Opt.STRESSED), Outcome.RESISTED)
        )
        val late = Entry(ms(20, 23), false, mapOf(Q.FEELING to Opt.STRESSED), null)
        val lines = Prompt.extras(history + late, late, emptyList(), zone).joinToString("\n")
        assertTrue("Late at night (22:00 to 05:00) in the last 30 days: 3 other entries, 2 given in to or slipped." in lines)
        val day = Entry(ms(20, 14), false, mapOf(Q.FEELING to Opt.STRESSED), null)
        assertFalse("Late at night" in Prompt.extras(history + day, day, emptyList(), zone).joinToString(" "))
        val sys = Prompt.systemFor(true)
        assertTrue("do not add up, split, re-count or estimate" in sys)
        assertTrue("never a nightly focus block" in sys)
    }

    @Test
    fun thePlaceIsAFactNotAGuessAndSectionsMustNotRepeat() {
        val history = listOf(
            Entry(ms(12, 23), false, mapOf(Q.PLACE to Opt.BED), Outcome.GAVE_IN),
            Entry(ms(13, 23), false, mapOf(Q.PLACE to Opt.BED), Outcome.RESISTED),
            Entry(ms(14, 15), false, mapOf(Q.PLACE to Opt.DESK), Outcome.RESISTED)
        )
        val now = Entry(ms(20, 23), false, mapOf(Q.PLACE to Opt.BED), null)
        assertTrue("Logged at the same place (bed) 2 times before: 1 ridden out, 1 given in to." in Prompt.extras(history + now, now, emptyList(), zone).joinToString(" "))
        val sys = Prompt.systemFor(true)
        assertTrue("Every section adds something new" in sys)
        // The banned verdict is described, not quoted, so it isn't planted.
        assertFalse("not luck" in sys)
    }

    @Test
    fun theCoachSpeaksLikeAPersonAndEndsWithASpark() {
        for (sys in listOf(Prompt.systemFor(true), WEEKLY_SYSTEM)) {
            assertTrue("Voice" in sys)
            assertTrue("the spark" in sys)
            assertTrue("No slogans" in sys)
        }
        // Honesty stays: warm is not the same as flattering.
        assertTrue("Never flatter" in Prompt.systemFor(true))
        assertFalse("each with a short reason" in Prompt.systemFor(true))
    }
}

