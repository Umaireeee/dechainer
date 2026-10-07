package io.github.warleysr.dechainer

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.focus.CheckinAnswer
import io.github.warleysr.dechainer.focus.Flavor
import io.github.warleysr.dechainer.focus.FocusSource
import io.github.warleysr.dechainer.focus.ResetResult
import io.github.warleysr.dechainer.focus.SessionOutcome
import io.github.warleysr.dechainer.lock.LockStateStore
import io.github.warleysr.dechainer.store.DataTools
import io.github.warleysr.dechainer.store.DechainerDatabase
import io.github.warleysr.dechainer.store.DeleteResult
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
    private fun tools() = DataTools(ctx) { now }

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

    @Test fun aFinishedSlipCanBeDeleted() {
        val slip = entry(ms(10, 20, 9), UrgeKind.SLIP)
        assertEquals(DeleteResult.DELETED, tools().deleteUrge(slip))
        assertNull(Store.urgeEntries(ctx).get(slip))
    }

    @Test fun aCompletedSessionCanBeDeleted() {
        val id = session(ms(10, 20, 9))
        assertEquals(DeleteResult.DELETED, tools().deleteSession(id))
        assertTrue(Store.focus(ctx).checkins(id).isEmpty())
        assertEquals(DeleteResult.MISSING, tools().deleteSession(id))
    }

    // ---- wipe ----

    @Test fun wipeRemovesFinishedEntriesAndSessions() {
        val old = entry(ms(10, 8, 9)); val slipToday = entry(ms(10, 20, 9), UrgeKind.SLIP); val urgeToday = entry(ms(10, 20, 9))
        val oldSession = session(ms(10, 9, 9)); val sessionToday = session(ms(10, 20, 9))

        val r = tools().wipeAll()
        assertNull(Store.urgeEntries(ctx).get(old)); assertNull(Store.urgeEntries(ctx).get(slipToday)); assertNull(Store.urgeEntries(ctx).get(urgeToday))
        assertNull(Store.focus(ctx).session(oldSession)); assertNull(Store.focus(ctx).session(sessionToday))
        assertEquals(5, r.removed)
        assertEquals(0, r.kept)
    }

    // ---- export and import ----

    private fun fill() {
        val repo = Store.urgeEntries(ctx)
        val id = repo.insert(UrgeKind.SLIP, UrgeSource.TILE, ms(10, 6)); repo.markWriting(id); repo.saveNote(id, "n")
        repo.saveAnswers(id, listOf(Answer("q", "p", "a"))); repo.saveDeepDive(id, "## Deep dive")
        entry(ms(10, 7)) // a counted stub
        session(ms(10, 8, 9))
    }

    private fun counts() = listOf(
        Store.urgeEntries(ctx).count(), Store.focus(ctx).count(), Store.focus(ctx).allCheckins().size
    )

    @Test fun anExportReadsBackIntoAnEmptyStore() {
        fill()
        val before = counts()
        val file = tools().export()
        assertFalse(file.contains("app_state")) // the trusted-clock state is never exported

        Store.resetForTests(); ctx.deleteDatabase(DechainerDatabase.FILE_NAME)
        val r = tools().import(file)
        assertTrue(r.ok)
        assertEquals(before, counts())
        assertEquals("## Deep dive", Store.urgeEntries(ctx).all().first().deepDive)
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
