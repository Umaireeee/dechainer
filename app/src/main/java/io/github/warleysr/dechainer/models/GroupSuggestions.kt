package io.github.warleysr.dechainer.models

import android.content.pm.ApplicationInfo
import io.github.warleysr.dechainer.R

/** A ready-made group the user can create in one tap, e.g. every social app installed on the phone. */
data class GroupSuggestion(val id: String, val nameRes: Int, val apps: List<AppItem>)

object GroupSuggestions {
    private enum class Kind(val id: String, val nameRes: Int, val category: Int) {
        SOCIAL("social", R.string.group_suggestion_social, ApplicationInfo.CATEGORY_SOCIAL),
        GAMES("games", R.string.group_suggestion_games, ApplicationInfo.CATEGORY_GAME),
        VIDEO("video", R.string.group_suggestion_video, ApplicationInfo.CATEGORY_VIDEO)
    }

    /**
     * Many apps never declare a category, so the well-known ones are listed by name too. A name
     * match wins over the declared category.
     */
    private val KNOWN_PACKAGES: Map<String, Kind> = buildMap {
        listOf(
            "com.instagram.android", "com.instagram.barcelona", "com.facebook.katana", "com.facebook.lite",
            "com.twitter.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
            "com.snapchat.android", "com.reddit.frontpage", "com.pinterest", "com.linkedin.android",
            "com.tumblr", "com.bereal.ft"
        ).forEach { put(it, Kind.SOCIAL) }
        listOf(
            "com.google.android.youtube", "com.netflix.mediaclient", "tv.twitch.android.app",
            "com.amazon.avod.thirdpartyclient", "com.disney.disneyplus", "com.hbo.hbonow",
            "com.google.android.youtube.tv"
        ).forEach { put(it, Kind.VIDEO) }
    }

    private fun kindOf(app: AppItem): Kind? =
        KNOWN_PACKAGES[app.packageName] ?: Kind.entries.firstOrNull { it.category == app.category }

    /**
     * Suggests groups from the installed apps. Only apps the user can open and that aren't in a
     * group yet count, a group needs at least [minApps] members to be worth suggesting, and a
     * suggestion the user already turned down is not offered again.
     */
    fun suggest(
        apps: List<AppItem>,
        groups: List<AppGroup>,
        dismissed: Set<String>,
        minApps: Int = 2
    ): List<GroupSuggestion> {
        val grouped = groups.flatMap { it.packageNames }.toSet()
        val candidates = apps.filter { it.isLaunchable && it.packageName !in grouped }
        return Kind.entries
            .filter { it.id !in dismissed }
            .mapNotNull { kind ->
                val members = candidates.filter { kindOf(it) == kind }.sortedBy { it.name.lowercase() }
                if (members.size >= minApps) GroupSuggestion(kind.id, kind.nameRes, members) else null
            }
    }
}
