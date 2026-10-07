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

    private fun entry(at: Long, source: UrgeSource = UrgeSource.HOME): Long =
        Store.urgeEntries(ctx).insert(source, at)

    private fun session(at: Long): Long {
        val repo = Store.focus(ctx)
        val id = repo.insert(StoredSession(at, FocusSource.MANUAL, Flavor.USUAL, "Chapter", at, at + hour, null, 0, null))
        repo.addCheckin(id, at + hour, CheckinAnswer.YES, ResetResult.NONE)
        repo.end(id, at + hour, SessionOutcome.COMPLETED, 25)
        return id
    }

    // ---- delete ----

    @Test fun anUrgeCanBeDeleted() {
        val id = entry(ms(10, 20, 9))
        assertEquals(DeleteResult.DELETED, tools().deleteUrge(id))
        assertNull(Store.urgeEntries(ctx).get(id))
        assertEquals(DeleteResult.MISSING, tools().deleteUrge(id))
    }

    @Test fun aCompletedSessionCanBeDeleted() {
        val id = session(ms(10, 20, 9))
        assertEquals(DeleteResult.DELETED, tools().deleteSession(id))
        assertTrue(Store.focus(ctx).checkins(id).isEmpty())
        assertEquals(DeleteResult.MISSING, tools().deleteSession(id))
    }

    // ---- wipe ----

    @Test fun wipeRemovesEveryUrgeAndFinishedSession() {
        val old = entry(ms(10, 8, 9)); val today = entry(ms(10, 20, 9), UrgeSource.TILE)
        val oldSession = session(ms(10, 9, 9)); val sessionToday = session(ms(10, 20, 9))

        val r = tools().wipeAll()
        assertNull(Store.urgeEntries(ctx).get(old)); assertNull(Store.urgeEntries(ctx).get(today))
        assertNull(Store.focus(ctx).session(oldSession)); assertNull(Store.focus(ctx).session(sessionToday))
        assertEquals(4, r.removed)
        assertEquals(0, r.kept)
    }

    // ---- export and import ----

    private fun fill() {
        val repo = Store.urgeEntries(ctx)
        repo.insert(UrgeSource.TILE, ms(10, 6), ms(10, 6), ms(10, 6) + 10 * 60_000L)
        entry(ms(10, 7))
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
        assertEquals(UrgeSource.TILE, Store.urgeEntries(ctx).all().first().source)
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

    @Test fun anEntryWithAnUnknownSourceIsCountedAndSkipped() {
        val file = """{"format":"dechainer-export","version":1,"urge_entry":[
            {"created_at":1000,"source":"HOME","lock_started_at":1000,"lock_ended_at":601000},
            {"created_at":2000,"source":"NOPE"}]}"""
        val r = tools().import(file)
        assertTrue(r.ok)
        val all = Store.urgeEntries(ctx).all()
        assertEquals(1, all.size)
        assertEquals(UrgeSource.HOME, all[0].source)
        assertEquals(601_000L, all[0].lockEndedAt)
        assertEquals(1, r.skipped)
    }
}
