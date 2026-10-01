package io.github.warleysr.dechainer.screens.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.screens.common.RecoveryGenerateDialog
import io.github.warleysr.dechainer.security.SecurityManager

@Composable
fun SetupRecovery(paddingValues: PaddingValues) {
    var showGenerateDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Column(
        // Scrolls: at a large font size the first screen of the app must not clip its own button.
        modifier = Modifier.padding(paddingValues).fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (showGenerateDialog) {
            RecoveryGenerateDialog(
                onDismiss = { showGenerateDialog = false },
                onConfirm = { key ->
                    SecurityManager.saveRecoveryCode(context, key)
                    showGenerateDialog = false
                }
            )
        }
        Text(
            stringResource(R.string.attention),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.self_lock_risk),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { showGenerateDialog = true }) {
            Text(stringResource(R.string.generate_key))
        }
    }
}