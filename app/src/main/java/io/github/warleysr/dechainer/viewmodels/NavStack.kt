package io.github.warleysr.dechainer.viewmodels

/**
 * The screen stack behind the single-Activity routing, with no Android in it. The bottom of the
 * stack is always [home]: choosing a root screen puts it on top of home (so Back from any root goes
 * home, and Home is where the app opens), choosing home empties the stack back to it, and any other
 * screen is pushed on top of where you are. [stack] is handed in so the caller can make it observable.
 */
class NavStack<T>(private val stack: MutableList<T>, private val home: T, private val isRoot: (T) -> Boolean) {
    init {
        if (stack.isEmpty()) stack.add(home)
    }

    val current: T get() = stack.lastOrNull() ?: home

    val atHome: Boolean get() = current == home

    fun navigateTo(screen: T) {
        when {
            screen == home -> { stack.clear(); stack.add(home) }
            isRoot(screen) -> { stack.clear(); stack.add(home); stack.add(screen) }
            // Opening the screen you are already on does not stack it twice.
            current != screen -> stack.add(screen)
        }
    }

    /** Pops one screen. False at home, where there is nowhere further back to go. */
    fun goBack(): Boolean {
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            return true
        }
        return false
    }
}
