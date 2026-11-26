package io.github.warleysr.dechainer.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.warleysr.dechainer.DechainerApplication
import io.github.warleysr.dechainer.data.AppRepository
import io.github.warleysr.dechainer.models.AppItem
import io.github.warleysr.dechainer.security.SecurityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class ImpulseLockViewModel : ViewModel() {
    private val context = DechainerApplication.getInstance()

    var tetrisUnlock by mutableStateOf(SecurityManager.isTetrisUnlockEnabled(context))
        private set

    var tetrisMinutes by mutableIntStateOf(SecurityManager.getTetrisMinutes(context))
        private set

    var action by mutableStateOf(SecurityManager.getImpulseAction(context))
        private set

    var durationMinutes by mutableIntStateOf(SecurityManager.getImpulseDurationMinutes(context))
        private set

    var suspendedApps by mutableStateOf(SecurityManager.getImpulseSuspendedApps(context))
        private set

    var apps by mutableStateOf<List<AppItem>>(emptyList())
        private set

    var isLoadingApps by mutableStateOf(false)
        private set

    init {
        loadApps()
    }

    fun refreshFromPrefs() {
        tetrisUnlock = SecurityManager.isTetrisUnlockEnabled(context)
        tetrisMinutes = SecurityManager.getTetrisMinutes(context)
        action = SecurityManager.getImpulseAction(context)
        durationMinutes = SecurityManager.getImpulseDurationMinutes(context)
        suspendedApps = SecurityManager.getImpulseSuspendedApps(context)
    }

    fun updateTetrisUnlock(enabled: Boolean) {
        tetrisUnlock = enabled
        SecurityManager.setTetrisUnlockEnabled(context, enabled)
    }

    fun updateTetrisMinutes(value: Int) {
        val clamped = value.coerceIn(SecurityManager.TETRIS_MIN_MINUTES, SecurityManager.TETRIS_MAX_MINUTES)
        tetrisMinutes = clamped
        SecurityManager.setTetrisMinutes(context, clamped)
    }

    fun updateAction(value: SecurityManager.ImpulseAction) {
        action = value
        SecurityManager.setImpulseAction(context, value)
    }

    fun updateDurationMinutes(value: Int) {
        val clamped = value.coerceIn(
            SecurityManager.IMPULSE_MIN_DURATION_MINUTES,
            SecurityManager.IMPULSE_MAX_DURATION_MINUTES
        )
        durationMinutes = clamped
        SecurityManager.setImpulseDurationMinutes(context, clamped)
    }

    fun toggleAppSelection(packageName: String) {
        val newSet = suspendedApps.toMutableSet()
        if (!newSet.remove(packageName)) newSet.add(packageName)
        suspendedApps = newSet
        SecurityManager.setImpulseSuspendedApps(context, newSet)
    }

    fun loadApps() {
        viewModelScope.launch {
            isLoadingApps = true
            try {
                apps = withContext(Dispatchers.IO) { AppRepository.getApps() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to load apps")
            } finally {
                isLoadingApps = false
            }
        }
    }
}
