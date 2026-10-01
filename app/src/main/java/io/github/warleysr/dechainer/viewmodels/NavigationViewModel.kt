package io.github.warleysr.dechainer.viewmodels

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel

/** Screen stack behind [io.github.warleysr.dechainer.activities.MainActivity]'s single-Activity routing. Opens on Home. */
class NavigationViewModel : ViewModel() {
    private val nav = NavStack(mutableStateListOf(Route.HOME), Route.HOME) { it.isRoot }

    fun current(): Route = nav.current

    fun navigateTo(route: Route) = nav.navigateTo(route)

    fun goBack(): Boolean = nav.goBack()
}
