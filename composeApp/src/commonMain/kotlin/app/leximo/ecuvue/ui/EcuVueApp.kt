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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.*

@Composable fun EcuVueApp(
    host: AppHost,
    initialTranslations: Translations? = null,
    initialStartupError: String? = null,
) {
    val settings by host.settings.collectAsState()
    val state by host.session.state.collectAsState()
    val scope = rememberCoroutineScope()
    var translations by remember(host, initialTranslations) { mutableStateOf(initialTranslations) }
    val catalog by host.catalog.state.collectAsState()
    var startupError by remember(host, initialStartupError) { mutableStateOf(initialStartupError) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(host) { host.catalog.load() }
    LaunchedEffect(host, retry) {
        try {
            if (translations == null) translations = host.resources.translations()
            startupError = null
        }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { startupError = error.message ?: "Could not load interface translations" }
    }
    val dark = when (settings.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = appColors(settings.palette, dark), shapes = Shapes(medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp))) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val text = translations
            if (text == null) {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (startupError == null) {
                        if (host.platform != "Android") CircularProgressIndicator()
                    } else {
                        Text(startupError!!)
                        Button(onClick = { retry++ }) { Text("Retry") }
                    }
                }
            } else {
                val locale = settings.language.ifEmpty { host.systemLanguage }
                CompositionLocalProvider(LocalWords provides Words(text, locale), LocalLayoutDirection provides if (locale == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Workspace(host, state, settings, catalog, scope)
                }
            }
        }
    }
}

@Composable private fun Workspace(host: AppHost, state: SessionState, settings: AppSettings, catalog: CatalogState, scope: CoroutineScope) {
    val reference = (catalog as? CatalogState.Ready)?.reference
    val savedAdapter by host.savedAdapters.savedAdapter.collectAsState()
    val w = LocalWords.current
    var page by rememberSaveable { mutableStateOf("overview") }
    var previousPage by rememberSaveable { mutableStateOf("overview") }
    var code by rememberSaveable { mutableStateOf("") }
    var referenceQuery by rememberSaveable { mutableStateOf("") }
    var uiError by remember { mutableStateOf<DiagnosticException?>(null) }
    var clearDialog by remember { mutableStateOf(false) }
    val run: (suspend () -> Unit) -> Unit = { action ->
        scope.launch {
            uiError = null
            try { action() } catch (error: CancellationException) { throw error }
            catch (error: Exception) { uiError = error as? DiagnosticException ?: DiagnosticException("unexpectedError", error.message) }
        }
    }
    val openFault: (String) -> Unit = { code = it; if (page != "details") previousPage = page; page = "details" }
    val connect: () -> Unit = {
        if (page != "connections") {
            if (page != "details") previousPage = page
            page = "connections"
        }
    }
    val selectedTab = if (page in setOf("connections", "details")) previousPage else page
    val vehiclePages = setOf("diagnostics", "live")
    val back: () -> Unit = {
        val destination = if (page in setOf("connections", "details")) previousPage else "overview"
        page = if (!state.connected && destination in vehiclePages) "overview" else destination
    }
    LaunchedEffect(state.connected, page) {
        if (!state.connected) {
            if (page in vehiclePages) page = "overview"
            if (previousPage in vehiclePages) previousPage = "overview"
            clearDialog = false
        }
    }
    DisposableEffect(page, previousPage) { host.setBackAction(if (page == "overview") null else back); onDispose { host.setBackAction(null) } }
    LaunchedEffect(page) { host.screenViewed(if (page == "details") "fault_details" else page) }
    val nav = listOf(Triple("overview", w("overview"), Icons.Outlined.Dashboard), Triple("diagnostics", w("diagnostics"), Icons.Outlined.Troubleshoot),
        Triple("live", w("liveData"), Icons.Outlined.Speed), Triple("settings", w("settings"), Icons.Outlined.Settings))
    BoxWithConstraints {
        val wide = maxWidth >= 840.dp
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (!wide) NavigationBar {
                    nav.forEach { (id, label, icon) -> NavigationBarItem(selected = selectedTab == id, onClick = { page = id }, enabled = state.connected || id !in vehiclePages, modifier = Modifier.testTag("nav-$id"), icon = { Icon(icon, label) }, label = { Text(label, maxLines = 1) }) }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) NavigationRail(Modifier.fillMaxHeight(), containerColor = MaterialTheme.colorScheme.background) {
                    Spacer(Modifier.height(24.dp))
                    nav.forEach { (id, label, icon) -> NavigationRailItem(selected = selectedTab == id, onClick = { page = id }, enabled = state.connected || id !in vehiclePages, modifier = Modifier.testTag("nav-$id"), icon = { Icon(icon, label) }, label = { Text(label) }) }
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 36.dp else 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(if (page == "connections") 14.dp else 20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (page in setOf("connections", "details")) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, w("back")) }
                        Column(Modifier.weight(1f)) {
                            Heading(w(when (page) {
                                "connections" -> "devices"
                                "details" -> "faultDetails"
                                "live" -> "liveData"
                                else -> page
                            }), when (page) {
                                "connections" -> w("adaptersDescription")
                                "diagnostics" -> w("scanDescription")
                                "live" -> w("liveHint")
                                else -> null
                            })
                        }
                        if (page != "connections") Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val status = w(if (state.simulated) "simulation" else if (state.connected) "connected" else "notConnected")
                            IconButton(onClick = connect, modifier = Modifier.semantics { stateDescription = status }) {
                                Icon(Icons.Outlined.Cable, w("chooseConnection"), tint = if (state.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (state.simulated) Text(w("simulation"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (state.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(w(state.operation ?: "connecting", "Reading vehicle…"), style = MaterialTheme.typography.labelLarge)
                    }
                    val error = uiError ?: state.errorKey?.let { DiagnosticException(it, state.errorDetails) }
                    if (error != null) Message(w(error.key, error.message ?: "Operation failed"), error.details) { uiError = null; host.session.dismissMessage() }
                    state.notice?.let { Message(w(it), state.errorDetails) { host.session.dismissMessage() } }
                    when (page) {
                        "overview" -> {
                            Overview(state, reference, connect, { run { host.session.disconnect() } }, { run { host.session.scan(); page = "diagnostics" } }, { page = "live" }, openFault)
                            ReferenceSearch(catalog, referenceQuery, { referenceQuery = it }, openFault, host.catalog::load)
                        }
                        "connections" -> Connections(host, state, run,
                            { request -> run { host.connect(request); page = "overview" } }, { run { host.session.disconnect() } },
                            host::openBluetoothSettings, host::openWifiSettings)
                        "diagnostics" -> Diagnostics(state, reference, { run { host.session.scan() } }, { clearDialog = true }, openFault)
                        "details" -> FaultDetails(code, state, reference)
                        "live" -> LivePage(state, { driving -> run { host.session.setDriving(driving) } }, { run { host.connect(ConnectionRequest(ConnectionKind.SIMULATOR)) } })
                        "settings" -> SettingsPage(settings, reference, host.platform == "Android", host.supportsAiTools, savedAdapter,
                            { allowed -> run { host.savedAdapters.allowAssistantReconnect(allowed) } },
                            { run { host.savedAdapters.forget() } }) { value -> run { host.updateSettings(value) } }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
    if (clearDialog && state.connected) AlertDialog(onDismissRequest = { clearDialog = false }, title = { Text(w("clearTitle")) },
        text = { Text(w(if (state.simulated) "demoClearExplanation" else "clearExplanation")) },
        confirmButton = { TextButton(onClick = { clearDialog = false; run { host.session.clearFaults() } }) { Text(w("confirmClear")) } },
        dismissButton = { TextButton(onClick = { clearDialog = false }) { Text(w("cancel")) } })
}
