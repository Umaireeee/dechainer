package io.github.warleysr.dechainer.screens.urge

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.Rules
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.screens.common.RepeatWhileVisible
import io.github.warleysr.dechainer.urge.BlackoutTimer
import io.github.warleysr.dechainer.urge.UrgeFlowRules
import io.github.warleysr.dechainer.urge.UrgeScreen
import io.github.warleysr.dechainer.viewmodels.UrgeViewModel
import kotlinx.coroutines.delay

/**
 * The blackout flow's screen: a pure black, fullscreen countdown until the lock ends. There is no
 * choice, no breathing, no prompts and no writing. The screen is fixed: no status bar, no navigation
 * bar, minimum window brightness, and the display is kept on, so the panel is nearly dark and only
 * the timer is visible. Which screen shows comes from the stored entry and the time, so the flow
 * opens back into the same step after the app is killed.
 */
@Composable
fun UrgeFlowHost(vm: UrgeViewModel, modifier: Modifier = Modifier) {
    // While the blackout holds the screen: immersive, dim, always on. Restored when it ends.
    BlackoutWindowEffect()

    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(TrustedClock.now(context)) }
    RepeatWhileVisible(1000) { now = TrustedClock.now(context) }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        val entry = state.entry ?: return@Box
        when (UrgeFlowRules.screenFor(entry, now)) {
            UrgeScreen.BLACKOUT -> BlackoutScreen(msLeft = ((entry.lockEndedAt ?: now) - now).coerceAtLeast(0L))
            UrgeScreen.FINISHED -> CompletionScreen(onClose = vm::finish)
        }
    }
}

// ---- the immersive, dim, always-on window ----

@Composable
private fun BlackoutWindowEffect() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())

            val previousBrightness = window.attributes.screenBrightness
            val hadKeepScreenOn = (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = Rules.BLACKOUT_BRIGHTNESS }

            onDispose {
                val restore = window.attributes
                restore.screenBrightness = previousBrightness
                window.attributes = restore
                if (!hadKeepScreenOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var context: Context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

// ---- the pure-black countdown ----

@Composable
private fun BlackoutScreen(msLeft: Long) {
    val text = BlackoutTimer.format(msLeft)
    val description = stringResource(R.string.blackout_timer_desc, text)
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color(0xFF888888),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Light,
            fontSize = 72.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { contentDescription = description }
        )
    }
}

// ---- the quiet close ----

@Composable
private fun CompletionScreen(onClose: () -> Unit) {
    // The blackout is over: come back on your own, but close by itself either way.
    LaunchedEffect(Unit) {
        delay(4_000)
        onClose()
    }
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text(
                stringResource(R.string.urge_complete_title),
                color = Color(0xFF888888),
                fontFamily = FontFamily.Monospace,
                fontSize = 22.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.urge_complete_body),
                color = Color(0xFF555555),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onClose) { Text(stringResource(R.string.urge_complete_close)) }
        }
    }
}
