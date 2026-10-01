package io.github.warleysr.dechainer.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** Screen stack behind [io.github.warleysr.dechainer.activities.MainActivity]'s single-Activity routing. Opens on Home. */
class NavigationViewModel : ViewModel() {
    private val nav = NavStack(mutableStateListOf(Route.HOME), Route.HOME) { it.isRoot }

    /** Which urge or slip [Route.ENTRY] shows. */
    var entryId by mutableLongStateOf(-1L)
        private set

    fun openEntry(id: Long) {
        entryId = id
        nav.navigateTo(Route.ENTRY)
    }

    fun current(): Route = nav.current

    fun navigateTo(route: Route) = nav.navigateTo(route)

    fun goBack(): Boolean = nav.goBack()
}
