package io.github.warleysr.dechainer.models

import java.util.concurrent.TimeUnit

/** The groups of the Apps screen, in the order they are shown. */
enum class AppSection {
    /** Apps that already have a rule: a limit, a schedule, a suspension, a block or a group rule. */
    CONTROLLED,

    /** Apps with no rule, ranked by this week's screen time. */
    MOST_USED,

    /** Apps used a little this week. */
    RARELY_USED,

    /** Apps with no use this week. */
    NEVER_USED,

    /** System apps nobody really uses (Settings, Calculator, ...), kept out of the way. */
    SYSTEM_TOOLS,

    /** Used when usage access is missing: nothing can be ranked, so apps are just listed A-Z. */
    ALL_APPS
}

object AppSections {
    /** Under this much screen time in 7 days an app counts as "rarely used". */
    val RARELY_USED_BELOW_MILLIS = TimeUnit.MINUTES.toMillis(10)

    /**
     * Sorts [apps] into sections. System and user apps are mixed on purpose: Android flags YouTube,
     * Chrome or Messages as "system" on many phones, so the flag says nothing about whether an app
     * is a distraction. Real use does.
     *
     * @param weeklyMillis foreground time per package over the last 7 days, or null when usage
     * access isn't granted.
     * @param controlledGroupPackages members of groups that have a limit or a schedule.
     * @return only the non-empty sections, in display order; each list is already sorted.
     */
    fun build(
        apps: List<AppItem>,
        weeklyMillis: Map<String, Long>?,
        controlledGroupPackages: Set<String>
    ): Map<AppSection, List<AppItem>> {
        fun used(app: AppItem) = weeklyMillis?.get(app.packageName) ?: 0L
        fun isControlled(app: AppItem) =
            app.isSuspended || app.isHidden || app.isUninstallBlocked ||
                app.timeLimit.isSet || app.timeWindows.any { it.enabled } ||
                app.packageName in controlledGroupPackages

        val byUsageThenName = compareByDescending<AppItem> { used(it) }.thenBy { it.name.lowercase() }
        val sections = linkedMapOf<AppSection, List<AppItem>>()

        // A controlled app always shows, even one with no launcher icon; the rest need an icon.
        val (controlled, rest) = apps.partition(::isControlled)
        val visible = rest.filter { it.isLaunchable }

        sections[AppSection.CONTROLLED] = controlled.sortedWith(byUsageThenName)

        if (weeklyMillis == null) {
            sections[AppSection.ALL_APPS] = visible.sortedBy { it.name.lowercase() }
        } else {
            val (systemTools, userFacing) = visible.partition {
                it.isSystem && used(it) < RARELY_USED_BELOW_MILLIS
            }
            sections[AppSection.MOST_USED] = userFacing.filter { used(it) >= RARELY_USED_BELOW_MILLIS }
                .sortedWith(byUsageThenName)
            sections[AppSection.RARELY_USED] = userFacing
                .filter { used(it) in 1 until RARELY_USED_BELOW_MILLIS }
                .sortedWith(byUsageThenName)
            sections[AppSection.NEVER_USED] = userFacing.filter { used(it) == 0L }
                .sortedBy { it.name.lowercase() }
            sections[AppSection.SYSTEM_TOOLS] = systemTools.sortedBy { it.name.lowercase() }
        }

        return sections.filterValues { it.isNotEmpty() }
    }
}
