package io.github.warleysr.dechainer.viewmodels

import io.github.warleysr.dechainer.lock.LockEngine
import android.os.UserManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.warleysr.dechainer.DechainerApplication
import io.github.warleysr.dechainer.data.AppRepository
import io.github.warleysr.dechainer.data.LockSafety
import io.github.warleysr.dechainer.data.ScheduleEnforcer
import io.github.warleysr.dechainer.data.ScheduleRepository
import io.github.warleysr.dechainer.models.AppItem
import io.github.warleysr.dechainer.models.BlockSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Backs both the schedule list and the editor. Scoped to the Activity (every screen gets the same
 * instance through `viewModel()`), which is how the list hands the draft over to the editor.
 */
class SchedulesViewModel : ViewModel() {
    private val context = DechainerApplication.getInstance()

    enum class SaveResult { OK, LOCKED, NO_DAYS, NOTHING_TO_BLOCK, NO_FREE_TIME, NEEDS_CONFIRMATION, NOT_SAVED }

    var schedules by mutableStateOf(ScheduleRepository.getSchedules(context))
        private set

    var apps by mutableStateOf<List<AppItem>>(emptyList())
        private set

    var isLoadingApps by mutableStateOf(false)
        private set

    /** Schedule being edited; null when the editor is closed. */
    var draft by mutableStateOf<BlockSchedule?>(null)
        private set

    var isNewDraft by mutableStateOf(true)
        private set

    val protectedPackages: Set<String> by lazy { ScheduleEnforcer.protectedPackages(context) }

    /** Restrictions most useful against compulsive phone use — shown first in the picker. */
    val suggestedRestrictions = listOf(
        UserManager.DISALLOW_INSTALL_APPS,
        UserManager.DISALLOW_UNINSTALL_APPS,
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_CONFIG_VPN,
        UserManager.DISALLOW_BLUETOOTH,
        UserManager.DISALLOW_CONFIG_WIFI
    )

    private val restrictionFieldNames: Map<String, String> = UserManager::class.java.fields
        .filter { field ->
            java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                java.lang.reflect.Modifier.isFinal(field.modifiers) &&
                field.type == String::class.java &&
                field.name.startsWith("DISALLOW_")
        }
        .associate { (it.get(null) as String) to it.name.lowercase() }

    val otherRestrictions: List<String> = restrictionFieldNames.keys
        .filter { it !in suggestedRestrictions }
        .sorted()

    fun resourceNameFor(key: String): String? = restrictionFieldNames[key]

    init {
        loadApps()
    }

    fun refresh() {
        schedules = ScheduleRepository.getSchedules(context)
    }

    fun loadApps() {
        viewModelScope.launch {
            isLoadingApps = true
            apps = withContext(Dispatchers.IO) {
                try { AppRepository.getApps() } catch (_: Exception) { emptyList() }
            }
            isLoadingApps = false
        }
    }

    fun appName(pkg: String): String = apps.firstOrNull { it.packageName == pkg }?.name ?: pkg

    fun isLocked(schedule: BlockSchedule): Boolean = ScheduleRepository.isLockedNow(schedule)

    fun isAnyLocked(): Boolean = schedules.any { ScheduleRepository.isLockedNow(it) }

    // --- Editor ---

    fun startNew(defaultName: String) {
        draft = BlockSchedule(id = UUID.randomUUID().toString(), name = defaultName)
        isNewDraft = true
    }

    /** Opens the editor pre-filled from [preset]. Nothing is saved until the person taps save. */
    fun startPreset(preset: io.github.warleysr.dechainer.models.SchedulePreset, name: String) {
        draft = preset.toDraft(UUID.randomUUID().toString(), name)
        isNewDraft = true
    }

    /**
     * A new schedule with everything [schedule] has — apps, services, websites, days and times —
     * to adjust and save. It starts unlocked; lock it again if you want.
     */
    fun startDuplicate(schedule: BlockSchedule, suffix: String) {
        draft = schedule.copy(
            id = UUID.randomUUID().toString(),
            name = "${schedule.name} $suffix".trim(),
            enabled = true,
            lockWhileActive = false
        )
        isNewDraft = true
    }

    /** Adds [source]'s apps, services and websites to the draft. Nothing is removed. */
    fun copyContentsFrom(source: BlockSchedule) = updateDraft {
        it.copy(
            packages = it.packages + source.packages,
            restrictions = it.restrictions + source.restrictions,
            websites = it.websites + source.websites
        )
    }

    fun startEditing(schedule: BlockSchedule) {
        draft = schedule
        isNewDraft = false
    }

    fun updateDraft(transform: (BlockSchedule) -> BlockSchedule) {
        draft = draft?.let(transform)
    }

    fun toggleDraftPackage(pkg: String) = updateDraft {
        it.copy(packages = if (pkg in it.packages) it.packages - pkg else it.packages + pkg)
    }

    fun toggleDraftRestriction(key: String) = updateDraft {
        it.copy(restrictions = if (key in it.restrictions) it.restrictions - key else it.restrictions + key)
    }

    /**
     * @param confirmedLongLock the user already accepted the warning for a long locked window.
     */
    fun saveDraft(confirmedLongLock: Boolean = false): SaveResult {
        val current = draft ?: return SaveResult.OK
        // Re-read the stored copy: the window may have opened while the editor was on screen.
        val stored = ScheduleRepository.getSchedule(context, current.id)
        if (stored != null && ScheduleRepository.isLockedNow(stored)) return SaveResult.LOCKED
        if (current.days.isEmpty()) return SaveResult.NO_DAYS
        // A study-mode schedule always blocks something (every app not allowed), even with an
        // empty list — that's "phone and essentials only".
        if (!current.allowOnly && current.packages.isEmpty() && current.restrictions.isEmpty() && current.websites.isEmpty())
            return SaveResult.NOTHING_TO_BLOCK

        if (current.enabled && current.lockWhileActive) {
            val others = ScheduleRepository.getSchedules(context).filter { it.id != current.id }
            if (!LockSafety.leavesEnoughFreeTime(others + current)) return SaveResult.NO_FREE_TIME
            // "Got longer" means: newly locking, a longer window, or days ADDED. The old test
            // asked whether the NEW day set contains the OLD one, which is true exactly when days
            // were removed - so shrinking 7 days to 1 warned you, and growing 1 day to 7 did not.
            val addsDays = !(stored?.days ?: emptySet()).containsAll(current.days)
            val lockGotLonger = stored == null || !stored.lockWhileActive ||
                current.windowMinutes > stored.windowMinutes || addsDays
            if (!confirmedLongLock && lockGotLonger && current.windowMinutes > LockSafety.LONG_LOCK_MINUTES)
                return SaveResult.NEEDS_CONFIRMATION
        }

        // Storage refused the write (unreadable file, full disk): keep the editor open and say so.
        if (!ScheduleRepository.upsert(context, current.copy(name = current.name.trim()))) return SaveResult.NOT_SAVED
        draft = null
        applyAndRefresh()
        return SaveResult.OK
    }

    fun deleteDraft(): SaveResult {
        val current = draft ?: return SaveResult.OK
        val stored = ScheduleRepository.getSchedule(context, current.id)
        if (stored != null && ScheduleRepository.isLockedNow(stored)) return SaveResult.LOCKED
        if (!ScheduleRepository.delete(context, current.id)) return SaveResult.NOT_SAVED
        draft = null
        applyAndRefresh()
        return SaveResult.OK
    }

    fun closeEditor() {
        draft = null
    }

    // --- List actions ---

    fun setEnabled(schedule: BlockSchedule, enabled: Boolean): SaveResult {
        val stored = ScheduleRepository.getSchedule(context, schedule.id) ?: return SaveResult.OK
        if (!enabled && ScheduleRepository.isLockedNow(stored)) return SaveResult.LOCKED
        if (enabled && stored.lockWhileActive) {
            val others = ScheduleRepository.getSchedules(context).filter { it.id != stored.id }
            if (!LockSafety.leavesEnoughFreeTime(others + stored.copy(enabled = true))) return SaveResult.NO_FREE_TIME
        }
        if (!ScheduleRepository.upsert(context, stored.copy(enabled = enabled))) return SaveResult.NOT_SAVED
        applyAndRefresh()
        return SaveResult.OK
    }

    private fun applyAndRefresh() {
        refresh()
        viewModelScope.launch(Dispatchers.IO) { LockEngine.sync(context) }
    }
}
