package io.github.warleysr.dechainer.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.warleysr.dechainer.DechainerApplication
import io.github.warleysr.dechainer.data.AppRepository
import io.github.warleysr.dechainer.data.ScheduleEnforcer
import io.github.warleysr.dechainer.data.TimeLimits
import io.github.warleysr.dechainer.models.AppItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Apps tab: every app, with one switch to suspend it. */
class AppsViewModel : ViewModel() {
    private val context = DechainerApplication.getInstance()

    var apps by mutableStateOf<List<AppItem>>(emptyList())
        private set
    var isLoading by mutableStateOf(true)
        private set
    var query by mutableStateOf("")
    var showSystem by mutableStateOf(false)

    // Launchers, dialers, keyboards and Déchaîner itself: suspending them would break the phone.
    private val protectedPackages: Set<String> by lazy { ScheduleEnforcer.protectedPackages(context) }

    // Daily limits: minutes allowed, minutes used today, and whether Android lets us read usage.
    var limits by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    var usedMinutes by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    var usageAccess by mutableStateOf(true)
        private set

    init {
        load()
        refreshLimits()
    }

    /** Re-reads the limits and today's usage (off the main thread). Called on open and on return from Settings. */
    fun refreshLimits() {
        viewModelScope.launch {
            val (l, u, access) = withContext(Dispatchers.IO) {
                val l = TimeLimits.all(context)
                val access = TimeLimits.hasUsageAccess(context)
                val u = if (l.isNotEmpty() && access)
                    TimeLimits.usedToday(context, l.keys).mapValues { (it.value / 60_000L).toInt() }
                else emptyMap()
                Triple(l, u, access)
            }
            limits = l
            usedMinutes = u
            usageAccess = access
            // Access may just have been granted: have the engine look.
            if (l.isNotEmpty() && access) ScheduleEnforcer.requestSyncAll(context)
        }
    }

    fun setLimit(pkg: String, minutes: Int) {
        TimeLimits.set(context, pkg, minutes)
        limits = if (minutes <= 0) limits - pkg else limits + (pkg to minutes)
        refreshLimits()
    }

    fun load(force: Boolean = false) {
        viewModelScope.launch {
            isLoading = apps.isEmpty()
            apps = withContext(Dispatchers.IO) {
                AppRepository.getApps(forceRefresh = force).filter { it.packageName !in protectedPackages }
            }
            isLoading = false
        }
    }

    val visible: List<AppItem>
        get() = apps.filter {
            (showSystem || !it.isSystem || it.isSuspended) &&
                (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true))
        }.sortedWith(compareByDescending<AppItem> { it.isSuspended }.thenBy { it.name.lowercase() })

    /** The schedule or impulse lock holding [pkg] right now, if any. Those can't be lifted by hand. */
    // Fresh, not cached: a focus lock starts and ends many times a day, and a stale answer here
    // would let a tap lift an app that a schedule or session still holds. A fresh answer can mean
    // a full sync, so it's only ever asked off the main thread (see requestUnsuspend).
    private fun heldBy(pkg: String): String? = ScheduleEnforcer.activeBlockFor(context, pkg, fresh = true)?.scheduleName

    /**
     * Checks in the background whether [pkg] is held; calls [onHeld] with the holder's name, or
     * [onFree] if it can be lifted. Both on the main thread.
     */
    fun requestUnsuspend(pkg: String, onHeld: (String) -> Unit, onFree: () -> Unit) {
        viewModelScope.launch {
            val holder = withContext(Dispatchers.IO) { heldBy(pkg) }
            if (holder != null) onHeld(holder) else onFree()
        }
    }

    fun setSuspended(pkg: String, suspended: Boolean) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                // Last check at the moment of lifting it, whatever the screen showed a moment ago.
                if (!suspended && heldBy(pkg) != null) return@withContext false
                try {
                    AppRepository.setAppSuspended(pkg, suspended)
                    // Suspended by hand: it's yours now, so no schedule lifts it when its window ends.
                    if (suspended) ScheduleEnforcer.disown(context, pkg)
                    true
                } catch (_: Exception) {
                    false
                }
            }
            if (ok) apps = apps.map { if (it.packageName == pkg) it.copy(isSuspended = suspended) else it }
        }
    }
}
