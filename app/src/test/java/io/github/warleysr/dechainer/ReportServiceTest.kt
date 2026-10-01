package io.github.warleysr.dechainer

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.ai.AiCalls
import io.github.warleysr.dechainer.ai.AiConfig
import io.github.warleysr.dechainer.ai.AiError
import io.github.warleysr.dechainer.ai.AiGateResult
import io.github.warleysr.dechainer.ai.AiResult
import io.github.warleysr.dechainer.ai.Provider
import io.github.warleysr.dechainer.day.NewGoal
import io.github.warleysr.dechainer.report.DataTools
import io.github.warleysr.dechainer.report.ReportOutcome
import io.github.warleysr.dechainer.report.ReportService
import io.github.warleysr.dechainer.store.AppStateKeys
import io.github.warleysr.dechainer.store.DechainerDatabase
import io.github.warleysr.dechainer.store.StoredReport
import io.github.warleysr.dechainer.store.Store
import io.github.warleysr.dechainer.urge.Answer
import io.github.warleysr.dechainer.urge.UrgeKind
import io.github.warleysr.dechainer.urge.UrgeSource
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Phase 6 acceptance checks on the real store: a report is built after the phone was off at the
 * due time, and no raw urge text is ever in the request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReportServiceTest {
    private lateinit var ctx: Context
    private val zone = ZoneId.of("Europe/London")
    private val secret = "SECRET-NOTE-the-ugly-details"
    private fun ms(m: Int, d: Int, h: Int = 12) = LocalDate.of(2026, m, d).atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    private var gate = AiGateResult.OPEN
    private var reply: AiResult = AiResult.Ok("## Week at a glance\n2 urges.\n## Next week\n1. Phone out of the bedroom.\n2. Walk.\n3. Plan.")
    private val requests = mutableListOf<String>()
    private val announced = mutableListOf<StoredReport>()

    private fun service() = ReportService(
        ctx,
        calls = AiCalls(chat = { _, _, user, _ -> requests += user; reply }),
        gate = { gate },
        config = { AiConfig(Provider.OPENAI, "https://api.example.com/v1", "key", "model") },
        announce = { announced += it }
    )

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Store.resetForTests()
        ctx.deleteDatabase(DechainerDatabase.FILE_NAME)
    }

    @After fun tearDown() {
        Store.resetForTests()
        ctx.deleteDatabase(DechainerDatabase.FILE_NAME)
    }

    /** An urge with a stored note, answered, still waiting for its deep dive. */
    private fun urge(at: Long, kind: UrgeKind = UrgeKind.URGE) {
        val repo = Store.urgeEntries(ctx)
        val id = repo.insert(kind, UrgeSource.HOME, at)
        repo.markWriting(id); repo.saveNote(id, secret)
        repo.saveAnswers(id, listOf(Answer("q1", "Where were you?", "In bed")))
    }

    @Test fun aReportDueWhileThePhoneWasOffIsBuiltOnTheNextWakeUp() {
        urge(ms(10, 7)); urge(ms(10, 8, 23), UrgeKind.SLIP)
        val s = service()
        assertEquals(listOf(0), s.dueWithData(ms(10, 30), zone)) // the phone was off on the 12th; weeks 1 and 2 are empty
        assertEquals(ReportOutcome.DONE, s.generate(0, ms(10, 30), zone))
        assertEquals(1, requests.size)
        assertEquals(1, announced.size)
        val row = Store.reports(ctx).all().single()
        assertEquals(0, row.periodIndex)
        assertTrue(row.bodyMd.contains("Week at a glance"))
        assertTrue(row.summaryJson.contains("Phone out of the bedroom"))
        assertEquals(emptyList<Int>(), s.dueWithData(ms(10, 30), zone))
    }

    @Test fun noRawUrgeTextIsEverInTheRequest() {
        urge(ms(10, 7))
        assertTrue(Store.urgeEntries(ctx).all().single().rawText == secret) // the note is on the phone...
        service().generate(0, ms(10, 15), zone)
        assertEquals(1, requests.size)
        assertFalse(requests.single().contains("SECRET")) // ...and not in what was sent
        assertTrue(requests.single().contains("In bed"))
    }

    @Test fun aPeriodThatHasNotEndedIsNotBuilt() {
        urge(ms(10, 7))
        assertEquals(ReportOutcome.NOT_DUE, service().generate(0, ms(10, 11, 23), zone))
        assertTrue(requests.isEmpty())
        assertTrue(Store.reports(ctx).all().isEmpty())
    }

    @Test fun aPeriodWithNoDataPointHasNoReport() {
        urge(ms(10, 7))
        assertEquals(ReportOutcome.NO_DATA, service().generate(1, ms(10, 30), zone))
        assertTrue(requests.isEmpty())
    }

    @Test fun theAnchorIsTheFirstDataPointsDayAndNeverMoves() {
        assertNull(service().anchor(zone)) // nothing recorded yet
        urge(ms(10, 7, 23))
        assertEquals(LocalDate.of(2026, 10, 7), service().anchor(zone))
        urge(ms(10, 3)) // an older entry arriving later does not move week 0
        assertEquals(LocalDate.of(2026, 10, 7), service().anchor(zone))
        assertEquals("2026-10-07", Store.appState(ctx).get(AppStateKeys.WEEK_ANCHOR))
    }

    @Test fun aFirstGoalIsADataPoint() {
        Store.days(ctx).savePlan(LocalDate.of(2026, 10, 9), List(3) { NewGoal("g$it") }, ms(10, 8, 21))
        assertEquals(LocalDate.of(2026, 10, 8), service().anchor(zone))
    }

    @Test fun failuresAreSortedIntoRetryAndOwner() {
        urge(ms(10, 7))
        val s = service()
        gate = AiGateResult.OFFLINE
        assertEquals(ReportOutcome.RETRY, s.generate(0, ms(10, 15), zone))
        gate = AiGateResult.NO_KEY
        assertEquals(ReportOutcome.NEEDS_OWNER, s.generate(0, ms(10, 15), zone))
        gate = AiGateResult.OPEN
        reply = AiResult.Failed(AiError.BAD_KEY)
        assertEquals(ReportOutcome.NEEDS_OWNER, s.generate(0, ms(10, 15), zone))
        reply = AiResult.Failed(AiError.NETWORK)
        assertEquals(ReportOutcome.RETRY, s.generate(0, ms(10, 15), zone))
        reply = AiResult.Ok("")
        assertEquals(ReportOutcome.RETRY, s.generate(0, ms(10, 15), zone))
        assertTrue(Store.reports(ctx).all().isEmpty())
        assertTrue(announced.isEmpty())
        reply = AiResult.Ok("## Week at a glance\nFine.")
        assertEquals(ReportOutcome.DONE, s.generate(0, ms(10, 15), zone))
        assertEquals(1, announced.size)
    }

    @Test fun aSecondRunDoesNotCallTheAiAgainOrAnnounceAgain() {
        urge(ms(10, 7))
        val s = service()
        s.generate(0, ms(10, 15), zone)
        assertEquals(ReportOutcome.DONE, s.generate(0, ms(10, 15), zone))
        assertEquals(1, requests.size)
        assertEquals(1, announced.size)
    }

    @Test fun aDeletedReportIsNotBuiltAgain() {
        urge(ms(10, 7))
        val s = service()
        s.generate(0, ms(10, 15), zone)
        val tools = DataTools(ctx, zone) { ms(10, 14) }
        assertEquals(io.github.warleysr.dechainer.report.DeleteResult.DELETED, tools.deleteReport(announced.single().id))
        assertEquals(emptyList<Int>(), s.dueWithData(ms(10, 14), zone))
        assertEquals(ReportOutcome.DONE, s.generate(0, ms(10, 14), zone))
        assertEquals(1, requests.size)
        assertEquals("", Store.reports(ctx).all().single().bodyMd)
    }

    @Test fun theNextReportSeesWhatTheLastOneAdvised() {
        urge(ms(10, 7)); urge(ms(10, 14))
        val s = service()
        s.generate(0, ms(10, 30), zone)
        s.generate(1, ms(10, 30), zone)
        assertEquals(2, requests.size)
        assertTrue(requests[1].contains("advice given then: 1. Phone out of the bedroom."))
        assertFalse(requests[0].contains("advice given then"))
    }
}
