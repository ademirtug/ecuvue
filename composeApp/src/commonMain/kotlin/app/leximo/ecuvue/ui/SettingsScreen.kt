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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*

@Composable private fun SettingsDropdown(
    label: String, value: String, options: List<Pair<String, String>>, icon: ImageVector,
    tag: String, onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == value }?.second.orEmpty(), onValueChange = {},
            readOnly = true, singleLine = true,
            leadingIcon = { Icon(icon, null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .testTag(tag).semantics { contentDescription = label },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            options.forEach { (id, text) ->
                val isSelected = id == value
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { expanded = false; onSelect(id) },
                    trailingIcon = { if (isSelected) Icon(Icons.Outlined.Check, null) },
                    modifier = Modifier.testTag("$tag-option-${id.ifEmpty { "system" }}").semantics { selected = isSelected },
                )
            }
        }
    }
}

@Composable private fun PalettePicker(value: String, dark: Boolean, onSelect: (String) -> Unit) {
    val w = LocalWords.current
    val palettes = listOf("monochrome" to "paletteMonochrome", "iceBlue" to "paletteIceBlue", "racingGreen" to "paletteRacingGreen", "sunset" to "paletteSunset")
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 620.dp) 4 else 2
        val width = (maxWidth - 12.dp * (columns - 1)) / columns
        FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            palettes.forEach { (id, key) ->
                val isSelected = value == id
                val preview = appColors(id, dark)
                Surface(
                    Modifier.width(width).testTag("palette-$id").selectable(isSelected, role = Role.RadioButton, onClick = { onSelect(id) }),
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf(preview.primary, preview.primaryContainer).forEach { color ->
                                Box(Modifier.size(22.dp).background(color, CircleShape).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .2f), CircleShape))
                            }
                            Spacer(Modifier.weight(1f))
                            Icon(if (isSelected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(19.dp),
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(w(key), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable internal fun SettingsPage(
    settings: AppSettings, reference: CodeReference?, android: Boolean, supportsAiTools: Boolean,
    savedAdapter: SavedAdapter?, reconnectApproval: (Boolean) -> Unit, forgetAdapter: () -> Unit,
    update: (AppSettings) -> Unit,
) {
    val w = LocalWords.current
    var credits by remember { mutableStateOf(false) }
    val dark = when (settings.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    Panel {
        Text(w("appearance"), style = MaterialTheme.typography.titleLarge)
        Text(w("themeDescription"))
        SettingsDropdown(w("appearance"), settings.theme, listOf("system", "light", "dark").map { it to w(it) },
            when (settings.theme) { "light" -> Icons.Outlined.LightMode; "dark" -> Icons.Outlined.DarkMode; else -> Icons.Outlined.BrightnessAuto },
            "theme-dropdown") { update(settings.copy(theme = it)) }
        Text(w("colorPalette"), fontWeight = FontWeight.SemiBold)
        PalettePicker(settings.palette, dark) { update(settings.copy(palette = it)) }
    }
    Panel {
        Text(w("language"), style = MaterialTheme.typography.titleLarge)
        Text(w("languageDescription"))
        SettingsDropdown(w("language"), settings.language,
            listOf("" to w("system")) + languages.filterKeys { it.isNotEmpty() }.entries.sortedBy { it.value }.map { it.key to it.value },
            Icons.Outlined.Translate, "language-dropdown") { update(settings.copy(language = it)) }
    }
    if (android) Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.AutoAwesome, null)
            Text(w("aiAccess", "AI assistant access"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            Switch(settings.aiEnabled, { update(settings.copy(aiEnabled = it)) }, enabled = supportsAiTools, modifier = Modifier.testTag("ai-access"))
        }
        Text(w("aiAccessDescription", "Allow assistants to read reports and request fresh readings from your connected adapter. They cannot clear codes or change vehicle settings. Your VIN is not included in tool results."))
        Text(w("aiSavedConnectionHint", "Connect an adapter once in ECU Vue, then approve assistant reconnection below. Android may require opening the app to complete a connection. Saved reports remain available offline. Tool results may be processed by your assistant's service."), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(w("savedAdapterTitle", "Saved adapter"), style = MaterialTheme.typography.titleMedium)
        if (savedAdapter == null) {
            Text(w("noSavedAdapterHint", "Connect a real adapter successfully to save it. The simulator is never saved."), modifier = Modifier.testTag("no-saved-adapter"))
        } else {
            Text(savedAdapter.request.name.ifBlank { savedAdapter.request.kind.name }, modifier = Modifier.testTag("saved-adapter-name"))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(w("allowAssistantReconnect", "Allow AI to reconnect this adapter"), Modifier.weight(1f))
                Switch(savedAdapter.assistantReconnectAllowed, reconnectApproval,
                    enabled = settings.aiEnabled && supportsAiTools && savedAdapter.reconnectSupported,
                    modifier = Modifier.testTag("ai-reconnect"))
            }
            Text(w("adapterApprovalHint", "Approval applies only to this adapter. Choosing a different adapter resets approval. Assistants cannot choose arbitrary devices."), style = MaterialTheme.typography.bodySmall)
            if (!savedAdapter.reconnectSupported) Text(w("usbReconnectHint", "Automatic USB reconnection needs a unique serial number and Android USB permission. Connect this adapter manually when required."), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = forgetAdapter, modifier = Modifier.testTag("forget-saved-adapter")) {
                Text(w("forgetSavedAdapter", "Forget saved adapter"))
            }
        }
        if (!supportsAiTools) Text(w("aiVersionHint", "System assistant tools require Android 16 or later."), style = MaterialTheme.typography.bodySmall)
    }
    Panel {
        Text("ECU Vue · 0.2.0", style = MaterialTheme.typography.titleLarge)
        Text(w("genericObd"))
        if (reference != null) Text("${w("offlineReference")}: ${reference.count} · ${reference.version}", style = MaterialTheme.typography.bodySmall)
        Text(w("codeDisclaimer"), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { credits = true }, enabled = reference != null) { Text(w("credits", "Reference credits")) }
    }
    if (credits && reference != null) AlertDialog(onDismissRequest = { credits = false }, title = { Text(reference.sourceName) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(reference.licenseText); Spacer(Modifier.height(16.dp)); Text(w("referenceScope")) } },
        confirmButton = { TextButton(onClick = { credits = false }) { Text(w("dismiss")) } })
}
