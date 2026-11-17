@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.leximo.ecuvue.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.*
import app.leximo.ecuvue.core.*
import androidx.compose.ui.text.font.FontWeight

@Composable internal fun LivePage(s: SessionState, scenario: (Boolean) -> Unit, reset: () -> Unit) {
    if (!s.connected) return
    val w = LocalWords.current
    CapabilitiesPanel(s.capabilities)
    if (s.simulated) Panel {
        Text(w("simulatorControls"), fontWeight = FontWeight.Bold)
        Text(w("scenarioHint"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { scenario(false) }, enabled = !s.busy) { Text(w("idle")) }
            OutlinedButton(onClick = { scenario(true) }, enabled = !s.busy) { Text(w("driving")) }
            TextButton(onClick = reset, enabled = !s.busy) { Text(w("restartSimulator")) }
        }
    }
    Metrics(s.live, s.capabilities, s.history, true)
    Text(w("sampleWindow"), style = MaterialTheme.typography.bodySmall)
}
