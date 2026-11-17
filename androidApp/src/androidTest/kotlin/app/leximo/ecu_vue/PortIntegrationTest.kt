package app.leximo.ecu_vue

import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import app.leximo.ecuvue.core.*
import app.leximo.ecuvue.ui.AppHost
import app.leximo.ecuvue.ui.EcuVueApp
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.koin.android.ext.android.getKoin
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PortIntegrationTest {
    @get:Rule(order = 0) val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()
    private val container get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EcuVueApplication).getKoin()
    private val host get() = container.get<AppHost>()
    private val tools get() = container.get<ReadOnlyDiagnosticTools>()
    private var originalSavedAdapter: SavedAdapter? = null
    @Before fun prepare() = runBlocking {
        originalSavedAdapter = host.savedAdapters.savedAdapter.value
        host.savedAdapters.forget()
        withContext(Dispatchers.Main) {
            host.session.disconnect()
            host.updateSettings(AppSettings(theme = "light", language = "en"))
        }
        ui.waitUntil(20_000) { ui.onAllNodesWithText("Choose connection").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun finish() = runBlocking {
        withContext(Dispatchers.Main) { host.session.disconnect(); host.updateSettings(AppSettings(theme = "light", language = "en")) }
        host.savedAdapters.forget()
        originalSavedAdapter?.let { host.savedAdapters.remember(it); host.savedAdapters.allowAssistantReconnect(it.assistantReconnectAllowed) }
        Unit
    }
    @Test fun savedAdapterApprovalPersistsAndCanBeRevokedFromSettings() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("saved_adapter_test", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try {
            val repository = app.leximo.ecu_vue.data.AndroidSavedAdapterRepository(preferences)
            val adapter = SavedAdapter(ConnectionRequest(ConnectionKind.BLE, "AA:BB:CC:DD:EE:01", "Test BLE adapter"))
            repository.remember(adapter)
            repository.allowAssistantReconnect(true)
            val restored = app.leximo.ecu_vue.data.AndroidSavedAdapterRepository(preferences)
            assertTrue(restored.savedAdapter.value!!.assistantReconnectAllowed)
            assertEquals(adapter.request, restored.savedAdapter.value!!.request)
            restored.forget()
            assertNull(app.leximo.ecu_vue.data.AndroidSavedAdapterRepository(preferences).savedAdapter.value)

            host.savedAdapters.remember(adapter)
            withContext(Dispatchers.Main) { host.updateSettings(host.settings.value.copy(aiEnabled = true)) }
            ui.onNodeWithTag("nav-settings").performClick()
            ui.onNodeWithTag("ai-reconnect").performScrollTo().assertIsOff().performClick()
            ui.waitUntil(5_000) { host.savedAdapters.savedAdapter.value?.assistantReconnectAllowed == true }
            ui.onNodeWithTag("ai-reconnect").assertIsOn()
            screenshot("14-saved-adapter-approval")
            ui.onNodeWithTag("forget-saved-adapter").performScrollTo().performClick()
            ui.waitUntil(5_000) { host.savedAdapters.savedAdapter.value == null }
            ui.onNodeWithTag("no-saved-adapter").assertExists()
        } finally { preferences.edit().clear().commit() }
        Unit
    }

    @Test fun assistantConnectionUsesNativeServiceAndHandlesBackgroundRestrictions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = StoredSavedAdapterRepository()
        val selected = SavedAdapter(ConnectionRequest(ConnectionKind.WIFI, "127.0.0.1", "Test adapter"))
        repository.remember(selected)
        repository.allowAssistantReconnect(true)
        var handshakes = 0
        var failHandshake = false
        val simulation = SimulatedAdapter()
        val fakeHardware = object : DiagnosticAdapter by simulation {
            override val simulated = false
            override val name = "Injected test hardware"
            override suspend fun connect() {
                handshakes++
                if (failHandshake) throw DiagnosticException("connectionFailed")
                simulation.connect()
            }
        }
        val controller = app.leximo.ecu_vue.platform.AndroidConnectionController(
            context, host.session, container.get(), object : DiagnosticAdapterFactory {
                override suspend fun create(request: ConnectionRequest): DiagnosticAdapter {
                    assertEquals(selected.request, request)
                    return fakeHardware
                }
            }, container.get(), container.get(app.leximo.ecuvue.di.ApplicationScope), repository, container.get(),
        )
        val connectionTools = AssistantConnectionTools(host.session, repository, controller) { host.settings.value.aiEnabled }
        withContext(Dispatchers.Main) { host.updateSettings(host.settings.value.copy(aiEnabled = true)) }
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        delay(1_000)
        val background = runCatching { connectionTools.connectSavedAdapter() }
        background.exceptionOrNull()?.let {
            assertTrue(it.toString(), it is DiagnosticException)
            assertEquals("assistantForegroundRequired", (it as DiagnosticException).key)
            assertFalse(host.session.state.value.connected)
            assertEquals(0, handshakes)
        }
        android.util.Log.i("EcuVueConnectionTest", "Background result: ${background.exceptionOrNull()?.message ?: "connected"}")
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        val connected = connectionTools.connectSavedAdapter()
        assertTrue(connected.connected)
        assertEquals(1, handshakes)
        assertEquals(connected.sessionId, connectionTools.connectSavedAdapter().sessionId)
        host.session.stop()
        delay(1_500) // Let the connection foreground service finish before another test starts.
        failHandshake = true
        val failed = runCatching { connectionTools.connectSavedAdapter() }
        assertEquals("connectionFailed", (failed.exceptionOrNull() as DiagnosticException).key)
        assertFalse(host.session.state.value.connected)
        delay(1_500) // Fast failure must clean up after startForeground without crashing the process.
        ui.onNodeWithTag("nav-overview").assertExists()
        Unit
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion = 31)
    @Test fun foregroundServiceDenialReturnsAnActionableError() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deniedContext = object : android.content.ContextWrapper(context) {
            override fun startForegroundService(service: android.content.Intent): android.content.ComponentName? {
                throw android.app.ForegroundServiceStartNotAllowedException("Injected platform denial")
            }
        }
        val launcher = app.leximo.ecu_vue.platform.ConnectionServiceLauncher(deniedContext)
        val failure = runCatching { launcher.start() }.exceptionOrNull()
        assertTrue(failure.toString(), failure is DiagnosticException)
        assertEquals("assistantForegroundRequired", (failure as DiagnosticException).key)
        assertFalse(host.session.state.value.connected)
        Unit
    }
    @Test fun connectionPickerKeepsTabsAndExpandsTransportOptions() {
        ui.onNodeWithTag("nav-settings").performClick()
        ui.onNodeWithContentDescription("Choose connection").performClick()
        ui.onNodeWithTag("nav-settings").assertIsDisplayed().assertIsSelected()
        ui.onNodeWithTag("connect-simulator").assert(hasAnyAncestor(hasTestTag("available-adapters")))
        screenshot("07-connections-collapsed")
        listOf("wifi", "usb").forEach { kind ->
            ui.onNodeWithTag("connection-$kind-toggle").performScrollTo().assertIsOff().performClick().assertIsOn()
            ui.onNodeWithTag("nav-settings").assertIsDisplayed()
            if (kind == "wifi") {
                ui.onNodeWithText("192.168.0.10").performScrollTo().performTextReplacement("192.168.1.20")
            }
            ui.onNodeWithTag("connection-$kind-toggle").performScrollTo().performClick().assertIsOff()
        }
        ui.onNodeWithTag("connection-wifi-toggle").performScrollTo().performClick()
        ui.onNodeWithText("192.168.1.20").assertExists()
        screenshot("08-connections-wifi")
        ui.onNodeWithContentDescription("Back").performScrollTo().performClick()
        ui.onNodeWithTag("available-adapters").assertDoesNotExist()
        ui.onNodeWithTag("nav-settings").assertIsSelected()
        ui.onNodeWithContentDescription("Choose connection").performClick()
        ui.onNodeWithTag("nav-settings").performClick()
        ui.onNodeWithTag("available-adapters").assertDoesNotExist()
        ui.onNodeWithTag("nav-settings").assertIsSelected()
    }
    @Test fun offlineReferenceAndTabsFollowConnectionState() {
        ui.waitUntil(20_000) { host.catalog.state.value is CatalogState.Ready }
        ui.onNodeWithTag("nav-diagnostics").assertIsNotEnabled()
        ui.onNodeWithTag("nav-live").assertIsNotEnabled()
        ui.onNodeWithTag("offline-reference").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("reference-query").performScrollTo().performTextReplacement("P0301")
        ui.onNode(hasText("P0301") and !hasSetTextAction()).performScrollTo().performClick()
        ui.onNodeWithText("What this code means").performScrollTo().assertIsDisplayed()
        ui.onNodeWithContentDescription("Back").performScrollTo().performClick()
        ui.onNodeWithTag("nav-overview").assertIsSelected()
        ui.onNodeWithTag("reference-query").assertTextContains("P0301")
        screenshot("10-offline-reference")
        runBlocking(Dispatchers.Main) { host.connect(ConnectionRequest(ConnectionKind.SIMULATOR)) }
        ui.onNodeWithTag("disconnect-overview").performScrollTo().assertIsDisplayed()
        screenshot("11-connected-overview")
        ui.onNodeWithTag("disconnect-overview").performClick()
        ui.waitUntil(5_000) { !host.session.state.value.connected && !host.session.state.value.busy }
        ui.onNodeWithTag("nav-diagnostics").assertIsNotEnabled()
        ui.onNodeWithTag("nav-live").assertIsNotEnabled()
        ui.onNodeWithTag("reference-query").assertTextContains("P0301")
        runBlocking(Dispatchers.Main) { host.connect(ConnectionRequest(ConnectionKind.SIMULATOR)) }
        ui.onNodeWithTag("nav-diagnostics").assertIsEnabled().performClick()
        ui.onNodeWithTag("nav-live").assertIsEnabled()
        ui.onNodeWithText("Choose connection").assertDoesNotExist()
        ui.onNodeWithTag("offline-reference").assertDoesNotExist()
        runBlocking(Dispatchers.Main) { host.session.disconnect() }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("offline-reference").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("nav-overview").assertIsSelected()
        ui.onNodeWithTag("nav-diagnostics").assertIsNotEnabled()
        ui.onNodeWithTag("reference-query").assertTextContains("P0301")
        runBlocking(Dispatchers.Main) { host.connect(ConnectionRequest(ConnectionKind.SIMULATOR)) }
        ui.onNodeWithTag("nav-live").performClick()
        ui.onNodeWithText("Choose connection").assertDoesNotExist()
        ui.onNodeWithTag("offline-reference").assertDoesNotExist()
        runBlocking(Dispatchers.Main) { host.session.disconnect() }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("offline-reference").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("nav-overview").assertIsSelected()
        ui.onNodeWithTag("nav-live").assertIsNotEnabled()
    }
    @Test fun unifiedBluetoothDiscoveryRoutesDevicesAndStopsOnExit() {
        val classic = AdapterDevice("paired-classic", "Paired ELM", ConnectionKind.CLASSIC)
        val pairedBle = AdapterDevice("paired-ble", "Paired BLE OBD", ConnectionKind.BLE)
        val nearbyBle = AdapterDevice("nearby-ble", "Nearby BLE OBD", ConnectionKind.BLE)
        val gate = AtomicReference(CompletableDeferred<Unit>())
        val scans = AtomicInteger()
        val finished = AtomicInteger()
        val requests = ConcurrentLinkedQueue<ConnectionRequest>()
        val fake = object : AppHost by host {
            override suspend fun pairedDevices() = listOf(classic, pairedBle)
            override suspend fun bleDevices(): List<AdapterDevice> {
                scans.incrementAndGet()
                try { gate.get().await(); return listOf(nearbyBle, nearbyBle, pairedBle) }
                finally { finished.incrementAndGet() }
            }
            override suspend fun connect(request: ConnectionRequest) { requests.add(request) }
        }
        ui.activityRule.scenario.onActivity { it.setContent { EcuVueApp(fake) } }
        ui.waitForIdle()
        ui.onNodeWithText("Choose connection").performClick()
        ui.onNodeWithText("Classic").assertExists()
        ui.onAllNodesWithText("BLE").assertCountEquals(1)
        ui.onNodeWithTag("find-bluetooth-adapters").performClick()
        ui.waitUntil(5_000) { scans.get() == 1 }
        // Paired adapters stay available during the BLE search.
        ui.onNodeWithTag("connect-classic-paired-classic").assertIsEnabled()
        ui.onNodeWithTag("stop-bluetooth-discovery").assertExists()
        gate.get().complete(Unit)
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("connect-ble-nearby-ble").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText("BLE").assertCountEquals(2)
        ui.onNodeWithTag("connect-ble-nearby-ble").assert(hasAnyAncestor(hasTestTag("available-adapters")))
        screenshot("09-unified-bluetooth")
        ui.onNodeWithTag("connect-ble-nearby-ble").performScrollTo().performClick()
        ui.waitUntil(5_000) { requests.size == 1 }
        assertEquals(ConnectionKind.BLE, requests.last().kind)
        assertEquals(nearbyBle.id, requests.last().id)
        ui.onNodeWithText("Choose connection").performClick()
        ui.onNodeWithTag("connect-classic-paired-classic").performScrollTo().performClick()
        ui.waitUntil(5_000) { requests.size == 2 }
        assertEquals(ConnectionKind.CLASSIC, requests.last().kind)
        assertEquals(classic.id, requests.last().id)
        gate.set(CompletableDeferred())
        ui.onNodeWithText("Choose connection").performClick()
        ui.onNodeWithTag("find-bluetooth-adapters").performClick()
        ui.waitUntil(5_000) { scans.get() == 2 }
        ui.onNodeWithTag("stop-bluetooth-discovery").performClick()
        ui.waitUntil(5_000) { finished.get() == 2 }
        ui.onNodeWithTag("find-bluetooth-adapters").assertIsEnabled().performClick()
        ui.waitUntil(5_000) { scans.get() == 3 }
        ui.onNodeWithTag("nav-settings").performClick()
        ui.waitUntil(5_000) { finished.get() == 3 }
        ui.onNodeWithTag("nav-settings").assertIsSelected()
    }
    @Test fun appearanceChangesKeepLauncherEnabledWhileActivityIsResumed() = runBlocking {
        val context = ui.activity
        val manager = context.packageManager
        @Suppress("DEPRECATION")
        val aliases = manager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_ACTIVITIES or android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)
            .activities!!.filter { ".launcher." in it.name }.map { android.content.ComponentName(context.packageName, it.name) }
        assertEquals(9, aliases.size)
        val before = aliases.associateWith(manager::getComponentEnabledSetting)
        withContext(Dispatchers.Main) { host.updateSettings(host.settings.value.copy(theme = "dark", palette = "sunset")) }
        ui.waitForIdle()
        assertEquals(before, aliases.associateWith(manager::getComponentEnabledSetting))
        ui.activityRule.scenario.onActivity {
            assertFalse(it.isFinishing)
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, it.lifecycle.currentState)
        }
        Unit
    }
    @Test fun simulatorUiScanDetailsLiveAndConfirmedClear() {
        screenshot("01-overview")
        ui.onNodeWithText("Choose connection").performClick()
        ui.onNodeWithTag("connect-simulator").performScrollTo().performClick()
        ui.waitUntil(10_000) { host.session.state.value.connected && !host.session.state.value.busy }
        ui.onNodeWithTag("nav-diagnostics").performClick()
        ui.onNodeWithTag("scan-faults").performScrollTo().performClick()
        ui.waitUntil(10_000) { host.session.state.value.report?.faults?.size == 3 && !host.session.state.value.busy }
        ui.onNodeWithText("P0301").assertExists()
        screenshot("02-diagnostics")
        ui.onNodeWithText("P0301").performScrollTo().performClick()
        ui.onNodeWithText("What this code means").performScrollTo().assertIsDisplayed()
        screenshot("03-fault-details")
        ui.onNodeWithContentDescription("Back").performScrollTo().performClick()
        ui.onNodeWithTag("nav-live").performClick()
        ui.waitUntil(10_000) { host.session.state.value.live != null }
        screenshot("04-live")
        ui.onNodeWithTag("nav-diagnostics").performClick()
        ui.onNodeWithText("Clear fault codes").performScrollTo().performClick()
        ui.onNodeWithText("Clear diagnostic data?").assertIsDisplayed()
        ui.onNodeWithText("Cancel").performClick()
        assertEquals(3, host.session.state.value.report?.faults?.size)
        ui.onNodeWithText("Clear fault codes").performScrollTo().performClick()
        ui.onNodeWithText("Clear stored & pending").performClick()
        ui.waitUntil(10_000) { host.session.state.value.report?.faults?.size == 1 && !host.session.state.value.busy }
        assertEquals("P0420", host.session.state.value.report!!.faults.single().code)
        ui.onNodeWithTag("clear-faults").assertIsNotEnabled()
    }
    @Test fun nativeToolsCanRunWithoutAnActivityAndPersistReports() = runBlocking {
        host.connect(ConnectionRequest(ConnectionKind.SIMULATOR))
        assertFailsDisabled()
        withContext(Dispatchers.Main) { host.updateSettings(host.settings.value.copy(aiEnabled = true)) }
        val report = tools.readFaultCodes()
        assertTrue(report.simulated)
        assertEquals(3, report.report.faults.size)
        val reference = tools.lookupCode("P0301", "tr")
        assertNotNull(reference)
        assertFalse(reference!!.isFallback)
        assertEquals(9532, container.get<ReferenceRepository>().reference().count)
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        val live = tools.readLiveSnapshot()
        assertNotNull(live.data.rpm)
        host.session.disconnect()
        assertEquals(report, tools.latestReport())
        assertFalse(tools.connectionStatus().connected)
        withContext(Dispatchers.Main) { host.updateSettings(host.settings.value.copy(aiEnabled = false)) }
        assertFailsDisabled()
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        Unit
    }
    @Test fun appearanceAndArabicPreserveSession() = runBlocking {
        host.connect(ConnectionRequest(ConnectionKind.SIMULATOR))
        val id = host.session.state.value.sessionId
        ui.onNodeWithTag("nav-settings").performClick()
        ui.onNodeWithText("Ice blue").performScrollTo().performClick()
        ui.waitUntil(10_000) { host.settings.value.palette == "iceBlue" }
        ui.onNodeWithTag("palette-iceBlue").assertIsSelected()
        screenshot("12-settings-light")
        ui.onNodeWithTag("theme-dropdown").performScrollTo().performClick()
        ui.onNodeWithTag("theme-dropdown-option-dark").performClick()
        ui.waitUntil(10_000) { host.settings.value.theme == "dark" }
        screenshot("05-settings-dark")
        ui.onNodeWithTag("language-dropdown").performScrollTo().performClick()
        screenshot("13-language-dropdown")
        ui.onNodeWithTag("language-dropdown-option-ar").performScrollTo().performClick()
        ui.waitUntil(10_000) { host.settings.value.language == "ar" }
        ui.waitForIdle()
        assertEquals(id, host.session.state.value.sessionId)
        assertTrue(host.session.state.value.connected)
        screenshot("06-arabic")
        ui.activityRule.scenario.recreate()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("language-dropdown").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("ar", host.settings.value.language)
        assertEquals("dark", host.settings.value.theme)
        assertEquals("iceBlue", host.settings.value.palette)
        assertEquals(id, host.session.state.value.sessionId)
        assertTrue(host.session.state.value.connected)
    }
    @Test fun androidSystemDiscoversAndExecutesReadOnlyFunctions() = runBlocking {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String): String {
            val streams = automation.executeShellCommandRwe(command)
            streams[1].close()
            val output = ParcelFileDescriptor.AutoCloseInputStream(streams[0]).bufferedReader().use { it.readText() }
            val error = ParcelFileDescriptor.AutoCloseInputStream(streams[2]).bufferedReader().use { it.readText() }
            return output + error
        }
        val id = "app.leximo.ecu_vue.ai.BaseDiagnosticAppFunctions#getConnectionStatus"
        var indexed = false
        repeat(30) {
            if (!indexed) { indexed = shell("cmd app_function list-app-functions").contains(id); if (!indexed) delay(1_000) }
        }
        assertTrue("Android must index the generated AppFunctions", indexed)
        val allFunctions = shell("cmd app_function list-app-functions")
        for (function in app.leximo.ecu_vue.ai.DiagnosticFunctionIds.all) assertTrue(function, allFunctions.contains(function))
        withContext(Dispatchers.Main) { host.updateSettings(host.settings.value.copy(aiEnabled = true)) }
        host.connect(ConnectionRequest(ConnectionKind.SIMULATOR))
        val command = "cmd app_function execute-app-function --package app.leximo.ecu_vue --function $id --parameters {} --brief-yaml"
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        var status = ""
        repeat(30) {
            if (!status.contains("simulated")) { status = shell(command); if (!status.contains("simulated")) delay(250) }
        }
        assertFalse(status, status.contains("Error executing"))
        assertTrue(status, status.contains("simulated"))
        val reconnect = shell("cmd app_function execute-app-function --package app.leximo.ecu_vue --function app.leximo.ecu_vue.ai.BaseDiagnosticAppFunctions#connectSavedAdapter --parameters {} --brief-yaml")
        assertFalse(reconnect, reconnect.contains("Error executing"))
        assertTrue(reconnect, reconnect.contains("simulated"))
        val scan = shell("cmd app_function execute-app-function --package app.leximo.ecu_vue --function app.leximo.ecu_vue.ai.BaseDiagnosticAppFunctions#readFaultCodes --parameters {} --brief-yaml")
        assertFalse(scan, scan.contains("Error executing"))
        assertTrue(scan, scan.contains("P0301"))
        assertTrue(scan, scan.contains("simulated"))
        assertTrue("Fault results must include reference explanations: $scan", scan.contains("possibleCauses"))
        assertTrue(scan, scan.contains("suggestedNextStep"))
        assertFalse("VIN must not be exposed", scan.contains("WDD00000000000001"))
        host.session.disconnect()
        val missingProfile = shell("cmd app_function execute-app-function --package app.leximo.ecu_vue --function app.leximo.ecu_vue.ai.BaseDiagnosticAppFunctions#connectSavedAdapter --parameters {} --brief-yaml")
        assertTrue(missingProfile, missingProfile.contains("real adapter", ignoreCase = true))
        host.savedAdapters.remember(SavedAdapter(ConnectionRequest(ConnectionKind.CLASSIC, "AA:BB:CC:DD:EE:01", "Test profile")))
        val notApproved = shell("cmd app_function execute-app-function --package app.leximo.ecu_vue --function app.leximo.ecu_vue.ai.BaseDiagnosticAppFunctions#connectSavedAdapter --parameters {} --brief-yaml")
        assertTrue(notApproved, notApproved.contains("approve reconnection", ignoreCase = true))
        withContext(Dispatchers.Main) { host.updateSettings(host.settings.value.copy(aiEnabled = false)) }
        val disabled = shell(command)
        assertTrue(disabled, disabled.contains("disabled", ignoreCase = true) || disabled.contains("denied", ignoreCase = true))
        val deniedConnect = shell("cmd app_function execute-app-function --package app.leximo.ecu_vue --function app.leximo.ecu_vue.ai.BaseDiagnosticAppFunctions#connectSavedAdapter --parameters {} --brief-yaml")
        assertTrue(deniedConnect, deniedConnect.contains("disabled", ignoreCase = true) || deniedConnect.contains("denied", ignoreCase = true))
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        Unit
    }
    private fun assertFailsDisabled() {
        try { tools.connectionStatus(); fail("AI access must be denied") }
        catch (error: DiagnosticException) { assertEquals("aiAccessDisabled", error.key) }
    }
    private fun screenshot(name: String) {
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "port-previews").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
