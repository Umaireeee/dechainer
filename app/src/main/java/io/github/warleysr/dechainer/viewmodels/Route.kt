package io.github.warleysr.dechainer.viewmodels

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.AutoStories
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
 * them.
 */
enum class Route(
    val id: String,
    @StringRes val title: Int,
    val icon: ImageVector?,
    val isRoot: Boolean,
    @StringRes val menuTitle: Int = title
) {
    HOME("home", R.string.app_name, null, isRoot = true),
    FOCUS("focus", R.string.focus_tab, Icons.Outlined.Timer, isRoot = true),
    TODAY("today", R.string.today_tab, Icons.Outlined.Checklist, isRoot = true),
    SCHEDULES("schedules", R.string.schedules, Icons.Outlined.Schedule, isRoot = true),
    APPS("apps", R.string.apps, Icons.Outlined.Block, isRoot = true, menuTitle = R.string.menu_apps_limits),
    REPORTS("reports", R.string.reports_tab, Icons.Outlined.Assessment, isRoot = true),
    JOURNAL("journal", R.string.journal_title, Icons.Outlined.AutoStories, isRoot = true),
    SETTINGS("config", R.string.settings, Icons.Outlined.Settings, isRoot = true),
    FOCUS_LOG("focus_log", R.string.focus_log, null, isRoot = false),
    SCHEDULE_EDITOR("schedule_editor", R.string.schedules, null, isRoot = false),
    RESTRICTIONS("restrictions", R.string.protections, null, isRoot = false),
    SETUP_DEVICE_OWNER("setup_device_owner", R.string.app_name, null, isRoot = false),
    URGE_SETTINGS("urge_settings", R.string.urge_settings_title, null, isRoot = false),
    DATA("data", R.string.data_title, null, isRoot = false),
    ENTRY("entry", R.string.entry_title, null, isRoot = false),
    APP_LOCK("app_lock", R.string.applock_title, null, isRoot = false);

    companion object {
        /** What the menu offers after "Urge", in order. */
        val MENU = listOf(TODAY, FOCUS, SCHEDULES, APPS, REPORTS, JOURNAL, SETTINGS)

        /**
         * The menu while a brick holds the phone: nothing here changes settings, so nothing here is
         * offered. (The Urge button stays on every screen of a brick.)
         */
        fun menuFor(brick: Boolean): List<Route> = if (brick) listOf(TODAY, REPORTS, JOURNAL) else MENU

        /**
         * The screens that stay open while a punishment day or an urge lock holds the phone: they only
         * read (Reports, the deep dives) or write the plan for tomorrow (Today). Everything else shows Home.
         */
        val OPEN_WHILE_LOCKED = setOf(TODAY, REPORTS, JOURNAL, ENTRY)
    }
}
