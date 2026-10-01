package io.github.warleysr.dechainer.data

import android.content.RestrictionsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import org.json.JSONArray
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.net.toUri
import androidx.core.content.edit

class BrowserRestrictionsManager(private val context: Context) {

    private companion object {
        const val KEY_SCHEDULED_APPLIED = "scheduled_sites_applied"
        const val KEY_DOH_LOCKED = "secure_dns_locked"
    }

    fun getPossibleBrowsers(): List<ResolveInfo> {
        val pm = context.packageManager
        val resolvedPackages = mutableSetOf<String>()
        val results = mutableListOf<ResolveInfo>()

        val browserCategoryIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_BROWSER)
        }

        val httpIntent = Intent(Intent.ACTION_VIEW, "http://www.example.com".toUri())
        val httpsIntent = Intent(Intent.ACTION_VIEW, "https://www.example.com".toUri())

        val flags =
            PackageManager.MATCH_ALL

        listOf(browserCategoryIntent, httpIntent, httpsIntent).forEach { intent ->
            pm.queryIntentActivities(intent, flags).forEach { resolveInfo ->
                val packageName = resolveInfo.activityInfo.packageName

                if (packageName != context.packageName && resolvedPackages.add(packageName)) {
                    results.add(resolveInfo)
                }
            }
        }

        return results
    }

    /** Whether Déchaîner has set a specific Private DNS provider for the whole device. */
    private fun isPrivateDnsPinned(): Boolean = try {
        DeviceOwnerRepository.getPrivateDNS() != null
    } catch (_: Exception) {
        false
    }

    // [installed] no longer changes anything: SafeSearch is now sent on every update. Kept so
    // existing callers stay source-compatible.
    @Suppress("UNUSED_PARAMETER")
    fun applyRestrictions(installed: Boolean = false) {
        val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("blocked_lists_json", null)
        // Sites blocked only while a schedule window is open are merged into the permanent lists.
        val scheduledSites = ScheduleEnforcer.getActiveScheduledSites(context)
        val dnsPinned = isPrivateDnsPinned()
        // Nothing configured and nothing previously pushed: leave browsers untouched. When a DNS
        // filter was pinned before and isn't now, fall through once so the lock is lifted.
        if (json == null && scheduledSites.isEmpty() && !dnsPinned &&
            !prefs.getBoolean(KEY_SCHEDULED_APPLIED, false) &&
            !prefs.getBoolean(KEY_DOH_LOCKED, false)
        ) return

        val allSites = mutableSetOf<String>()
        if (json != null) try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val sitesArray = array.getJSONObject(i).getJSONArray("sites")
                for (j in 0 until sitesArray.length()) {
                    allSites.add(sitesArray.getString(j))
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        allSites.addAll(scheduledSites)
        prefs.edit {
            putBoolean(KEY_SCHEDULED_APPLIED, scheduledSites.isNotEmpty())
            putBoolean(KEY_DOH_LOCKED, dnsPinned)
        }

        val urlRestrictions = Bundle().apply {
            putStringArray("URLBlocklist", allSites.toTypedArray())

            // Every time, not only when a browser is first installed. setApplicationRestrictions
            // replaces the browser's whole policy bundle, so leaving SafeSearch out of any later
            // update — a blocklist edit, a schedule window opening — quietly switched it back off.
            putBoolean("ForceGoogleSafeSearch", true)

            // With a filtering DNS pinned, the browser's own "secure DNS" would send lookups
            // straight to a provider of its choosing and around the filter entirely. "off" makes
            // it use the phone's DNS, which is the filtered one.
            if (dnsPinned) putString("DnsOverHttpsMode", "off")
        }

        getPossibleBrowsers().forEach { info ->
            AppRepository.setApplicationRestrictions(info.activityInfo.packageName, urlRestrictions)
        }
    }
}
