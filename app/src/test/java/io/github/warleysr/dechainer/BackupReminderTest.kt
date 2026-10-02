package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.report.BackupReminder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupReminderTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    @Test
    fun nothingToKeepMeansNoReminder() = assertFalse(BackupReminder.due(null, null, now))

    @Test
    fun aNewOwnerIsRemindedAfterAWeekOfData() {
        assertFalse(BackupReminder.due(null, now - 6 * day, now))
        assertTrue(BackupReminder.due(null, now - 7 * day, now))
    }

    @Test
    fun afterABackupTheNextReminderComesAMonthLater() {
        assertFalse(BackupReminder.due(now - 29 * day, now - 400 * day, now))
        assertTrue(BackupReminder.due(now - 30 * day, now - 400 * day, now))
    }
}
