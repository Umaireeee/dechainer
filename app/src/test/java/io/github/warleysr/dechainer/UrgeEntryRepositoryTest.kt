package io.github.warleysr.dechainer

import android.app.Application
import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.store.DechainerDatabase
import io.github.warleysr.dechainer.store.Migrations
import io.github.warleysr.dechainer.store.Store
import io.github.warleysr.dechainer.urge.Answer
import io.github.warleysr.dechainer.urge.Question
import io.github.warleysr.dechainer.urge.QuestionType
import io.github.warleysr.dechainer.urge.UrgeJson
import io.github.warleysr.dechainer.urge.UrgeKind
import io.github.warleysr.dechainer.urge.UrgeSource
import io.github.warleysr.dechainer.urge.UrgeStatus
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

/** The urge journal's table on the real SQLite store (Robolectric), through the repository. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UrgeEntryRepositoryTest {
    private lateinit var context: Context
    private val q = listOf(Question("q1", QuestionType.TEXT, "Where?"), Question("q2", QuestionType.TEXT, "When?"))
    private val a = listOf(Answer("q1", "Where?", "Sofa"), Answer("q2", "When?", "Late"))

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
    fun anUrgeStartsLockedAndASlipStartsWriting() {
        val urge = repo.insert(UrgeKind.URGE, UrgeSource.HOME, 1_000L)
        val slip = repo.insert(UrgeKind.SLIP, UrgeSource.HOME, 2_000L)
        assertEquals(UrgeStatus.LOCKED, repo.get(urge)?.status)
        assertEquals(UrgeStatus.WRITING, repo.get(slip)?.status)
        assertEquals(2, repo.count())
    }

    @Test
    fun theLockWindowIsStoredAfterTheEntry() {
        val id = repo.insert(UrgeKind.URGE, UrgeSource.TILE, 1_000L)
        assertNull(repo.get(id)?.lockEndedAt)
        repo.setLockWindow(id, 1_000L, 601_000L)
        val e = repo.get(id)!!
        assertEquals(1_000L, e.lockStartedAt)
        assertEquals(601_000L, e.lockEndedAt)
        assertEquals(UrgeSource.TILE, e.source)
    }

    @Test
    fun theWholeFlowEndsWithTheDeepDiveSavedAndTheNoteGone() {
        val id = repo.insert(UrgeKind.URGE, UrgeSource.HOME, 1_000L, 1_000L, 601_000L)
        assertTrue(repo.markWriting(id))
        assertTrue(repo.saveNote(id, "I was on the sofa"))
        var e = repo.get(id)!!
        assertEquals(UrgeStatus.QUESTIONS, e.status)
        assertEquals("I was on the sofa", e.rawText)
        assertNull("the note is stored before the questions exist", e.questionsJson)
        assertTrue(repo.saveQuestions(id, q))
        e = repo.get(id)!!
        assertEquals(q, UrgeJson.questionsFromJson(e.questionsJson))
        assertEquals("saving the questions changes nothing else", "I was on the sofa", e.rawText)

        assertTrue(repo.saveAnswers(id, a))
        e = repo.get(id)!!
        assertEquals(UrgeStatus.PENDING_DEEPDIVE, e.status)
        assertEquals("the note stays until the deep dive exists", "I was on the sofa", e.rawText)
        assertEquals(a, UrgeJson.answersFromJson(e.answersJson))

        assertTrue(repo.saveDeepDive(id, "## What happened\nYou sat."))
        e = repo.get(id)!!
        assertEquals(UrgeStatus.DONE, e.status)
        assertEquals("## What happened\nYou sat.", e.deepDive)
        assertNull("raw_text is NULL once the deep dive exists", e.rawText)
    }

    @Test
    fun theCrisisFlagIsStoredWithTheQuestions() {
        val id = repo.insert(UrgeKind.URGE, UrgeSource.HOME, 1_000L)
        repo.markWriting(id)
        repo.saveNote(id, "n")
        assertFalse("off by default", repo.get(id)!!.support)
        assertTrue(repo.saveQuestions(id, q, support = true))
        assertTrue("the flag is read back", repo.get(id)!!.support)
        repo.saveAnswers(id, a)
        assertTrue("and survives the answers", repo.get(id)!!.support)
    }

    @Test
    fun aDeepDiveCannotBeSavedBeforeTheAnswersAndTheNoteSurvivesTheAttempt() {
        val id = repo.insert(UrgeKind.SLIP, UrgeSource.HOME, 1_000L)
        repo.saveNote(id, "keep me")
        assertFalse(repo.saveDeepDive(id, "## early"))
        val e = repo.get(id)!!
        assertEquals(UrgeStatus.QUESTIONS, e.status)
        assertEquals("keep me", e.rawText)
        assertNull(e.deepDive)
    }

    @Test
    fun aBlankDeepDiveIsRefusedAndTheNoteIsKept() {
        val id = repo.insert(UrgeKind.SLIP, UrgeSource.HOME, 1_000L)
        repo.saveNote(id, "keep me")
        repo.saveAnswers(id, a)
        assertFalse(repo.saveDeepDive(id, "   "))
        assertEquals("keep me", repo.get(id)?.rawText)
        assertEquals(UrgeStatus.PENDING_DEEPDIVE, repo.get(id)?.status)
    }

    @Test
    fun aDeepDiveIsSavedOnlyOnce() {
        val id = repo.insert(UrgeKind.SLIP, UrgeSource.HOME, 1_000L)
        repo.saveNote(id, "n")
        repo.saveAnswers(id, a)
        assertTrue(repo.saveDeepDive(id, "first"))
        assertFalse(repo.saveDeepDive(id, "second"))
        assertEquals("first", repo.get(id)?.deepDive)
    }

    @Test
    fun skippingLeavesACountedStubWithNoText() {
        val id = repo.insert(UrgeKind.URGE, UrgeSource.SHORTCUT, 1_000L, 1_000L, 601_000L)
        assertTrue(repo.skip(id))
        val e = repo.get(id)!!
        assertEquals(UrgeStatus.SKIPPED, e.status)
        assertNull(e.rawText)
        assertEquals(1, repo.count())
        assertFalse("nothing moves out of SKIPPED", repo.markWriting(id))
        assertFalse(repo.saveNote(id, "late"))
    }

    @Test
    fun questionsAreStoredOnlyWhileTheEntryIsBeingAnswered() {
        val id = repo.insert(UrgeKind.SLIP, UrgeSource.HOME, 1_000L)
        assertFalse("not before the note", repo.saveQuestions(id, q))
        repo.saveNote(id, "n")
        assertTrue(repo.saveQuestions(id, q))
        repo.saveAnswers(id, a)
        assertFalse("not after the answers", repo.saveQuestions(id, listOf(Question("z", QuestionType.TEXT, "Z?"))))
        assertEquals(q, UrgeJson.questionsFromJson(repo.get(id)?.questionsJson))
    }

    @Test
    fun anEntryWithAnsweredQuestionsCannotBeSkipped() {
        val id = repo.insert(UrgeKind.SLIP, UrgeSource.HOME, 1_000L)
        repo.saveNote(id, "n")
        assertFalse(repo.skip(id))
        assertEquals("n", repo.get(id)?.rawText)
    }

    @Test
    fun theUnfinishedAndPendingListsAreTheRightEntries() {
        val locked = repo.insert(UrgeKind.URGE, UrgeSource.HOME, 1_000L)
        val writing = repo.insert(UrgeKind.SLIP, UrgeSource.HOME, 2_000L)
        val pending = repo.insert(UrgeKind.SLIP, UrgeSource.HOME, 3_000L).also { repo.saveNote(it, "n"); repo.saveAnswers(it, a) }
        val done = repo.insert(UrgeKind.SLIP, UrgeSource.HOME, 4_000L).also {
            repo.saveNote(it, "n"); repo.saveAnswers(it, a); repo.saveDeepDive(it, "d")
        }
        val skipped = repo.insert(UrgeKind.SLIP, UrgeSource.HOME, 5_000L).also { repo.skip(it) }

        assertEquals(listOf(writing, locked), repo.unfinished().map { it.id })
        assertEquals(listOf(pending), repo.pendingDeepDives().map { it.id })
        assertEquals(5, repo.count())
        assertTrue(done != skipped)
    }

    @Test
    fun deletingAnEntryRemovesOnlyThatEntry() {
        val a1 = repo.insert(UrgeKind.URGE, UrgeSource.HOME, 1_000L)
        val b1 = repo.insert(UrgeKind.URGE, UrgeSource.HOME, 2_000L)
        assertTrue(repo.delete(a1))
        assertFalse(repo.delete(a1))
        assertNull(repo.get(a1))
        assertEquals(UrgeStatus.LOCKED, repo.get(b1)?.status)
    }

    @Test
    fun aRowThatCannotBeReadIsSkippedAndNeverTouched() {
        val good = repo.insert(UrgeKind.URGE, UrgeSource.HOME, 1_000L)
        val db = DechainerDatabase(context)
        val bad = db.writableDatabase.insert(
            "urge_entry", null,
            ContentValues().apply {
                put("created_at", 9_000L); put("kind", "FROM_THE_FUTURE"); put("source", "HOME"); put("status", "LOCKED")
                put("raw_text", "precious")
            }
        )
        assertEquals("the unreadable row is not listed", listOf(good), repo.unfinished().map { it.id })
        assertNull(repo.get(bad))
        // Writes that target it by id change nothing: the move needs a status it understands.
        assertFalse(repo.skip(bad))
        db.writableDatabase.query("urge_entry", arrayOf("raw_text", "kind"), "id = ?", arrayOf(bad.toString()), null, null, null).use {
            assertTrue(it.moveToFirst())
            assertEquals("precious", it.getString(0))
            assertEquals("FROM_THE_FUTURE", it.getString(1))
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
        val id = repo.insert(UrgeKind.URGE, UrgeSource.HOME, 1_000L)
        assertEquals(UrgeStatus.LOCKED, repo.get(id)?.status)
        assertEquals("123456", Store.appState(context).get("urgeLockEndsAt"))
    }

    @Test
    fun theFlowSurvivesTheStoreBeingClosedAndReopened() {
        val id = repo.insert(UrgeKind.URGE, UrgeSource.HOME, 1_000L, 1_000L, 601_000L)
        repo.saveNote(id, "still here")
        Store.resetForTests()
        val e = Store.urgeEntries(context).get(id)!!
        assertEquals(UrgeStatus.QUESTIONS, e.status)
        assertEquals("still here", e.rawText)
    }
}
