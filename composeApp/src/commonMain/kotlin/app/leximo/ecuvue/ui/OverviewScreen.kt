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
import androidx.compose.ui.platform.testTag

@Composable private fun ConnectionPrompt(connect: () -> Unit) {
    val w = LocalWords.current
    Panel {
        BrandMark(76.dp)
        Heading(w("connectAdapter"), w("connectSubtitle"))
        Button(onClick = connect) { Icon(Icons.Outlined.Cable, null); Spacer(Modifier.width(8.dp)); Text(w("chooseConnection")) }
    }
}

@Composable internal fun Overview(s: SessionState, reference: CodeReference?, connect: () -> Unit, disconnect: () -> Unit, scan: () -> Unit, live: () -> Unit, openFault: (String) -> Unit) {
    val w = LocalWords.current
    val report = s.report
    if (!s.connected) { ConnectionPrompt(connect); return }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BrandMark(64.dp)
            Column(Modifier.weight(1f)) {
                Text(s.vehicle?.displayName ?: w(if (s.simulated) "sampleVehicle" else "obdVehicle"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(s.adapterName, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Outlined.CheckCircle, w("connected"), tint = MaterialTheme.colorScheme.primary)
        }
        s.vehicle?.vin?.let { CodeText("${w("vehicleVin")}: $it") }
        if (s.vehicle == null) Text(w("vehicleIdentityUnavailable"), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = connect) { Text(w("changeConnection")) }
            OutlinedButton(onClick = disconnect, enabled = !s.busy, modifier = Modifier.testTag("disconnect-overview")) {
                Icon(Icons.Outlined.LinkOff, null); Spacer(Modifier.width(8.dp)); Text(w("disconnect"))
            }
        }
    }
    CapabilitiesPanel(s.capabilities)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(w("livePreview"), style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = live) { Text(w("viewLive")) }
    }
    Metrics(s.live, s.capabilities, emptyList(), false)
    Panel {
        Text(w("faultCodes"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (report == null) Text(w("noScan")) else {
            Text(if (report.faults.isEmpty()) w("noFaults") else "${report.faults.size} · ${w("faultsFound")}", style = MaterialTheme.typography.headlineSmall)
            report.faults.take(3).forEach { FaultRow(it.code, it.kind, reference, openFault) }
        }
        Button(onClick = scan, enabled = !s.busy, modifier = Modifier.testTag("scan-faults")) { Text(w(if (s.report == null) "scanFaults" else "scanAgain")) }
    }
}
