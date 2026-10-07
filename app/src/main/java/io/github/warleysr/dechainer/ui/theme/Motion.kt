package io.github.warleysr.dechainer.ui.theme

/**
 * The app's only motion values. Motion here is a signal that something changed, never
 * decoration: no bounce, no confetti, nothing that moves while you're just looking.
 */
object Motion {
    const val SCREEN_MS = 220
    const val SCREEN_OUT_MS = 120
    const val PHASE_MS = 400     // focus ↔ break colour shift
    const val LIST_MS = 250      // a row moving between sections
}
