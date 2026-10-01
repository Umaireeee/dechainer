package io.github.warleysr.dechainer

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.day.DayKind
import io.github.warleysr.dechainer.day.NewGoal
import io.github.warleysr.dechainer.day.Violation
import io.github.warleysr.dechainer.day.DayVerdict
import io.github.warleysr.dechainer.focus.CheckinAnswer
import io.github.warleysr.dechainer.focus.Flavor
import io.github.warleysr.dechainer.focus.FocusSource
import io.github.warleysr.dechainer.focus.ResetResult
import io.github.warleysr.dechainer.focus.SessionOutcome
import io.github.warleysr.dechainer.lock.LockStateStore
import io.github.warleysr.dechainer.lock.PunishmentInput
import io.github.warleysr.dechainer.report.DataTools
import io.github.warleysr.dechainer.report.DeleteResult
import io.github.warleysr.dechainer.store.AppStateKeys
import io.github.warleysr.dechainer.store.DechainerDatabase
import io.github.warleysr.dechainer.store.StoredSession
import io.github.warleysr.dechainer.store.Store
import io.github.warleysr.dechainer.urge.Answer
import io.github.warleysr.dechainer.urge.UrgeKind
import io.github.warleysr.dechainer.urge.UrgeSource
import io.github.warleysr.dechainer.urge.UrgeStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DataToolsTest {
    private lateinit var ctx: Context
    private val zone = ZoneId.of("Europe/London")
    private val hour = 3_600_000L
    private fun ms(m: Int, d: Int, h: Int = 12) = LocalDate.of(2026, m, d).atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
    private var now = ms(10, 20)
    private fun tools() = DataTools(ctx, zone) { now }

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Store.resetForTests(); TrustedClock.resetForTests(); LockStateStore.resetForTests()
        ctx.deleteDatabase(DechainerDatabase.FILE_NAME)
    }

    @After fun tearDown() {
        Store.resetForTests(); TrustedClock.resetForTests(); LockStateStore.resetForTests()
        ctx.deleteDatabase(DechainerDatabase.FILE_NAME)
    }

    private fun entry(at: Long, kind: UrgeKind = UrgeKind.URGE, finish: Boolean = true): Long {
        val repo = Store.urgeEntries(ctx)
        val id = repo.insert(kind, UrgeSource.HOME, at)
        if (finish) { repo.markWriting(id); repo.skip(id) }
        return id
    }

    private fun session(at: Long): Long {
        val repo = Store.focus(ctx)
        val id = repo.insert(StoredSession(at, FocusSource.MANUAL, Flavor.USUAL, "Chapter", at, at + hour, null, 0, null))
        repo.addCheckin(id, at + hour, CheckinAnswer.YES, ResetResult.NONE)
        repo.end(id, at + hour, SessionOutcome.COMPLETED, 25)
        return id
    }

    private fun freeze() {
        val t = TrustedClock.now(ctx)
        LockStateStore.setPunishment(ctx, PunishmentInput(t - hour, t + hour), "today")
    }

    // ---- delete ----

    @Test fun anOrdinaryUrgeAndItsDeepDiveCanBeDeleted() {
        val id = entry(ms(10, 20, 9))
        assertEquals(DeleteResult.DELETED, tools().deleteUrge(id))
        assertNull(Store.urgeEntries(ctx).get(id))
        assertEquals(DeleteResult.MISSING, tools().deleteUrge(id))

        val repo = Store.urgeEntries(ctx)
        val d = repo.insert(UrgeKind.URGE, UrgeSource.HOME, ms(10, 19))
        repo.markWriting(d); repo.saveNote(d, "n"); repo.saveAnswers(d, listOf(Answer("q", "p", "a"))); repo.saveDeepDive(d, "## Deep")
        assertEquals(DeleteResult.DELETED, tools().deleteDeepDive(d))
        assertNull(repo.get(d)!!.deepDive)
        assertEquals(UrgeStatus.DONE, repo.get(d)!!.status)
    }

    @Test fun anEntryStillInTheFlowCannotBeDeleted() {
        val id = entry(ms(10, 20, 9), finish = false)
        assertEquals(DeleteResult.NOT_ALLOWED, tools().deleteUrge(id))
    }

    @Test fun aSlipOfTodayCannotBeDeletedSoTonightsNoSlipGoalStaysHonest() {
        val today = entry(ms(10, 20, 9), UrgeKind.SLIP)
        assertEquals(DeleteResult.NOT_ALLOWED, tools().deleteUrge(today))
        val earlier = entry(ms(10, 18, 9), UrgeKind.SLIP)
        assertEquals(DeleteResult.DELETED, tools().deleteUrge(earlier))
        assertNotNull(Store.urgeEntries(ctx).get(today))
    }

    @Test fun aSessionCanGoOnceItsDayHasPassed() {
        val today = session(ms(10, 20, 9))
        assertEquals(DeleteResult.NOT_ALLOWED, tools().deleteSession(today))
        val old = session(ms(10, 12, 9))
        assertEquals(DeleteResult.DELETED, tools().deleteSession(old))
        assertTrue(Store.focus(ctx).checkins(old).isEmpty())
    }

    @Test fun goalTextInTheOpenWeekStaysUntilAReportCoversIt() {
        val days = Store.days(ctx)
        days.savePlan(LocalDate.of(2026, 10, 7), List(3) { NewGoal("g$it") }, ms(10, 6, 21))
        assertEquals(DeleteResult.NOT_ALLOWED, tools().deleteGoals(LocalDate.of(2026, 10, 7)))
        Store.reports(ctx).insert(0, ms(10, 5, 0), ms(10, 12, 0), ms(10, 12, 1), "## r", "{}")
        assertEquals(DeleteResult.DELETED, tools().deleteGoals(LocalDate.of(2026, 10, 7)))
        assertTrue(days.goals(LocalDate.of(2026, 10, 7)).isEmpty())
        assertNotNull(days.day(LocalDate.of(2026, 10, 7))) // the day row, with its counts, stays
    }

    @Test fun deletingAReportNeverMakesMoreGoalsDeletable() {
        val days = Store.days(ctx)
        days.savePlan(LocalDate.of(2026, 10, 7), List(3) { NewGoal("g$it") }, ms(10, 6, 21))
        Store.reports(ctx).insert(0, ms(10, 5, 0), ms(10, 12, 0), ms(10, 12, 1), "## r", "{}")
        Store.reports(ctx).delete(Store.reports(ctx).all().single().id)
        assertTrue(tools().canDeleteGoals(LocalDate.of(2026, 10, 7)))
        assertFalse(tools().canDeleteGoals(LocalDate.of(2026, 10, 14)))
    }

    @Test fun aPunishmentDayMakesEveryWriteRefuse() {
        val id = entry(ms(10, 10))
        val sid = session(ms(10, 10))
        freeze()
        assertEquals(DeleteResult.FROZEN, tools().deleteUrge(id))
        assertEquals(DeleteResult.FROZEN, tools().deleteDeepDive(id))
        assertEquals(DeleteResult.FROZEN, tools().deleteSession(sid))
        assertEquals(DeleteResult.FROZEN, tools().deleteReport(1))
        assertEquals(DeleteResult.FROZEN, tools().deleteGoals(LocalDate.of(2026, 10, 1)))
        assertTrue(tools().wipeAll().frozen)
        assertFalse(tools().import("{}").ok)
        assertNotNull(Store.urgeEntries(ctx).get(id))
        assertTrue(tools().export().isNotBlank()) // reading is still open
    }

    // ---- wipe ----

    @Test fun wipeRemovesWhatTheRulesAllowAndResetsNothingThatPunishes() {
        val state = Store.appState(ctx)
        state.set(AppStateKeys.ACTIVATED_ON, "2026-10-01"); state.set(AppStateKeys.WEEK_ANCHOR, "2026-10-05")
        val days = Store.days(ctx)
        days.savePlan(LocalDate.of(2026, 10, 8), List(3) { NewGoal("old") }, ms(10, 7, 21))
        days.saveEvaluation(LocalDate.of(2026, 10, 8), DayVerdict(DayKind.PUNISHMENT, Violation.UNDER_HALF))
        days.setRest(LocalDate.of(2026, 10, 9), true)
        days.savePlan(LocalDate.of(2026, 10, 21), List(3) { NewGoal("open") }, ms(10, 20, 21))
        Store.reports(ctx).insert(0, ms(10, 5, 0), ms(10, 12, 0), ms(10, 12, 1), "## r", "{}")
        val old = entry(ms(10, 8, 9)); val slipToday = entry(ms(10, 20, 9), UrgeKind.SLIP); val urgeToday = entry(ms(10, 20, 9))
        val oldSession = session(ms(10, 9, 9)); val sessionToday = session(ms(10, 20, 9))

        val r = tools().wipeAll()
        assertFalse(r.frozen)
        assertNull(Store.urgeEntries(ctx).get(old)); assertNull(Store.urgeEntries(ctx).get(urgeToday))
        assertNotNull(Store.urgeEntries(ctx).get(slipToday)) // would otherwise turn tonight's NO_SLIP into a pass
        assertNull(Store.focus(ctx).session(oldSession)); assertNotNull(Store.focus(ctx).session(sessionToday))
        assertTrue(days.goals(LocalDate.of(2026, 10, 8)).isEmpty())
        assertEquals(3, days.goals(LocalDate.of(2026, 10, 21)).size) // the open week's goals stay
        assertEquals(DayKind.PUNISHMENT, days.day(LocalDate.of(2026, 10, 8))!!.kind)
        assertEquals(listOf(LocalDate.of(2026, 10, 9)), days.restDates())
        assertEquals("2026-10-01", state.get(AppStateKeys.ACTIVATED_ON)); assertEquals("2026-10-05", state.get(AppStateKeys.WEEK_ANCHOR))
        assertEquals("", Store.reports(ctx).all().single().bodyMd)
        assertEquals(setOf(0), Store.reports(ctx).reportedPeriods()) // the week is not built again
        assertTrue(r.kept >= 3)
    }

    // ---- export and import ----

    private fun fill() {
        Store.appState(ctx).set(AppStateKeys.WEEK_ANCHOR, "2026-10-05"); Store.appState(ctx).set(AppStateKeys.ACTIVATED_ON, "2026-10-20")
        val repo = Store.urgeEntries(ctx)
        val id = repo.insert(UrgeKind.SLIP, UrgeSource.TILE, ms(10, 6)); repo.markWriting(id); repo.saveNote(id, "n")
        repo.saveAnswers(id, listOf(Answer("q", "p", "a"))); repo.saveDeepDive(id, "## Deep dive")
        entry(ms(10, 7)) // a counted stub
        session(ms(10, 8, 9))
        val days = Store.days(ctx)
        days.savePlan(LocalDate.of(2026, 10, 9), List(3) { NewGoal("goal $it") }, ms(10, 8, 21))
        days.saveEvaluation(LocalDate.of(2026, 10, 9), DayVerdict(DayKind.NORMAL, null))
        Store.reports(ctx).insert(0, ms(10, 5, 0), ms(10, 12, 0), ms(10, 12, 1), "## Report body", "{\"urges\":1}")
    }

    private fun counts() = listOf(
        Store.urgeEntries(ctx).count(), Store.focus(ctx).count(), Store.focus(ctx).allCheckins().size,
        Store.days(ctx).goals(LocalDate.of(2026, 10, 9)).size, Store.reports(ctx).all().size
    )

    @Test fun anExportReadsBackIntoAnEmptyStore() {
        fill()
        val before = counts()
        val file = tools().export()
        assertFalse(file.contains("activatedOn")) // app_state is never exported

        Store.resetForTests(); ctx.deleteDatabase(DechainerDatabase.FILE_NAME)
        Store.appState(ctx).set(AppStateKeys.ACTIVATED_ON, "2026-10-20")
        val r = tools().import(file)
        assertTrue(r.ok)
        assertEquals(before, counts())
        assertEquals("2026-10-05", Store.appState(ctx).get(AppStateKeys.WEEK_ANCHOR))
        assertEquals("## Deep dive", Store.urgeEntries(ctx).all().first().deepDive)
        assertEquals("## Report body", Store.reports(ctx).all().single().bodyMd)
    }

    @Test fun readingTheSameFileTwiceAddsNothing() {
        fill()
        val file = tools().export()
        val before = counts()
        val r = tools().import(file)
        assertTrue(r.ok)
        assertEquals(0, r.added)
        assertEquals(before, counts())
    }

    @Test fun anImportCannotRewriteADaysVerdictOrPlantADayInTheEnforcedPeriod() {
        Store.appState(ctx).set(AppStateKeys.ACTIVATED_ON, "2026-10-12")
        val days = Store.days(ctx)
        days.savePlan(LocalDate.of(2026, 10, 14), List(3) { NewGoal("g") }, ms(10, 13, 21))
        days.saveEvaluation(LocalDate.of(2026, 10, 14), DayVerdict(DayKind.PUNISHMENT, Violation.PLAN_MISSING))
        val file = """{"format":"dechainer-export","version":1,"day":[
            {"date":"2026-10-14","kind":"NORMAL","done_count":3,"total_count":3,"evaluated":1},
            {"date":"2026-10-13","kind":"NORMAL","done_count":3,"total_count":3,"evaluated":1},
            {"date":"2026-10-10","kind":"NORMAL","done_count":3,"total_count":3,"evaluated":1}]}"""
        val r = tools().import(file)
        assertTrue(r.ok)
        assertEquals(DayKind.PUNISHMENT, days.day(LocalDate.of(2026, 10, 14))!!.kind)
        assertNull(days.day(LocalDate.of(2026, 10, 13))) // inside the enforced period: not taken
        assertNotNull(days.day(LocalDate.of(2026, 10, 10))) // before activation: history only
        assertEquals(2, r.skipped)
        assertEquals(1, r.added)
    }

    @Test fun reportsFromOtherWeeksAreNotTaken() {
        Store.appState(ctx).set(AppStateKeys.WEEK_ANCHOR, "2026-10-06")
        val file = """{"format":"dechainer-export","version":1,"weekAnchor":"2026-10-05","weekly_report":[
            {"period_index":0,"period_start":1,"period_end":2,"created_at":3,"status":"DONE","body_md":"## x","summary_json":"{}"}]}"""
        val r = tools().import(file)
        assertTrue(r.ok)
        assertTrue(Store.reports(ctx).all().isEmpty())
        assertEquals(1, r.skipped)
    }

    @Test fun aFileThatIsNotABackupChangesNothing() {
        fill()
        val before = counts()
        assertFalse(tools().import("not json").ok)
        assertFalse(tools().import("""{"format":"something-else","version":1}""").ok)
        assertFalse(tools().import("""{"format":"dechainer-export","version":99}""").ok)
        assertEquals(before, counts())
    }

    @Test fun anEntryThatWasMidFlowComesBackAsACountedStubOrAWaitingNote() {
        val file = """{"format":"dechainer-export","version":1,"urge_entry":[
            {"created_at":1000,"kind":"URGE","source":"HOME","status":"WRITING","raw_text":"half"},
            {"created_at":2000,"kind":"URGE","source":"HOME","status":"QUESTIONS","raw_text":"note"},
            {"created_at":3000,"kind":"NOPE","source":"HOME","status":"DONE"}]}"""
        val r = tools().import(file)
        assertTrue(r.ok)
        val all = Store.urgeEntries(ctx).all()
        assertEquals(UrgeStatus.SKIPPED, all[0].status); assertNull(all[0].rawText)
        assertEquals(UrgeStatus.PENDING_DEEPDIVE, all[1].status); assertEquals("note", all[1].rawText)
        assertEquals(1, r.skipped)
    }
}
