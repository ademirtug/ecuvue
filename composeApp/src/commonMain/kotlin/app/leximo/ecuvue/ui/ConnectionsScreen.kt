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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import app.leximo.ecuvue.presentation.ConnectionPresenter
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable internal fun Connections(
    adapterDiscovery: AdapterDiscovery, s: SessionState, run: (suspend () -> Unit) -> Unit,
    connect: (ConnectionRequest) -> Unit, disconnect: () -> Unit,
    openBluetoothSettings: () -> Unit, openWifiSettings: () -> Unit,
) {
    val w = LocalWords.current
    val discoveryScope = rememberCoroutineScope()
    val parameters = remember(adapterDiscovery, discoveryScope) { parametersOf(adapterDiscovery, discoveryScope) }
    val presenter = koinInject<ConnectionPresenter>(parameters = { parameters })
    val discovery by presenter.state.collectAsState()
    var hostAddress by rememberSaveable { mutableStateOf("192.168.0.10") }
    var port by rememberSaveable { mutableStateOf("35000") }
    var baud by rememberSaveable { mutableStateOf(38400) }
    var wifiExpanded by rememberSaveable { mutableStateOf(false) }
    var usbExpanded by rememberSaveable { mutableStateOf(false) }
    val hasBluetooth = ConnectionKind.CLASSIC in adapterDiscovery.connections || ConnectionKind.BLE in adapterDiscovery.connections
    val selectAdapter: (ConnectionRequest) -> Unit = { request -> presenter.stopDiscovery(); connect(request) }
    DisposableEffect(presenter) { presenter.loadPaired(); onDispose { presenter.close() } }
    if (s.connected) OutlinedButton(onClick = disconnect, enabled = !s.busy) { Text(w("disconnect")) }
    Panel(Modifier.testTag("available-adapters")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Bluetooth, null)
            Text(w("adaptersTitle"), style = MaterialTheme.typography.titleLarge)
        }
        if (hasBluetooth) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (discovery.searching) TextButton(onClick = presenter::stopDiscovery, modifier = Modifier.testTag("stop-bluetooth-discovery")) { Text(w("stopScan")) }
                else TextButton(onClick = presenter::findAdapters, enabled = !discovery.pairedLoading && !s.busy, modifier = Modifier.testTag("find-bluetooth-adapters")) { Text(w("findAdapters", "Find adapters")) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = openBluetoothSettings) { Icon(Icons.Outlined.Settings, w("bluetoothSettings")) }
            }
            if (discovery.pairedLoading || discovery.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            discovery.bluetoothError?.let { error -> Message(w(error.key, error.message ?: "Operation failed"), error.details) { presenter.dismissBluetoothError() } }
            if (discovery.adapters.isEmpty() && !discovery.pairedLoading && !discovery.searching && discovery.bluetoothError == null)
                Text(w(if (discovery.searched) "noBluetoothAdapters" else "bluetoothFindHint"))
            discovery.adapters.forEach { device ->
                DeviceRow(device, !s.busy, transportLabel = if (device.kind == ConnectionKind.BLE) "BLE" else w("bluetoothClassic", "Classic"),
                    connectTag = "connect-${device.kind.name.lowercase()}-${device.id}") {
                    selectAdapter(ConnectionRequest(device.kind, device.id, device.name))
                }
            }
        }
        DeviceRow(
            AdapterDevice("simulator", w("simulatedAdapter"), ConnectionKind.SIMULATOR),
            enabled = !s.busy && !(s.connected && s.simulated),
            subtitle = w("simulatedAdapterDescription"), icon = Icons.Outlined.Science,
            connected = s.connected && s.simulated, connectTag = "connect-simulator",
        ) { selectAdapter(ConnectionRequest(ConnectionKind.SIMULATOR)) }
    }
    if (ConnectionKind.WIFI in adapterDiscovery.connections) ExpandableConnectionPanel(
        w("wifiConnection"), Icons.Outlined.Wifi, wifiExpanded, { wifiExpanded = it }, "connection-wifi-toggle",
    ) {
        Text(w("wifiHint"), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(hostAddress, { hostAddress = it.take(253) }, Modifier.fillMaxWidth(), label = { Text(w("wifiAddress")) }, singleLine = true)
        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), label = { Text(w("wifiPort")) }, singleLine = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                val address = hostAddress.trim()
                val parsed = port.toIntOrNull()
                run {
                    if (address.isEmpty() || address.contains(Regex("[\\s/]")) || "://" in address) throw DiagnosticException("invalidWifiAddress")
                    if (parsed == null || parsed !in 1..65535) throw DiagnosticException("invalidWifiPort")
                    selectAdapter(ConnectionRequest(ConnectionKind.WIFI, address, "Wi-Fi · $address:$parsed", parsed))
                }
            }, enabled = !s.busy) { Text(w("connect")) }
            TextButton(onClick = openWifiSettings) { Text(w("wifiSettings")) }
        }
    }
    if (ConnectionKind.USB in adapterDiscovery.connections) ExpandableConnectionPanel(
        w("usbConnection"), Icons.Outlined.Usb, usbExpanded, { usbExpanded = it }, "connection-usb-toggle",
    ) {
        Text(w("usbHint"))
        Text(w("usbSpeed"), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(9600, 38400, 57600, 115200).forEach { value -> FilterChip(baud == value, { baud = value }, { Text(value.toString()) }) }
        }
        Button(onClick = presenter::refreshUsb, enabled = !s.busy && !discovery.usbLoading) { Text(w("findUsb")) }
        if (discovery.usbLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        discovery.usbError?.let { error -> Message(w(error.key, error.message ?: "Operation failed"), error.details) { presenter.dismissUsbError() } }
        discovery.usbDevices.forEach { device -> DeviceRow(device, !s.busy) { selectAdapter(ConnectionRequest(device.kind, device.id, device.name, baud = baud)) } }
    }
}

@Composable private fun ExpandableConnectionPanel(
    title: String, icon: ImageVector, expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
    tag: String, content: @Composable ColumnScope.() -> Unit,
) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)
                .toggleable(expanded, role = Role.Button, onValueChange = onExpandedChange)
                .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null)
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (expanded) Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}

@Composable private fun DeviceRow(
    device: AdapterDevice, enabled: Boolean, subtitle: String = device.id,
    icon: ImageVector = Icons.Outlined.Bluetooth, connected: Boolean = false,
    connectTag: String = "connect-${device.id}", transportLabel: String? = null, connect: () -> Unit,
) {
    val w = LocalWords.current
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = connect).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(device.name, Modifier.weight(1f, fill = false), fontWeight = FontWeight.SemiBold)
                transportLabel?.let { label -> Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(label, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                } }
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        if (connected) Icon(Icons.Outlined.CheckCircle, w("connected"), tint = MaterialTheme.colorScheme.primary)
        else IconButton(onClick = connect, enabled = enabled, modifier = Modifier.testTag(connectTag)) { Icon(Icons.Outlined.Cable, w("connect")) }
    }
}
