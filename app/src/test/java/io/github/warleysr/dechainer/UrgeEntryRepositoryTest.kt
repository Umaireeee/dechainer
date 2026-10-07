package io.github.warleysr.dechainer

import android.app.Application
import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.store.DechainerDatabase
import io.github.warleysr.dechainer.store.Migrations
import io.github.warleysr.dechainer.store.Store
import io.github.warleysr.dechainer.urge.UrgeSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The urge log's table on the real SQLite store (Robolectric), through the repository. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UrgeEntryRepositoryTest {
    private lateinit var context: Context

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

    private val repo get() = Store.urgeEntries(context)

    @Test
    fun theUrgeTableExistsInTheCurrentSchema() {
        assertTrue(Migrations.LATEST >= 2)
        assertEquals(0, repo.count())
    }

    @Test
    fun anEntryKeepsItsTimeSourceAndLockWindow() {
        val id = repo.insert(UrgeSource.TILE, 1_000L)
        val before = repo.get(id)!!
        assertEquals(1_000L, before.createdAt)
        assertEquals(UrgeSource.TILE, before.source)
        assertNull(before.lockStartedAt)
        assertNull(before.lockEndedAt)

        repo.setLockWindow(id, 1_000L, 601_000L)
        val e = repo.get(id)!!
        assertEquals(1_000L, e.lockStartedAt)
        assertEquals(601_000L, e.lockEndedAt)
    }

    @Test
    fun theEntriesAreReadOldestFirstAndTheLatestIsTheNewest() {
        repo.insert(UrgeSource.HOME, 3_000L)
        repo.insert(UrgeSource.HOME, 1_000L)
        repo.insert(UrgeSource.HOME, 2_000L)
        assertEquals(listOf(1_000L, 2_000L, 3_000L), repo.all().map { it.createdAt })
        assertEquals(3_000L, repo.latest()?.createdAt)
        assertEquals(1_000L, repo.firstCreatedAt())
        assertEquals(3, repo.count())
    }

    @Test
    fun deletingAnEntryRemovesOnlyThatEntry() {
        val a = repo.insert(UrgeSource.HOME, 1_000L)
        val b = repo.insert(UrgeSource.HOME, 2_000L)
        assertTrue(repo.delete(a))
        assertFalse(repo.delete(a))
        assertNull(repo.get(a))
        assertEquals(UrgeSource.HOME, repo.get(b)?.source)
    }

    @Test
    fun aRowThatCannotBeReadIsSkippedAndNeverTouched() {
        val good = repo.insert(UrgeSource.HOME, 1_000L)
        val db = DechainerDatabase(context)
        val bad = db.writableDatabase.insert(
            "urge_entry", null,
            ContentValues().apply {
                put("created_at", 9_000L); put("source", "FROM_THE_FUTURE")
            }
        )
        assertEquals("the unreadable row is not listed", listOf(good), repo.all().map { it.id })
        assertNull(repo.get(bad))
        assertFalse(repo.delete(bad))
        db.writableDatabase.query("urge_entry", arrayOf("source"), "id = ?", arrayOf(bad.toString()), null, null, null).use {
            assertTrue(it.moveToFirst())
            assertEquals("FROM_THE_FUTURE", it.getString(0))
        }
        db.close()
    }

    @Test
    fun aPhoneOnSchemaVersionOneIsUpgradedWithItsAppStateIntact() {
        // A database exactly as Phase 1 and 2 left it: version 1, app_state only, with a stored lock.
        val file = context.getDatabasePath(DechainerDatabase.FILE_NAME)
        file.parentFile?.mkdirs()
        val old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        old.execSQL("CREATE TABLE app_state (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        old.execSQL("INSERT INTO app_state (key, value) VALUES ('urgeLockEndsAt', '123456')")
        old.version = 1
        old.close()

        assertEquals("123456", Store.appState(context).get("urgeLockEndsAt"))
        assertEquals(0, repo.count())
        val id = repo.insert(UrgeSource.HOME, 1_000L)
        assertEquals(UrgeSource.HOME, repo.get(id)?.source)
        assertEquals("123456", Store.appState(context).get("urgeLockEndsAt"))
    }

    @Test
    fun theFlowSurvivesTheStoreBeingClosedAndReopened() {
        val id = repo.insert(UrgeSource.HOME, 1_000L, 1_000L, 601_000L)
        Store.resetForTests()
        val e = Store.urgeEntries(context).get(id)!!
        assertEquals(1_000L, e.lockStartedAt)
        assertEquals(601_000L, e.lockEndedAt)
    }
}
