package io.github.warleysr.dechainer.setup

/** The checks of the setup guide and of the Setup status card in Settings (blueprint 11), in the order they are walked. */
enum class SetupItem {
    DEVICE_OWNER,
    NOTIFICATIONS,
    FULL_SCREEN,
    USAGE_ACCESS,
    BATTERY,
    RECOVERY_CODE,
    AI_KEY
}

data class SetupCheck(val item: SetupItem, val done: Boolean)

/** Which checks apply on this phone, and what is still open. Pure: the phone's facts are passed in. */
object SetupStatus {
    /** Xiaomi, Redmi and POCO phones stop apps in the background unless told otherwise (blueprint 11, step 6). */
    fun isXiaomiFamily(manufacturer: String, brand: String): Boolean =
        listOf("xiaomi", "redmi", "poco").any { it in manufacturer.lowercase() || it in brand.lowercase() }

    fun applies(item: SetupItem, sdk: Int, xiaomiFamily: Boolean): Boolean = when (item) {
        SetupItem.FULL_SCREEN -> sdk >= 34
        SetupItem.BATTERY -> xiaomiFamily
        else -> true
    }

    /** The checks that still need something from the owner, in guide order. */
    fun open(checks: List<SetupCheck>): List<SetupCheck> = checks.filter { !it.done }.sortedBy { it.item.ordinal }

    /** The optional AI key never keeps setup open. */
    fun complete(checks: List<SetupCheck>): Boolean = open(checks).all { it.item == SetupItem.AI_KEY }
}
