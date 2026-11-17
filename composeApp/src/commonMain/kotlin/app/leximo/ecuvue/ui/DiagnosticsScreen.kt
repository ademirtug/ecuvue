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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.testTag

@Composable internal fun Diagnostics(s: SessionState, reference: CodeReference?, scan: () -> Unit, clear: () -> Unit, open: (String) -> Unit) {
    if (!s.connected) return
    val w = LocalWords.current
    val report = s.report
    var filter by remember { mutableStateOf<FaultKind?>(null) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.Troubleshoot, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(w("genericObd"), style = MaterialTheme.typography.titleLarge)
                Text(if (report == null) w("scanPrompt") else "${w("lastScan")}: ${instant(report.scannedAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = scan, enabled = !s.busy, modifier = Modifier.testTag("scan-faults")) {
                Icon(Icons.Outlined.Radar, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text(w(if (report == null) "scanFaults" else "scanAgain"))
            }
            OutlinedButton(onClick = clear, enabled = !s.busy && report?.faults?.any { it.kind != FaultKind.PERMANENT } == true, modifier = Modifier.testTag("clear-faults")) {
                Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text(w("clearCodes"))
            }
        }
    }
    if (report != null) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FaultKind.entries.forEach { kind ->
                Surface(Modifier.weight(1f).fillMaxHeight().testTag("fault-count-${kind.name.lowercase()}"), shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(report.faults.count { it.kind == kind }.toString(), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                        Text(w(kind.name.lowercase()), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(filter == null, { filter = null }, { Text(w("all")) })
            FaultKind.entries.forEach { kind -> FilterChip(filter == kind, { filter = kind }, { Text(w(kind.name.lowercase())) }) }
        }
    }
    Panel {
        if (report == null) Text(w("noScan"), style = MaterialTheme.typography.titleLarge)
        else {
            val faults = report.faults.filter { filter == null || it.kind == filter }
            if (faults.isEmpty()) {
                Text(w(if (report.faults.isEmpty()) "noFaults" else "noFilterResults"))
                if (report.faults.isEmpty()) Text(w("noFaultsDescription"), style = MaterialTheme.typography.bodySmall)
            }
            faults.forEachIndexed { index, fault ->
                if (index > 0) HorizontalDivider()
                FaultRow(fault.code, fault.kind, reference, open)
            }
            if (report.pendingStatus != Support.SUPPORTED || report.permanentStatus != Support.SUPPORTED) Text(w("unsupportedModes"), style = MaterialTheme.typography.bodySmall)
        }
    }
    report?.let { FreezeFramePanel(it.freezeFrame) }
}
