package io.github.warleysr.dechainer.screens.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.day.DayEngine

/**
 * The last step of setup (blueprint 11, step 9 and 6.4): the checklist rules in plain words, and one
 * explicit confirmation. Enforcement starts when it is confirmed, not at a first plan.
 */
@Composable
fun RulesOnboarding(paddingValues: PaddingValues, onConfirmed: () -> Unit) {
    val ctx = LocalContext.current
    var understood by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.padding(paddingValues).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.rules_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.rules_intro), style = MaterialTheme.typography.bodyLarge)
        listOf(
            R.string.rules_plan, R.string.rules_review, R.string.rules_punishment, R.string.rules_rest,
            R.string.rules_first_evening, R.string.rules_loosening
        ).forEach { Text("• " + stringResource(it), style = MaterialTheme.typography.bodyLarge) }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(understood, role = Role.Checkbox) { understood = it },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = understood, onCheckedChange = null)
            Text(stringResource(R.string.rules_understood), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
        }
        Button(
            onClick = { DayEngine.confirmRules(ctx); onConfirmed() },
            enabled = understood,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        ) { Text(stringResource(R.string.rules_confirm)) }
    }
}
