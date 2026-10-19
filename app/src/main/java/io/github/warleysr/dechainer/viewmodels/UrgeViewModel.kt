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
import java.util.concurrent.atomic.AtomicReference

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

    /** A start asked for while another was in flight; run once that one ends. */
    private val pendingStart = AtomicReference<UrgeSource?>(null)

    /** True from the moment a start is accepted until its entry is on screen, so two taps never make two starts. */
    private val starting = AtomicBoolean(false)

    /** Just after a finish, an urge lock that is still winding down is not adopted again. */
    private var suppressAdoptUntil = 0L

    /** The Urge button, the tile, the shortcut and the locked screen all come here. */
    fun startUrge(source: UrgeSource) {
        // A tap that lands while a start or a resume is in flight is kept and run when that one ends, not dropped.
        if (!starting.compareAndSet(false, true)) { pendingStart.set(source); return }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entry = flow.startUrge(source)
                _state.value = UrgeUiState(entry = entry)
            } catch (e: Exception) {
                Timber.e(e, "Urge start failed")
            } finally {
                starting.set(false)
                pendingStart.getAndSet(null)?.let { startUrge(it) }
            }
        }
    }

    /**
     * A blackout is running but this flow has no entry for it (the app was reopened, say): every
     * one gets its counted entry, and the countdown runs for what is left of it.
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
                pendingStart.getAndSet(null)?.let { startUrge(it) }
            }
        }
    }

    /** Leaves the flow. The lock ends by itself at its end time. */
    fun finish() {
        suppressAdoptUntil = SystemClock.elapsedRealtime() + 10_000L
        _state.value = UrgeUiState()
    }
}
