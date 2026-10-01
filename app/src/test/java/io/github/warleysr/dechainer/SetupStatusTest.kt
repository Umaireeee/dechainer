package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.setup.SetupCheck
import io.github.warleysr.dechainer.setup.SetupItem
import io.github.warleysr.dechainer.setup.SetupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStatusTest {
    @Test fun xiaomiPhonesAreRecognisedByMakerOrBrand() {
        assertTrue(SetupStatus.isXiaomiFamily("Xiaomi", "Redmi"))
        assertTrue(SetupStatus.isXiaomiFamily("XIAOMI", "POCO"))
        assertTrue(SetupStatus.isXiaomiFamily("Other", "poco"))
        assertFalse(SetupStatus.isXiaomiFamily("Google", "google"))
    }

    @Test fun onlyTheChecksThatApplyAreAsked() {
        assertFalse(SetupStatus.applies(SetupItem.FULL_SCREEN, 33, false))
        assertTrue(SetupStatus.applies(SetupItem.FULL_SCREEN, 34, false))
        assertFalse(SetupStatus.applies(SetupItem.BATTERY, 36, false))
        assertTrue(SetupStatus.applies(SetupItem.BATTERY, 30, true))
        assertTrue(SetupStatus.applies(SetupItem.DEVICE_OWNER, 30, false))
    }

    @Test fun openChecksComeInGuideOrderAndTheAiKeyNeverKeepsSetupOpen() {
        val checks = listOf(
            SetupCheck(SetupItem.AI_KEY, false), SetupCheck(SetupItem.USAGE_ACCESS, false),
            SetupCheck(SetupItem.DEVICE_OWNER, true), SetupCheck(SetupItem.NOTIFICATIONS, false)
        )
        assertEquals(listOf(SetupItem.NOTIFICATIONS, SetupItem.USAGE_ACCESS, SetupItem.AI_KEY), SetupStatus.open(checks).map { it.item })
        assertFalse(SetupStatus.complete(checks))
        assertTrue(SetupStatus.complete(listOf(SetupCheck(SetupItem.DEVICE_OWNER, true), SetupCheck(SetupItem.AI_KEY, false))))
    }
}
