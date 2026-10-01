package io.github.warleysr.dechainer

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.store.AppStateKeys
import io.github.warleysr.dechainer.store.DechainerDatabase
import io.github.warleysr.dechainer.store.Migrations
import io.github.warleysr.dechainer.store.Store
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real SQLite store, on Robolectric. The plain Application: the app's own one would start the lock engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppStateRepositoryTest {
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

    private val state get() = Store.appState(context)

    @Test
    fun aMissingKeyIsNull() {
        assertNull(state.get("nothing"))
        assertNull(state.getLong("nothing"))
    }

    @Test
    fun aValueComesBackAndCanBeReplaced() {
        state.set("k", "one")
        assertEquals("one", state.get("k"))
        state.set("k", "two")
        assertEquals("two", state.get("k"))
        assertEquals(mapOf("k" to "two"), state.all())
    }

    @Test
    fun removeDeletesOnlyThatKey() {
        state.setAll(mapOf("a" to "1", "b" to "2"))
        state.remove("a")
        assertNull(state.get("a"))
        assertEquals("2", state.get("b"))
    }

    @Test
    fun aValueThatIsNotANumberReadsAsNullAndIsLeftUntouched() {
        state.set("n", "not a number")
        assertNull(state.getLong("n"))
        assertNull(state.getInt("n"))
        assertEquals("not a number", state.get("n"))
        // Reading it as a number did not rewrite it.
        assertEquals(mapOf("n" to "not a number"), state.all())
    }

    @Test
    fun numbersRoundTrip() {
        state.set("l", Long.MAX_VALUE.toString())
        state.set("i", (-7).toString())
        assertEquals(Long.MAX_VALUE, state.getLong("l"))
        assertEquals(-7, state.getInt("i"))
    }

    @Test
    fun setAllIsAllOrNothing() {
        state.set("keep", "before")
        // A map that yields one pair and then fails: the first pair must not survive.
        val exploding = object : AbstractMap<String, String>() {
            override val entries: Set<Map.Entry<String, String>>
                get() = object : AbstractSet<Map.Entry<String, String>>() {
                    override val size = 2
                    override fun iterator() = object : Iterator<Map.Entry<String, String>> {
                        private var i = 0
                        override fun hasNext() = true
                        override fun next(): Map.Entry<String, String> {
                            if (i++ == 0) return java.util.AbstractMap.SimpleEntry("half", "written")
                            throw IllegalStateException("boom")
                        }
                    }
                }
        }
        try {
            state.setAll(exploding)
            fail("expected the failure to come through")
        } catch (_: IllegalStateException) {
        }
        assertNull("the first pair was rolled back", state.get("half"))
        assertEquals("before", state.get("keep"))
    }

    @Test
    fun dataSurvivesTheDatabaseBeingClosedAndReopened() {
        state.set("persisted", "yes")
        Store.resetForTests()
        assertEquals("yes", Store.appState(context).get("persisted"))
    }

    @Test
    fun theSchemaIsAtTheLatestVersionAndRunsInWriteAheadLogMode() {
        val db = DechainerDatabase(context)
        try {
            assertEquals(Migrations.LATEST, db.readableDatabase.version)
            db.readableDatabase.rawQuery("PRAGMA journal_mode", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("wal", it.getString(0).lowercase())
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun aOneOffMigrationRunsOnceAfterItSucceeds() {
        var runs = 0
        repeat(3) { state.runOnce("demo") { runs++; true } }
        assertEquals(1, runs)
        assertEquals("1", state.get(AppStateKeys.MIGRATED_PREFIX + "demo"))
    }

    @Test
    fun aOneOffMigrationThatFailedIsTriedAgain() {
        var runs = 0
        state.runOnce("flaky") { runs++; false }
        assertFalse(state.get(AppStateKeys.MIGRATED_PREFIX + "flaky") == "1")
        state.runOnce("flaky") { runs++; true }
        state.runOnce("flaky") { runs++; true }
        assertEquals(2, runs)
    }
}
