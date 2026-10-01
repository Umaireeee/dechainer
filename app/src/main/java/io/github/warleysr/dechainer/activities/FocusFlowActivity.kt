package io.github.warleysr.dechainer.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.warleysr.dechainer.focus.FocusRunner
import io.github.warleysr.dechainer.screens.focus.FocusFlowPanel
import io.github.warleysr.dechainer.screens.focus.flowAsksNow
import io.github.warleysr.dechainer.ui.theme.DechainerTheme

/**
 * The focus flow's question screen, over the lock screen too (blueprint 6.3): the prompt before a
 * scheduled session, the check-in after a focus phase, the reset, and the last question. It shows
 * whatever the flow is waiting for and closes itself when there is nothing to show. Nothing here
 * can end a block: it only answers the questions the block asks.
 */
class FocusFlowActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        FocusRunner.ensureLoaded(this)
        setContent {
            DechainerTheme {
                val flow by FocusRunner.flow.collectAsState()
                val current = flow
                val showing = current != null && flowAsksNow(current.stage)
                // Nothing left to ask (answered, answered from the notification, or timed out): close, and the
                // Focus page under it shows the rest of the flow.
                LaunchedEffect(showing) { if (!showing) finish() }
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(
                        modifier = Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center
                    ) {
                        if (current != null && showing) FocusFlowPanel(current)
                    }
                }
            }
        }
    }
}
