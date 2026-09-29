package io.github.warleysr.dechainer.screens.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Runs [block] now and then every [periodMillis] — but only while the app is on screen.
 *
 * A plain `while (true) { delay() }` inside a LaunchedEffect keeps waking the phone after you
 * leave the app, for as long as Android keeps the process alive. This one pauses the moment the
 * app goes to the background and picks up (with a fresh read) when you come back.
 */
@Composable
fun RepeatWhileVisible(periodMillis: Long, key: Any? = Unit, block: suspend () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(block)
    LaunchedEffect(owner, key, periodMillis) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                latest()
                delay(periodMillis)
            }
        }
    }
}
