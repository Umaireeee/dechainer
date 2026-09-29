package io.github.warleysr.dechainer.viewmodels

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel

/** Screen stack behind [io.github.warleysr.dechainer.activities.MainActivity]'s single-Activity routing. */
class NavigationViewModel : ViewModel() {
    companion object {
        val ROOTS = listOf("focus", "apps", "schedules", "config")
    }

    // Opens on Focus: the timer is what you come here for.
    private val stack = mutableStateListOf("focus")

    fun selectedTab() = stack.lastOrNull() ?: "focus"

    fun navigateTo(screen: String) {
        if (screen in ROOTS) {
            stack.clear()
        }
        stack.add(screen)
    }

    fun goBack(): Boolean {
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            return true
        }
        return false
    }
}
