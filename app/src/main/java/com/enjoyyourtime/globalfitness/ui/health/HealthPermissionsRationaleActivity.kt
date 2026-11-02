@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.ui.components.TopBarTitle
import com.enjoyyourtime.globalfitness.ui.profile.PRIVACY_POLICY_URL
import com.enjoyyourtime.globalfitness.ui.theme.GlobalFitnessTheme

/**
 * Privacy policy / permission rationale shown by Health Connect ("Why does it ask for these
 * permissions?") on Android 13 and lower, and via the VIEW_PERMISSION_USAGE alias on Android 14+.
 */
class HealthPermissionsRationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GlobalFitnessTheme {
                RationaleScreen(onClose = ::finish)
            }
        }
    }
}

@Composable
private fun RationaleScreen(onClose: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Scaffold(topBar = { TopAppBar(title = { TopBarTitle(stringResource(R.string.health_rationale_title)) }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Paragraph(stringResource(R.string.health_rationale_intro))
            Paragraph(stringResource(R.string.health_rationale_read))
            Paragraph(stringResource(R.string.health_rationale_write))
            Paragraph(stringResource(R.string.health_rationale_write_workouts))
            Paragraph(stringResource(R.string.health_rationale_write_meals))
            Paragraph(stringResource(R.string.health_rationale_storage))
            Paragraph(stringResource(R.string.health_rationale_revoke))
            OutlinedButton(onClick = { runCatching { uriHandler.openUri(PRIVACY_POLICY_URL) } }) {
                Text(stringResource(R.string.health_rationale_full_policy))
            }
            Button(onClick = onClose) { Text(stringResource(R.string.action_got_it)) }
        }
    }
}

@Composable
private fun Paragraph(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}
