package io.github.warleysr.dechainer.screens.common

import android.os.Bundle
import io.github.warleysr.dechainer.models.TimeLimit
import io.github.warleysr.dechainer.models.TimeWindow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PendingChangeTest {

    @Test
    fun `isLooserSet returns false when adding new elements or keeping same`() {
        val oldSet = setOf("app1", "app2")
        val newSetWithMore = setOf("app1", "app2", "app3")
        val sameSet = setOf("app1", "app2")

        assertFalse(isLooserSet(oldSet, newSetWithMore))
        assertFalse(isLooserSet(oldSet, sameSet))
    }

    @Test
    fun `isLooserSet returns true when any element is removed`() {
        val oldSet = setOf("app1", "app2", "app3")
        val newSetWithFewer = setOf("app1", "app2")
        val completelyDifferent = setOf("app4", "app5")

        assertTrue(isLooserSet(oldSet, newSetWithFewer))
        assertTrue(isLooserSet(oldSet, completelyDifferent))
    }

    @Test
    fun `isLooserRestrictions detects boolean loosening`() {
        val current = Bundle().apply {
            putBoolean("key1", true)
            putBoolean("key2", false)
        }
        val loosened = Bundle().apply {
            putBoolean("key1", false)
            putBoolean("key2", false)
        }
        val tightened = Bundle().apply {
            putBoolean("key1", true)
            putBoolean("key2", true)
        }
        val same = Bundle().apply {
            putBoolean("key1", true)
            putBoolean("key2", false)
        }

        assertTrue(isLooserRestrictions(current, loosened))
        assertFalse(isLooserRestrictions(current, tightened))
        assertFalse(isLooserRestrictions(current, same))
    }

    @Test
    fun `isLooserRestrictions detects string and array loosening`() {
        val current = Bundle().apply {
            putString("strKey", "some_value")
            putStringArray("arrKey", arrayOf("rule1", "rule2"))
        }
        val loosenedStr = Bundle().apply {
            putString("strKey", "")
            putStringArray("arrKey", arrayOf("rule1", "rule2"))
        }
        val changedStr = Bundle().apply {
            putString("strKey", "other_value")
            putStringArray("arrKey", arrayOf("rule1", "rule2"))
        }
        val loosenedArr = Bundle().apply {
            putString("strKey", "some_value")
            putStringArray("arrKey", arrayOf("rule1"))
        }
        val tightened = Bundle().apply {
            putString("strKey", "some_value")
            putStringArray("arrKey", arrayOf("rule1", "rule2", "rule3"))
        }

        assertTrue(isLooserRestrictions(current, loosenedStr))
        assertTrue(isLooserRestrictions(current, changedStr))
        assertTrue(isLooserRestrictions(current, loosenedArr))
        assertFalse(isLooserRestrictions(current, tightened))
    }

    @Test
    fun `isLooserRestrictions detects URLBlocklist loosening`() {
        val current = Bundle().apply {
            putStringArray("URLBlocklist", arrayOf("pornhub.com", "xvideos.com"))
        }
        val loosened = Bundle().apply {
            putStringArray("URLBlocklist", arrayOf("pornhub.com"))
        }
        val tightened = Bundle().apply {
            putStringArray("URLBlocklist", arrayOf("pornhub.com", "xvideos.com", "gambling.com"))
        }

        assertTrue(isLooserRestrictions(current, loosened))
        assertFalse(isLooserRestrictions(current, tightened))
    }

    @Test
    fun `isLooserRestrictions blocks new allowlist, numeric changes, and inverted Allow booleans`() {
        val currentWithBlock = Bundle().apply {
            putStringArray("URLBlocklist", arrayOf("*"))
            putString("DnsOverHttpsMode", "secure")
            putInt("DownloadRestrictions", 3)
            putBoolean("AllowDeletingBrowserHistory", false)
        }

        // Adding a new URLAllowlist key must be flagged as loosening.
        val addedAllowlist = Bundle(currentWithBlock).apply {
            putStringArray("URLAllowlist", arrayOf("*"))
        }
        assertTrue(isLooserRestrictions(currentWithBlock, addedAllowlist))

        // Changing string policy e.g. DnsOverHttpsMode "secure" -> "off" is loosening.
        val dnsOff = Bundle(currentWithBlock).apply {
            putString("DnsOverHttpsMode", "off")
        }
        assertTrue(isLooserRestrictions(currentWithBlock, dnsOff))

        // Weakening integer restriction e.g. 3 -> 1 is loosening.
        val lowerInt = Bundle(currentWithBlock).apply {
            putInt("DownloadRestrictions", 1)
        }
        assertTrue(isLooserRestrictions(currentWithBlock, lowerInt))

        // Enabling an Allow* boolean false -> true is loosening.
        val allowHistory = Bundle(currentWithBlock).apply {
            putBoolean("AllowDeletingBrowserHistory", true)
        }
        assertTrue(isLooserRestrictions(currentWithBlock, allowHistory))

        // Unchanged bundle + extra URLBlocklist entry is NOT loosening.
        val stricter = Bundle(currentWithBlock).apply {
            putStringArray("URLBlocklist", arrayOf("*", "example.com"))
        }
        assertFalse(isLooserRestrictions(currentWithBlock, stricter))
    }

    @Test
    fun `isLooserWindows evaluates span coverage including overnight and split spans`() {
        val overnight = listOf(TimeWindow(22 * 60, 2 * 60, setOf(1, 2, 3, 4, 5)))
        // Merging adjacent spans [9:00-12:00, 12:00-17:00] into [9:00-17:00] allows identical minutes.
        val split = listOf(TimeWindow(9 * 60, 12 * 60), TimeWindow(12 * 60, 17 * 60))
        val merged = listOf(TimeWindow(9 * 60, 17 * 60))
        assertFalse(isLooserWindows(split, merged))
        assertFalse(isLooserWindows(merged, split))

        // Expanding overnight end time or adding weekend days is loosening.
        assertTrue(isLooserWindows(overnight, listOf(TimeWindow(22 * 60, 3 * 60, setOf(1, 2, 3, 4, 5)))))
        assertTrue(isLooserWindows(overnight, listOf(TimeWindow(22 * 60, 2 * 60, setOf(1, 2, 3, 4, 5, 6)))))
        assertTrue(isLooserWindows(overnight, emptyList()))

        // Tightening from unrestricted to restricted is not loosening.
        assertFalse(isLooserWindows(emptyList(), overnight))
    }

    @Test
    fun `isLooserLimit checks daily and per-day caps`() {
        assertTrue(isLooserLimit(TimeLimit.daily(60), TimeLimit.daily(0)))
        assertTrue(isLooserLimit(TimeLimit.daily(60), TimeLimit.daily(90)))
        assertFalse(isLooserLimit(TimeLimit.daily(60), TimeLimit.daily(30)))
        assertFalse(isLooserLimit(TimeLimit.NONE, TimeLimit.daily(60)))

        val oldWeekly = TimeLimit.perDay(listOf(60, 60, 60, 60, 60, 60, 60))
        val raisedSunday = TimeLimit.perDay(listOf(60, 60, 60, 60, 60, 60, 61))
        val loweredSunday = TimeLimit.perDay(listOf(60, 60, 60, 60, 60, 60, 45))
        assertTrue(isLooserLimit(oldWeekly, raisedSunday))
        assertFalse(isLooserLimit(oldWeekly, loweredSunday))
    }
}
