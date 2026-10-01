package io.github.warleysr.dechainer

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.focus.CheckinAnswer
import io.github.warleysr.dechainer.focus.Flavor
import io.github.warleysr.dechainer.focus.FocusSession
import io.github.warleysr.dechainer.focus.FocusSource
import io.github.warleysr.dechainer.focus.ResetResult
import io.github.warleysr.dechainer.focus.SessionOutcome
import io.github.warleysr.dechainer.store.DechainerDatabase
import io.github.warleysr.dechainer.store.Store
import io.github.warleysr.dechainer.store.StoredSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `focus_session` and `focus_checkin` (blueprint section 7), on the real SQLite store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FocusSessionRepositoryTest {
    private lateinit var context: Context
    private val minute = 60_000L
    private val t0 = 1_800_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Store.resetForTests()
        context.deleteDatabase(DechainerDatabase.FILE_NAME)
    }

    @After
    fun tearDown() {
        Store.resetForTests()
        context.deleteDatabase(DechainerDatabase.FILE_NAME)
    }

    private val repo get() = Store.focus(context)

    private fun session(id: Long = t0, purpose: String = "Tax chapter 4") = StoredSession(
        id = id, source = FocusSource.MANUAL, flavor = Flavor.USUAL, purpose = purpose,
        startedAt = id, plannedEndAt = id + 60 * minute, endedAt = null, focusedMinutes = 0, outcome = null
    )

    @Test
    fun aSessionComesBackAsItWasStored() {
        val id = repo.insert(session())
        assertEquals(t0, id)
        assertEquals(session(), repo.session(id))
    }

    @Test
    fun twoSessionsStartingInTheSameMillisecondBothSurvive() {
        val a = repo.insert(session())
        val b = repo.insert(session(purpose = "Audit notes"))
        assertEquals(t0, a)
        assertEquals(t0 + 1, b)
        assertEquals(2, repo.count())
        assertEquals("Audit notes", repo.session(b)?.purpose)
    }

    @Test
    fun theChoiceAtThePromptUpdatesTheRow() {
        val id = repo.insert(session(purpose = "").copy(source = FocusSource.SCHEDULED, flavor = Flavor.SPECIAL))
        repo.updateChoice(id, Flavor.USUAL, "Audit notes")
        val row = repo.session(id)!!
        assertEquals(Flavor.USUAL, row.flavor)
        assertEquals("Audit notes", row.purpose)
        assertEquals(FocusSource.SCHEDULED, row.source)
    }

    @Test
    fun endingASessionRecordsItAndASecondEndingKeepsTheFirst() {
        val id = repo.insert(session())
        repo.end(id, t0 + 60 * minute, SessionOutcome.COMPLETED, 50)
        repo.end(id, t0 + 90 * minute, SessionOutcome.ENDED_EARLY_BY_SYSTEM, 10)
        val row = repo.session(id)!!
        assertEquals(t0 + 60 * minute, row.endedAt)
        assertEquals(SessionOutcome.COMPLETED, row.outcome)
        assertEquals(50, row.focusedMinutes)
    }

    @Test
    fun checkinsBelongToTheirSessionAndComeBackInOrder() {
        val a = repo.insert(session())
        val b = repo.insert(session(id = t0 + 10 * minute))
        repo.addCheckin(a, t0 + 30 * minute, CheckinAnswer.NO, ResetResult.READY)
        repo.addCheckin(a, t0 + 10 * minute, CheckinAnswer.YES, ResetResult.NONE)
        repo.addCheckin(b, t0 + 40 * minute, CheckinAnswer.UNANSWERED, ResetResult.NONE)
        val forA = repo.checkins(a)
        assertEquals(listOf(CheckinAnswer.YES, CheckinAnswer.NO), forA.map { it.answer })
        assertEquals(ResetResult.READY, forA.last().reset)
        assertEquals(1, repo.checkins(b).size)
    }

    @Test
    fun deletingASessionRemovesItsCheckinsAndNothingElse() {
        val a = repo.insert(session())
        val b = repo.insert(session(id = t0 + 10 * minute))
        repo.addCheckin(a, t0, CheckinAnswer.YES, ResetResult.NONE)
        repo.addCheckin(b, t0, CheckinAnswer.NO, ResetResult.NONE)
        repo.delete(a)
        assertNull(repo.session(a))
        assertTrue(repo.checkins(a).isEmpty())
        assertEquals(1, repo.checkins(b).size)
        assertEquals(1, repo.count())
    }

    @Test
    fun theLogReadsAnySessionWithANoAsNotDoneAndAllYesAsDoneAndNoAnswerAsUnanswered() {
        val yes = repo.insert(session(id = t0))
        val mixed = repo.insert(session(id = t0 + 100 * minute))
        val none = repo.insert(session(id = t0 + 200 * minute, purpose = ""))
        repo.end(yes, t0 + 50 * minute, SessionOutcome.COMPLETED, 50)
        repo.addCheckin(yes, t0 + 25 * minute, CheckinAnswer.YES, ResetResult.NONE)
        repo.addCheckin(yes, t0 + 50 * minute, CheckinAnswer.UNANSWERED, ResetResult.NONE)
        repo.addCheckin(mixed, t0 + 120 * minute, CheckinAnswer.YES, ResetResult.NONE)
        repo.addCheckin(mixed, t0 + 140 * minute, CheckinAnswer.NO, ResetResult.NOT_READY)

        val log = repo.log().associateBy { it.id }
        assertEquals(true, log.getValue(yes).done)
        assertEquals(false, log.getValue(mixed).done)
        assertNull(log.getValue(none).done)
        assertEquals(50, log.getValue(yes).minutes)
        assertEquals("Tax chapter 4", log.getValue(yes).intention)
        assertNull("no purpose reads as none", log.getValue(none).intention)
        assertEquals("oldest first", listOf(yes, mixed, none), repo.log().map { it.id })
    }

    // ---- The old log (blueprint 13) ----

    private val oldLog = listOf(
        FocusSession(id = t0, minutes = 25, done = true, intention = "Revenue recognition"),
        FocusSession(id = t0 + 40 * minute, minutes = 30, done = false),
        FocusSession(id = t0 + 90 * minute, minutes = 25, done = null)
    )

    @Test
    fun theOldLogIsImportedWithItsAnswers() {
        assertEquals(3, repo.importLegacy(oldLog))
        val log = repo.log()
        assertEquals(listOf(true, false, null), log.map { it.done })
        assertEquals(listOf(25, 30, 25), log.map { it.minutes })
        assertEquals("Revenue recognition", log.first().intention)
        val row = repo.session(t0)!!
        assertEquals(Flavor.USUAL, row.flavor)
        assertEquals(SessionOutcome.COMPLETED, row.outcome)
        assertEquals(t0 + 25 * minute, row.endedAt)
    }

    @Test
    fun importingTheOldLogTwiceAddsNothingTheSecondTime() {
        assertEquals(3, repo.importLegacy(oldLog))
        assertEquals(0, repo.importLegacy(oldLog))
        assertEquals(3, repo.count())
        assertEquals("answers are not doubled", 1, repo.checkins(t0).size)
    }

    // ---- A failed read never overwrites ----

    @Test
    fun aRowThatCannotBeReadIsSkippedByReadsAndLeftUntouched() {
        val good = repo.insert(session(id = t0))
        val db = DechainerDatabase(context)
        db.writableDatabase.execSQL(
            "INSERT INTO focus_session (id, source, flavor, purpose, started_at, planned_end_at, focused_minutes) " +
                "VALUES (${t0 + minute}, 'FROM_THE_FUTURE', 'USUAL', 'odd', ${t0 + minute}, ${t0 + 2 * minute}, 7)"
        )
        db.close()

        assertEquals("the unreadable row is skipped", listOf(good), repo.sessions().map { it.id })
        assertEquals(2, repo.count())
        repo.delete(good)
        repo.addCheckin(good, t0, CheckinAnswer.YES, ResetResult.NONE)
        assertEquals("deleting another row did not touch it", 1, repo.count())
        val kept = DechainerDatabase(context).readableDatabase
            .rawQuery("SELECT source, purpose, focused_minutes FROM focus_session", null)
            .use { c -> c.moveToFirst(); Triple(c.getString(0), c.getString(1), c.getInt(2)) }
        assertEquals(Triple("FROM_THE_FUTURE", "odd", 7), kept)
    }
}
