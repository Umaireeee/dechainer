package io.github.warleysr.dechainer.viewmodels

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.warleysr.dechainer.R

/**
 * Every screen the single Activity can show (blueprint 6.1), in one place instead of strings
 * repeated across `when` blocks. [isRoot] screens are the menu's destinations; the rest open from
 * them. [needsUnlock] screens sit behind the entry gate; Home does not, so the Urge button is always
 * one tap away.
 */
enum class Route(
    val id: String,
    @StringRes val title: Int,
    val icon: ImageVector?,
    val isRoot: Boolean,
    val needsUnlock: Boolean = true,
    @StringRes val menuTitle: Int = title
) {
    HOME("home", R.string.app_name, null, isRoot = true, needsUnlock = false),
    FOCUS("focus", R.string.focus_tab, Icons.Outlined.Timer, isRoot = true),
    // Today stays reachable on a punishment day (6.4), so it is not behind the entry gate.
    TODAY("today", R.string.today_tab, Icons.Outlined.Checklist, isRoot = true, needsUnlock = false),
    SCHEDULES("schedules", R.string.schedules, Icons.Outlined.Schedule, isRoot = true),
    APPS("apps", R.string.apps, Icons.Outlined.Block, isRoot = true, menuTitle = R.string.menu_apps_limits),
    SETTINGS("config", R.string.settings, Icons.Outlined.Settings, isRoot = true),
    FOCUS_LOG("focus_log", R.string.focus_log, null, isRoot = false),
    SCHEDULE_EDITOR("schedule_editor", R.string.schedules, null, isRoot = false),
    RESTRICTIONS("restrictions", R.string.protections, null, isRoot = false),
    ENTRY_CHALLENGE("entry_challenge", R.string.entry_challenge, null, isRoot = false),
    SETUP_DEVICE_OWNER("setup_device_owner", R.string.app_name, null, isRoot = false),
    URGE_SETTINGS("urge_settings", R.string.urge_settings_title, null, isRoot = false);

    companion object {
        /** What the menu offers after "Urge", in order. Today and Reports join when their phases are built. */
        val MENU = listOf(TODAY, FOCUS, SCHEDULES, APPS, SETTINGS)

        /**
         * The menu while a brick holds the phone: nothing here changes settings, so nothing here is
         * offered. (The Urge button stays on every screen of a brick.)
         */
        fun menuFor(brick: Boolean): List<Route> = if (brick) listOf(TODAY) else MENU
    }
}
