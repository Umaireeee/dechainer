package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.store.Migration
import io.github.warleysr.dechainer.store.Migrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MigrationsTest {
    private val chain = listOf(
        Migration(0, 1, listOf("a1", "a2")),
        Migration(1, 2, listOf("b1")),
        Migration(2, 3, listOf("c1", "c2"))
    )

    @Test
    fun theShippedHistoryIsOneUnbrokenChainFromZero() {
        assertTrue(Migrations.isContiguous())
        assertEquals(Migrations.ALL.size, Migrations.LATEST)
    }

    @Test
    fun theSkeletonCreatesAppState() {
        val sql = Migrations.statementsBetween(0, Migrations.LATEST).joinToString(" ").lowercase()
        assertTrue("app_state" in sql)
    }

    @Test
    fun theSecondStepAddsTheUrgeJournalAndLeavesAppStateAlone() {
        val step = Migrations.ALL.single { it.from == 1 && it.to == 2 }
        val sql = step.statements.joinToString(" ").lowercase()
        assertTrue("create table urge_entry" in sql)
        // Every column of blueprint section 7.
        for (c in listOf("created_at", "kind", "source", "lock_started_at", "lock_ended_at", "status", "raw_text", "questions_json", "answers_json", "deep_dive")) {
            assertTrue(c, c in sql)
        }
        assertFalse("app_state" in sql)
    }

    @Test
    fun theFocusTablesArriveInSchemaVersionThreeAndEarlierStepsAreNotEdited() {
        val before = Migrations.statementsBetween(0, 2).joinToString(" ").lowercase()
        assertFalse("focus_session" in before)
        val v3 = Migrations.statementsBetween(2, 3).joinToString(" ").lowercase()
        assertTrue("focus_session" in v3)
        assertTrue("focus_checkin" in v3)
        assertTrue("planned_end_at" in v3)
        assertTrue("reset_result" in v3)
    }

    @Test
    fun theMonthlyAndYearlyReportsArriveInSchemaVersionSixKeyedByKindAndPeriod() {
        assertFalse("period_report" in Migrations.statementsBetween(0, 5).joinToString(" ").lowercase())
        val v6 = Migrations.statementsBetween(5, 6).joinToString(" ").lowercase()
        assertTrue("create table period_report" in v6)
        assertTrue("unique (kind, period_key)" in v6)
    }

    @Test
    fun theUrgeTableIsRebuiltAsAPlainLogAndTheOldNotesAreDropped() {
        val v10 = Migrations.statementsBetween(9, 10).joinToString(" ").lowercase()
        assertTrue("drop table if exists urge_entry" in v10)
        assertTrue("create table urge_entry" in v10)
        for (c in listOf("created_at", "source", "lock_started_at", "lock_ended_at")) assertTrue(c, c in v10)
        for (c in listOf("raw_text", "questions_json", "answers_json", "deep_dive", "kind", "status")) assertFalse(c, c in v10)
    }

    @Test
    fun aFreshDatabaseRunsEveryStepInOrder() {
        assertEquals(listOf("a1", "a2", "b1", "c1", "c2"), Migrations.statementsBetween(0, 3, chain))
    }

    @Test
    fun anOlderDatabaseRunsOnlyTheStepsItIsMissing() {
        assertEquals(listOf("c1", "c2"), Migrations.statementsBetween(2, 3, chain))
        assertEquals(listOf("b1", "c1", "c2"), Migrations.statementsBetween(1, 3, chain))
    }

    @Test
    fun aDatabaseAlreadyUpToDateRunsNothing() {
        assertEquals(emptyList<String>(), Migrations.statementsBetween(3, 3, chain))
    }

    @Test
    fun aGapInTheHistoryFailsInsteadOfBuildingAHalfSchema() {
        val gap = listOf(Migration(0, 1, listOf("a")), Migration(2, 3, listOf("c")))
        try {
            Migrations.statementsBetween(0, 3, gap)
            fail("expected a failure")
        } catch (_: IllegalStateException) {
        }
        assertFalse(Migrations.isContiguous(gap))
    }

    @Test
    fun aDuplicateStartFailsToo() {
        val dup = listOf(Migration(0, 1, listOf("a")), Migration(0, 1, listOf("again")))
        try {
            Migrations.statementsBetween(0, 1, dup)
            fail("expected a failure")
        } catch (_: IllegalStateException) {
        }
        assertFalse(Migrations.isContiguous(dup))
    }

    @Test
    fun aDowngradeIsRefused() {
        try {
            Migrations.statementsBetween(3, 1, chain)
            fail("expected a failure")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun aTargetInTheMiddleOfAStepIsRefused() {
        val wide = listOf(Migration(0, 2, listOf("x")))
        try {
            Migrations.statementsBetween(0, 1, wide)
            fail("expected a failure")
        } catch (_: IllegalStateException) {
        }
    }
}
