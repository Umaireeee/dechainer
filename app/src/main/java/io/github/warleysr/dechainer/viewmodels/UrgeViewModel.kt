package io.github.warleysr.dechainer.viewmodels

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.warleysr.dechainer.urge.UrgeEntry
import io.github.warleysr.dechainer.urge.UrgeFlow
import io.github.warleysr.dechainer.urge.UrgeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

data class UrgeUiState(val entry: UrgeEntry? = null) {
    /** The flow owns the screen while this is true. */
    val active: Boolean get() = entry != null
}

/**
 * Drives the blackout flow for the single Activity. The lock starts the moment the owner
 * asks for it, and the pure-black countdown runs for the chosen length. There is nothing else:
 * no choice, no note, no questions, no deep dive.
 */
class UrgeViewModel(app: Application) : AndroidViewModel(app) {
    private val flow = UrgeFlow(app)
    private val _state = MutableStateFlow(UrgeUiState())
    val state: StateFlow<UrgeUiState> = _state.asStateFlow()

    /** True from the moment a start is accepted until its entry is on screen, so two taps never make two starts. */
    private val starting = AtomicBoolean(false)

    /** Just after a finish, an urge lock that is still winding down is not adopted again. */
    private var suppressAdoptUntil = 0L

    /** The Urge button, the tile, the shortcut and the locked screen all come here. */
    fun startUrge(source: UrgeSource) {
        if (!starting.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entry = flow.startUrge(source)
                _state.value = UrgeUiState(entry = entry)
            } catch (e: Exception) {
                Timber.e(e, "Urge start failed")
            } finally {
                starting.set(false)
            }
        }
    }

    /**
     * An urge lock is running but this flow has no entry for it (the app was reopened, say): every
     * lock gets its counted entry, and the breathing runs for what is left of it.
     */
    fun adoptRunningLock() {
        val s = _state.value
        if (s.entry != null || starting.get()) return
        if (SystemClock.elapsedRealtime() < suppressAdoptUntil) return
        startUrge(UrgeSource.HOME)
    }

    /** Opens back into a running lock, if there is one; called when the app comes to the front. */
    fun resumeIfAny() {
        val s = _state.value
        if (s.entry != null || !starting.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entry = flow.resumable() ?: return@launch
                _state.value = UrgeUiState(entry = entry)
            } finally {
                starting.set(false)
            }
        }
    }

    /** Leaves the flow. The lock ends by itself at its end time. */
    fun finish() {
        suppressAdoptUntil = SystemClock.elapsedRealtime() + 10_000L
        _state.value = UrgeUiState()
    }
}
