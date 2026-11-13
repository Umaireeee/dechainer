package io.github.warleysr.dechainer.data

import io.github.warleysr.dechainer.models.DowntimeRule
import io.github.warleysr.dechainer.models.TimeWindow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DowntimeRepositoryTest {
    @Test
    fun `rules survive a save and load, including days and the off switch`() {
        val rules = listOf(
            DowntimeRule(
                id = "1", name = "Bedtime",
                packageNames = setOf("a.b", "c.d"), groupIds = setOf("g1"),
                windows = listOf(TimeWindow(22 * 60, 7 * 60), TimeWindow(60, 120, setOf(6, 7), enabled = false))
            ),
            DowntimeRule(id = "2", name = "Work", packageNames = setOf("x"))
        )
        assertEquals(rules, DowntimeRepository.decode(DowntimeRepository.encode(rules)))
    }

    @Test
    fun `garbage or missing data loads as no rules`() {
        assertEquals(emptyList<DowntimeRule>(), DowntimeRepository.decode(null))
        assertEquals(emptyList<DowntimeRule>(), DowntimeRepository.decode("not json"))
    }
}
