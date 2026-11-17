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

@Composable internal fun FaultRow(code: String, kind: FaultKind?, reference: CodeReference?, open: (String) -> Unit) {
    val w = LocalWords.current
    val match = reference?.lookup(code, w.language)
    Row(Modifier.fillMaxWidth().clickable { open(code) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column { CodeText(code); kind?.let { Text(w(it.name.lowercase()), style = MaterialTheme.typography.labelSmall) } }
        Text(match?.description?.title ?: if (reference == null) w("catalogNotReady", "Code explanations are not available yet.") else w("unknownTitle"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Icon(Icons.Outlined.ChevronRight, w("faultDetails"))
    }
}

@Composable internal fun ReferenceSearch(catalog: CatalogState, query: String, updateQuery: (String) -> Unit, open: (String) -> Unit, retry: () -> Unit) {
    val w = LocalWords.current
    val reference = (catalog as? CatalogState.Ready)?.reference
    val matches = remember(reference, query, w.language) { reference?.search(query, w.language).orEmpty() }
    Panel(Modifier.testTag("offline-reference")) {
        Text(w("offlineReference"), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(query, { updateQuery(it.take(120)) }, Modifier.fillMaxWidth().testTag("reference-query"), label = { Text(w("searchCodes")) }, singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, enabled = reference != null)
        when (catalog) {
            CatalogState.Loading -> {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("catalog-loading"))
                Text(w("catalogLoading", "Loading code catalog… You can keep using the app."))
            }
            is CatalogState.Failed -> {
                Text(w("catalogLoadFailed", "Could not load the code catalog. Other features are still available."), Modifier.testTag("catalog-error"))
                TextButton(onClick = retry, modifier = Modifier.testTag("catalog-retry")) { Text(w("retry", "Retry")) }
            }
            is CatalogState.Ready -> if (query.isNotBlank() && matches.isEmpty()) Text(w("noReferenceMatches"))
        }
        matches.forEach { FaultRow(it.code, null, reference, open) }
        Text(w("referenceScope"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun FreezeFramePanel(frame: FreezeFrame?) {
    val w = LocalWords.current
    Panel {
        Text(w("freezeFrame"), style = MaterialTheme.typography.titleLarge)
        Text(w(if (frame == null) "noFreezeFrameHint" else "freezeFrameHint"), style = MaterialTheme.typography.bodySmall)
        if (frame != null) {
            CodeText("${w("recordedFor")}: ${frame.code} · ${w("respondingEcu")}: ${frame.sourceEcu}")
            metrics.filter { it.pid != null }.forEach { Text("${w(it.key)}: ${number(it.value(frame.data))} ${it.unit}") }
        }
    }
}

@Composable internal fun FaultDetails(code: String, s: SessionState, reference: CodeReference?) {
    val w = LocalWords.current
    val match = reference?.lookup(code, w.language)
    Panel {
        CodeText(code)
        Text(match?.description?.title ?: w("unknownTitle"), style = MaterialTheme.typography.headlineSmall)
        s.report?.faults?.filter { it.code == code }?.forEach { Text(w(it.kind.name.lowercase())) }
        if (reference == null) {
            Text(w("catalogNotReady", "Code explanations are not available yet."))
        } else {
            if (match?.isFallback == true) Text(w("referenceEnglishFallback"), style = MaterialTheme.typography.bodySmall)
            Text(w("meaning"), fontWeight = FontWeight.Bold); Text(match?.description?.meaning ?: w("unknownMeaning"))
            Text(w("possibleCauses"), fontWeight = FontWeight.Bold)
            (match?.description?.causes ?: listOf(w("unknownCauses"))).forEach { Text("• $it") }
            Text(w("nextSteps"), fontWeight = FontWeight.Bold); Text(match?.description?.next ?: w("unknownNext"))
            HorizontalDivider(); Text(w("codeDisclaimer"), style = MaterialTheme.typography.bodySmall)
        }
    }
    s.report?.freezeFrame?.takeIf { it.code == code }?.let { FreezeFramePanel(it) }
}
