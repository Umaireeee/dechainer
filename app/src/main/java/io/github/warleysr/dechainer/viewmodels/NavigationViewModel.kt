package io.github.warleysr.dechainer.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** Screen stack behind [io.github.warleysr.dechainer.activities.MainActivity]'s single-Activity routing. */
class NavigationViewModel : ViewModel() {
    private val stack = mutableStateListOf("apps")

    /** Set by other screens to have the Schedule tab open an app's or group's editor ("app:pkg" / "group:id"). */
    var pendingScheduleKey by mutableStateOf<String?>(null)

    fun selectedTab() = stack.lastOrNull() ?: "apps"

    fun navigateTo(screen: String) {
        if (screen in listOf("apps", "schedule", "config", "suspension_logs")) {
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
