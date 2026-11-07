package io.github.warleysr.dechainer.models

/**
 * Blocks one element (and everything it covers) of a screen without blocking the rest of that
 * screen. An element is matched by [viewId] (its accessibility resource id) and/or [text] (its
 * text or content description); at least one of the two is set.
 */
data class RegionRule(
    val id: String,
    val packageName: String,
    val viewId: String?,
    val text: String?,
    val label: String,
    /** Also cover everything below the element, down to the end of its scrolling container. */
    val extendDown: Boolean = false,
    /** Activity the rule was captured on; null means any screen of the app. */
    val activityClass: String? = null
)
