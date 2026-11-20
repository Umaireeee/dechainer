package io.github.warleysr.dechainer.screens.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import io.github.warleysr.dechainer.models.Span
import io.github.warleysr.dechainer.models.TimeLimit
import io.github.warleysr.dechainer.models.TimeWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.DayOfWeek

/** A new restriction only takes effect after a few seconds in which the user can still undo it. */
object PendingChange {
    const val UNDO_WINDOW_MS = 5000L

    /** Hosted by the main Scaffold. */
    val snackbarHost = SnackbarHostState()

    // App-wide scope: the screen that started the change may be gone before the delay ends.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeJob: Job? = null
    private var activeAction: (() -> Unit)? = null

    fun schedule(message: String, undoLabel: String, onUndo: (() -> Unit)? = null, action: () -> Unit) {
        // Commit any in-flight restriction immediately before replacing it so rapid calls don't
        // race on SnackbarHostState's mutex or drop an undo window.
        activeJob?.cancel()
        val displaced = activeAction
        activeJob = null
        activeAction = null
        snackbarHost.currentSnackbarData?.dismiss()
        displaced?.invoke()

        activeAction = action
        activeJob = scope.launch {
            val result = withTimeoutOrNull(UNDO_WINDOW_MS) {
                snackbarHost.showSnackbar(message, undoLabel, duration = SnackbarDuration.Indefinite)
            }
            if (activeAction === action) {
                activeAction = null
                activeJob = null
                if (result == SnackbarResult.ActionPerformed) onUndo?.invoke() else action()
            }
        }
    }
}

// 0 means "no limit".
private fun cap(minutes: Int) = if (minutes == 0) Int.MAX_VALUE else minutes

fun isLooserLimit(old: TimeLimit, new: TimeLimit) =
    DayOfWeek.entries.any { cap(new.minutesFor(it)) > cap(old.minutesFor(it)) }

private val FULL_DAY = listOf(Span(0, 1440))

private fun spansCoveredBy(newSpans: List<Span>, oldSpans: List<Span>): Boolean =
    newSpans.all { ns -> oldSpans.any { os -> os.start <= ns.start && ns.end <= os.end } }

/** Windows are the *allowed* periods and an empty list means always allowed: loosening is allowing a minute of a day that wasn't. */
fun isLooserWindows(old: List<TimeWindow>, new: List<TimeWindow>): Boolean =
    (1..7).any { day ->
        val oldSpans = TimeWindow.allowedSpans(listOf(old), day) ?: FULL_DAY
        val newSpans = TimeWindow.allowedSpans(listOf(new), day) ?: FULL_DAY
        !spansCoveredBy(newSpans, oldSpans)
    }

private fun isPermissiveKey(key: String): Boolean {
    val lower = key.lowercase()
    return lower.startsWith("allow") || "allowlist" in lower || "whitelist" in lower || "exception" in lower
}

@Suppress("DEPRECATION")
fun isLooserRestrictions(current: android.os.Bundle, new: android.os.Bundle): Boolean {
    val currentKeys = current.keySet()
    for (key in new.keySet()) {
        if (key !in currentKeys && isPermissiveKey(key)) return true
    }
    for (key in currentKeys) {
        val oldVal = current.get(key)
        val newVal = if (new.containsKey(key)) new.get(key) else null
        when (oldVal) {
            is Boolean -> {
                if (isPermissiveKey(key)) {
                    if (!oldVal && newVal == true) return true
                } else {
                    if (oldVal && newVal != true) return true
                }
            }
            is String -> {
                if (oldVal.isNotEmpty() && (newVal as? String) != oldVal) return true
            }
            is Number -> {
                if (oldVal.toLong() != (newVal as? Number)?.toLong()) return true
            }
            is Array<*> -> {
                val oldSet = oldVal.toSet()
                val newSet = (newVal as? Array<*>)?.toSet() ?: emptySet()
                if (isPermissiveKey(key)) {
                    if (!oldSet.containsAll(newSet)) return true
                } else {
                    if (!newSet.containsAll(oldSet)) return true
                }
            }
            is android.os.Bundle -> {
                if (newVal !is android.os.Bundle || isLooserRestrictions(oldVal, newVal)) return true
            }
        }
    }
    return false
}

fun isLooserSet(old: Set<String>, new: Set<String>) = !new.containsAll(old)
