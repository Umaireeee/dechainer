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
import io.github.warleysr.dechainer.report.DataTools
import io.github.warleysr.dechainer.report.PeriodKind
import io.github.warleysr.dechainer.report.PeriodReportService
import io.github.warleysr.dechainer.report.ReportOutcome
import io.github.warleysr.dechainer.store.DechainerDatabase
import io.github.warleysr.dechainer.store.Store
import io.github.warleysr.dechainer.store.StoredPeriodReport
import io.github.warleysr.dechainer.urge.Answer
import io.github.warleysr.dechainer.urge.UrgeKind
import io.github.warleysr.dechainer.urge.UrgeSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** The monthly and yearly reports on the real store: built once, after the period, never with a note in the request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PeriodReportServiceTest {
    private lateinit var ctx: Context
    private val zone = ZoneId.of("Europe/London")
    private val secret = "SECRET-NOTE-the-ugly-details"
    private fun ms(y: Int, m: Int, d: Int, h: Int = 12) = LocalDate.of(y, m, d).atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    private var gate = AiGateResult.OPEN
    private var reply: AiResult = AiResult.Ok("## The month at a glance\n2 urges.\n## Next month\n1. Phone in the hall.")
    private val requests = mutableListOf<Pair<String, String>>()
    private val announced = mutableListOf<StoredPeriodReport>()

    private fun service() = PeriodReportService(
        ctx,
        calls = AiCalls(chat = { _, system, user, _ -> requests += system to user; reply }),
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

    private fun urge(at: Long, kind: UrgeKind = UrgeKind.URGE) {
        val repo = Store.urgeEntries(ctx)
        val id = repo.insert(kind, UrgeSource.HOME, at)
        repo.markWriting(id); repo.saveNote(id, secret)
        repo.saveAnswers(id, listOf(Answer("q1", "Where were you?", "In bed")))
    }

    @Test fun aMonthIsBuiltOnceAfterItEndsAndAnnounced() {
        urge(ms(2026, 10, 7)); urge(ms(2026, 10, 20, 23), UrgeKind.SLIP)
        val s = service()
        assertEquals(ReportOutcome.NOT_DUE, s.generate(PeriodKind.MONTH, "2026-10", ms(2026, 10, 31, 23), zone))
        assertEquals(listOf("2026-10"), s.dueWithData(PeriodKind.MONTH, ms(2026, 11, 2), zone))
        assertEquals(ReportOutcome.DONE, s.generate(PeriodKind.MONTH, "2026-10", ms(2026, 11, 2), zone))
        assertEquals(1, requests.size)
        assertTrue(requests.single().first.contains("## The month at a glance"))
        assertEquals(1, announced.size)
        val row = Store.periodReports(ctx).all(PeriodKind.MONTH).single()
        assertEquals("2026-10", row.key)
        assertTrue(row.summaryJson.contains("Phone in the hall"))
        // Built once: a second run does nothing.
        assertEquals(ReportOutcome.DONE, s.generate(PeriodKind.MONTH, "2026-10", ms(2026, 11, 3), zone))
        assertEquals(1, requests.size)
        assertEquals(emptyList<String>(), s.dueWithData(PeriodKind.MONTH, ms(2026, 11, 3), zone))
    }

    @Test fun noRawUrgeTextIsEverInTheRequest() {
        urge(ms(2026, 10, 7))
        service().generate(PeriodKind.MONTH, "2026-10", ms(2026, 11, 2), zone)
        assertFalse(requests.single().second.contains("SECRET"))
        assertTrue(requests.single().second.contains("October 2026"))
    }

    @Test fun aYearUsesTheYearlyPromptAndTheMonthlyReportsOfThatYear() {
        urge(ms(2026, 10, 7))
        val s = service()
        s.generate(PeriodKind.MONTH, "2026-10", ms(2026, 11, 2), zone)
        reply = AiResult.Ok("## The year in numbers\n1 urge.\n## Carry into next year\nSleep by 23:00.")
        assertEquals(ReportOutcome.DONE, s.generate(PeriodKind.YEAR, "2026", ms(2027, 1, 2), zone))
        val (system, user) = requests.last()
        assertTrue(system.contains("## Carry into next year"))
        assertTrue(user.contains("THE MONTHLY REPORTS OF THIS YEAR"))
        assertTrue(user.contains("- 2026-10: urges 1"))
        assertTrue(Store.periodReports(ctx).all(PeriodKind.YEAR).single().summaryJson.contains("Sleep by 23:00."))
    }

    @Test fun anEmptyMonthHasNoReportAndFailuresAreSortedLikeTheWeeklyOnes() {
        urge(ms(2026, 10, 7))
        val s = service()
        assertEquals(ReportOutcome.NO_DATA, s.generate(PeriodKind.MONTH, "2026-11", ms(2026, 12, 2), zone))
        gate = AiGateResult.NO_KEY
        assertEquals(ReportOutcome.NEEDS_OWNER, s.generate(PeriodKind.MONTH, "2026-10", ms(2026, 11, 2), zone))
        gate = AiGateResult.OPEN
        reply = AiResult.Failed(AiError.NETWORK)
        assertEquals(ReportOutcome.RETRY, s.generate(PeriodKind.MONTH, "2026-10", ms(2026, 11, 2), zone))
        assertTrue(Store.periodReports(ctx).all(PeriodKind.MONTH).isEmpty())
    }

    @Test fun aDeletedReportIsNotBuiltAgainAndTheBackupCarriesItBack() {
        urge(ms(2026, 10, 7))
        service().generate(PeriodKind.MONTH, "2026-10", ms(2026, 11, 2), zone)
        val tools = DataTools(ctx, zone) { ms(2026, 11, 2) }
        val backup = tools.export()
        assertTrue(backup.contains("period_report"))
        val row = Store.periodReports(ctx).all(PeriodKind.MONTH).single()
        tools.deletePeriodReport(row.id)
        assertEquals(ReportOutcome.DONE, service().generate(PeriodKind.MONTH, "2026-10", ms(2026, 11, 3), zone))
        assertEquals("not built again", 1, requests.size)
        assertTrue(Store.periodReports(ctx).all(PeriodKind.MONTH).single().deleted)
    }
}
