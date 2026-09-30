package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.data.composeSchedules
import io.github.warleysr.dechainer.data.parseSchedules
import io.github.warleysr.dechainer.models.BlockSchedule
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class StorageSafetyTest {
    private fun schedule(id: String) =
        BlockSchedule(id, "s$id", days = setOf(DayOfWeek.MONDAY), startMinute = 60, endMinute = 120)

    @Test
    fun oneBadScheduleDoesNotCostTheOthers() {
        val text = JSONArray()
            .put(schedule("a").toJson())
            .put(org.json.JSONObject().put("name", "no id, will not parse"))
            .put("not even an object")
            .put(schedule("b").toJson())
            .toString()
        val read = parseSchedules(text)
        assertTrue(read.rootOk)
        assertEquals(listOf("a", "b"), read.schedules.map { it.id })
        assertEquals(2, read.unreadable.size)
    }

    @Test
    fun savingPutsTheUnreadableEntriesBack() {
        val text = JSONArray().put(schedule("a").toJson()).put(org.json.JSONObject().put("name", "odd")).toString()
        val read = parseSchedules(text)
        // An edit adds a schedule; the odd entry must still be in what gets stored.
        val saved = composeSchedules(read.schedules + schedule("c"), read.unreadable)
        val again = parseSchedules(saved)
        assertEquals(listOf("a", "c"), again.schedules.map { it.id })
        assertEquals(1, again.unreadable.size)
    }

    @Test
    fun aStoredValueThatIsNotAListIsNeverTreatedAsEmpty() {
        val broken = parseSchedules("{this is not json")
        assertFalse(broken.rootOk)
        assertTrue(parseSchedules(null).rootOk)
        assertTrue(parseSchedules(null).schedules.isEmpty())
        assertFalse(parseSchedules("{\"a\":1}").rootOk)
    }
}
