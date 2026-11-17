@file:OptIn(androidx.appfunctions.ExperimentalAppFunctionsApi::class)
package app.leximo.ecu_vue.ai

import androidx.annotation.RequiresApi
import androidx.appfunctions.*
import androidx.appfunctions.metadata.AppFunctionMetadata
import org.koin.android.ext.android.inject
import app.leximo.ecuvue.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

@AppFunctionSerializable data class ConnectionStatusResult(
    val connected: Boolean, val busy: Boolean, val simulated: Boolean, val latestReportCollectedAtEpochMillis: Long?,
    val savedAdapterAvailable: Boolean, val assistantReconnectAllowed: Boolean, val savedAdapterReconnectSupported: Boolean,
    val nextStep: String,
)
@AppFunctionSerializable data class CapabilitiesResult(
    val protocol: String, val supportedPids: List<String>, val discoveryComplete: Boolean,
    val pendingCodes: String, val permanentCodes: String, val freezeFrame: String,
)
/** The reference is inspection guidance, not a diagnosis of a failed component. Null means no bundled definition. */
@AppFunctionSerializable data class FaultResult(val code: String, val kind: String, val explanation: CodeReferenceResult?)
/** Values are nullable when no usable reading was reported. Units are fixed and explicit. */
@AppFunctionSerializable data class LiveSnapshotResult(
    val collectedAtEpochMillis: Long, val simulated: Boolean,
    val rpm: Double?, val speedKph: Double?, val coolantCelsius: Double?,
    val adapterVoltageVolts: Double?, val engineLoadPercent: Double?, val throttlePercent: Double?,
)
/** A historical ECU snapshot. Collection time is retrieval time, not the time the fault occurred. */
@AppFunctionSerializable data class FreezeFrameResult(val faultCode: String, val sourceEcu: String, val readings: LiveSnapshotResult)
@AppFunctionSerializable data class FaultReportResult(
    val collectedAtEpochMillis: Long, val simulated: Boolean, val currentConnectionSession: Boolean,
    val faults: List<FaultResult>, val pendingModeStatus: String, val permanentModeStatus: String,
    val freezeFrameStatus: String, val freezeFrame: FreezeFrameResult?,
)
/** Reference descriptions are possible causes and inspection guidance, not confirmed failed parts. */
@AppFunctionSerializable data class CodeReferenceResult(
    val code: String, val found: Boolean, val language: String, val usedEnglishFallback: Boolean,
    val title: String, val meaning: String, val possibleCauses: List<String>, val suggestedNextStep: String,
)

/** The OS binds the generated subclass; no Activity or UI needs to exist. */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(serviceName = "DiagnosticAppFunctionService", appFunctionXmlFileName = "ecu_vue_functions")
@AppFunctionAccessLevel(level = AppFunctionMetadata.ACCESS_LEVEL_ANDROID_TRUSTED, isCompatEnforcementEnabled = true)
abstract class BaseDiagnosticAppFunctions : AppFunctionService() {
    private val tools: ReadOnlyDiagnosticTools by inject()
    private val session: DiagnosticSession by inject()
    private val connections: AssistantConnectionTools by inject()
    private val savedAdapters: SavedAdapterRepository by inject()
    private val settings: SettingsRepository by inject()
    private suspend fun <T> execute(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            tools.connectionStatus() // Runtime consent is authoritative, even before OS indexing catches up.
            val result = block()
            tools.connectionStatus()
            result
        }
        catch (error: DiagnosticException) {
            throw when (error.key) {
                "aiAccessDisabled" -> AppFunctionDeniedException("Enable AI assistant access in ECU Vue settings first.")
                "invalidCode" -> AppFunctionInvalidArgumentException("Provide an OBD code such as P0301.")
                "noScan" -> AppFunctionElementNotFoundException("No saved report. Connect and scan using ECU Vue first.")
                "busy" -> AppFunctionLimitExceededException("The adapter is busy. Try again after the current operation finishes.")
                "connectionLost" -> AppFunctionAppUnknownException("No usable vehicle connection. Open ECU Vue and connect your adapter, or request the latest saved report.")
                "permissionDenied", "usbPermissionDenied" -> AppFunctionPermissionRequiredException("Open ECU Vue and grant the required Bluetooth or USB permissions, then retry.")
                "bleUnsupported" -> AppFunctionNotSupportedException("The saved BLE adapter does not provide a supported diagnostic service. Check the adapter in ECU Vue; do not choose another device automatically.")
                "noSavedAdapter" -> AppFunctionElementNotFoundException("Open ECU Vue and connect a real adapter once, then enable Settings > AI assistant access > Allow AI to reconnect this adapter.")
                "adapterReconnectNotAllowed" -> AppFunctionPermissionRequiredException("In ECU Vue Settings > AI assistant access, approve reconnection to the saved adapter. Do not try another device.")
                "savedAdapterUnsupported" -> AppFunctionNotSupportedException("Connect this USB adapter manually in ECU Vue. Automatic USB reconnection needs a unique serial number and existing USB permission.")
                "assistantSetupRequired", "assistantForegroundRequired" -> AppFunctionPermissionRequiredException("Open ECU Vue and connect the saved adapter to complete Android permission or connection setup, then retry readFaultCodes. Android did not allow an unattended connection.")
                "bluetoothOff" -> AppFunctionPermissionRequiredException("Turn on Bluetooth, then retry connectSavedAdapter. Open ECU Vue if Android requests setup.")
                "bluetoothUnavailable", "assistantConnectionUnsupported" -> AppFunctionNotSupportedException("This device does not support the requested adapter connection.")
                "pairingRequired" -> AppFunctionPermissionRequiredException("Pair the saved adapter in Android Bluetooth settings, connect it in ECU Vue, then retry.")
                "timeout", "connectionFailed" -> AppFunctionAppUnknownException("Could not reach the vehicle adapter. Check that it is powered and in range, open ECU Vue to connect if needed, then retry. No fresh readings were obtained.")
                else -> AppFunctionAppUnknownException("ECU Vue could not complete the request (${error.key}). Open the app to inspect connection details. No successful result is available.")
            }
        }
        catch (error: CancellationException) { throw error }
        catch (error: SecurityException) { throw AppFunctionPermissionRequiredException("Open ECU Vue and grant the required adapter permissions, then retry.") }
        catch (error: Exception) { throw AppFunctionAppUnknownException("ECU Vue could not complete the request. Open the app to check the adapter connection and retry.") }
    }

    /**
     * Checks whether ECU Vue can query the user's car and whether a saved adapter is approved for reconnection.
     * For requests such as "check my car's fault codes", check this first; if disconnected and approved,
     * call connectSavedAdapter, then readFaultCodes. Does not connect, pair, scan, or return hardware addresses.
     * A simulated connection contains generated data and must never be described as a real vehicle.
     */
    @AppFunctionDeclaration(isEnabled = false, isDescribedByKDoc = true)
    suspend fun getConnectionStatus(): ConnectionStatusResult = execute {
        connectionStatus(tools.connectionStatus())
    }

    /**
     * Connects the user's previously saved and explicitly approved car diagnostic adapter so fault codes or
     * live sensor readings can be requested. Takes no device address and never discovers or pairs a new device.
     * Reuses any existing connection without switching adapters; simulated=true means generated test data.
     * Requires AI access and separate saved-adapter reconnection approval in ECU Vue Settings.
     * Bluetooth or Wi-Fi must already be enabled and configured; USB requires a unique serial and permission.
     * May take up to 120 seconds. If Android requires foreground interaction, tell the user to open ECU Vue
     * and connect, then retry the diagnostic read. Never bypass permission errors or choose another adapter.
     * This initializes a diagnostic session; it does not clear codes, reset readiness, or program the car.
     * After success call readFaultCodes for a fresh fault scan, or readLiveSnapshot for current sensor data.
     */
    @AppFunctionDeclaration(isEnabled = false, isDescribedByKDoc = true)
    suspend fun connectSavedAdapter(): ConnectionStatusResult = execute {
        connectionStatus(connections.connectSavedAdapter())
    }

    /** Returns the connected car's discovered OBD protocol, supported PID hex IDs and diagnostic mode availability.
     * Requires a connection; call connectSavedAdapter first if needed. UNKNOWN and UNAVAILABLE do not prove
     * a feature is unsupported. Generic OBD coverage does not imply access to ABS, airbags or every ECU.
     */
    @AppFunctionDeclaration(isEnabled = false, isDescribedByKDoc = true)
    suspend fun getCapabilities(): CapabilitiesResult = execute {
        val state = tools.connectionStatus()
        if (!state.connected) throw DiagnosticException("connectionLost")
        val c = state.capabilities
        CapabilitiesResult(c.protocolName, c.livePids.sorted().map { it.toString(16).uppercase().padStart(2, '0') }, c.pidDiscoveryComplete, c.pendingCodes.name, c.permanentCodes.name, c.freezeFrame.name)
    }

    /**
     * Reads stored, pending and permanent generic OBD fault codes and available freeze-frame zero data.
     * Requires a user-connected adapter. Read-only: does not clear codes, reset readiness, or program ECUs.
     * Only responding diagnostic modes are covered. Fault codes identify conditions, not confirmed failed parts.
     * If disconnected, call connectSavedAdapter first. Each fault includes a bundled explanation when available,
     * with possible causes and inspection guidance in the app's language (English fallback is marked).
     * Report the collection time and simulation flag. An empty list does not prove the entire car is healthy.
     */
    @AppFunctionDeclaration(isEnabled = false, isDescribedByKDoc = true)
    suspend fun readFaultCodes(): FaultReportResult = execute { report(tools.readFaultCodes()) }

    /**
     * Returns the most recent saved diagnostic report without communicating with the car.
     * Always state its collection time and simulated flag. currentConnectionSession=false means it belongs to a previous connection.
     * Includes offline code explanations when available. Use readFaultCodes for a fresh scan; never describe this cache as a new scan.
     */
    @AppFunctionDeclaration(isEnabled = false, isDescribedByKDoc = true)
    suspend fun getLatestReport(): FaultReportResult = execute { report(tools.latestReport()) }

    /** Reads one bounded set of supported current sensor values, not a continuous stream or cached report.
     * If disconnected, call connectSavedAdapter first. Units: RPM, km/h, degrees Celsius, volts and percent.
     * Missing readings remain null, never zero. Always disclose simulation and collection time.
     */
    @AppFunctionDeclaration(isEnabled = false, isDescribedByKDoc = true)
    suspend fun readLiveSnapshot(): LiveSnapshotResult = execute {
        val result = tools.readLiveSnapshot()
        live(result.data, result.simulated)
    }

    /**
     * Looks up an offline generic OBD code without connecting to a vehicle. A reference match does not mean the fault is present.
     * @param code Five-character diagnostic code, such as P0301. Manufacturer-specific definitions may be unavailable.
     * @param language Preferred language code, such as en or tr. Reports an English fallback when a translation is absent.
     */
    @AppFunctionDeclaration(isEnabled = false, isDescribedByKDoc = true)
    suspend fun lookupCode(code: String, language: String? = null): CodeReferenceResult = execute {
        val requested = language ?: "en"
        val match = tools.lookupCode(code, requested)
        CodeReferenceResult(code.trim().uppercase(), match != null, match?.language ?: requested, match?.isFallback ?: false,
            match?.description?.title.orEmpty(), match?.description?.meaning.orEmpty(), match?.description?.causes.orEmpty(), match?.description?.next.orEmpty())
    }

    private fun connectionStatus(state: SessionState): ConnectionStatusResult {
        val saved = savedAdapters.savedAdapter.value
        val next = when {
            state.connected -> if (state.simulated) "Connected to the simulator. All readings are generated test data." else "Call readFaultCodes for a fresh fault scan or readLiveSnapshot for sensor values."
            saved == null -> "Open ECU Vue and connect a real adapter once, then approve assistant reconnection in Settings."
            !saved.reconnectSupported -> "Connect this adapter manually in ECU Vue. Automatic USB reconnection requires a unique serial number and USB permission."
            !saved.assistantReconnectAllowed -> "Approve this saved adapter in ECU Vue Settings > AI assistant access > Allow AI to reconnect this adapter."
            else -> "Call connectSavedAdapter, then request the diagnostic reading. Android may require opening ECU Vue."
        }
        return ConnectionStatusResult(state.connected, state.busy, state.simulated, state.latestReport?.report?.scannedAt,
            saved != null, saved?.assistantReconnectAllowed == true, saved?.reconnectSupported == true, next)
    }

    private suspend fun report(saved: SavedReport): FaultReportResult {
        val r = saved.report
        val language = settings.settings.value.language.ifEmpty { java.util.Locale.getDefault().language }
        val explanations = r.faults.map { it.code }.distinct().associateWith { code ->
            val match = tools.lookupCode(code, language)
            match?.let { CodeReferenceResult(code, true, it.language, it.isFallback,
                it.description.title, it.description.meaning, it.description.causes, it.description.next) }
        }
        val state = session.state.value
        return FaultReportResult(r.scannedAt, saved.simulated, state.connected && saved.sessionId == state.sessionId,
            r.faults.map { FaultResult(it.code, it.kind.name, explanations[it.code]) }, r.pendingStatus.name, r.permanentStatus.name, r.freezeFrameStatus.name,
            r.freezeFrame?.let { FreezeFrameResult(it.code, it.sourceEcu, live(it.data, saved.simulated)) })
    }
    private fun live(data: LiveData, simulated: Boolean) = LiveSnapshotResult(data.timestamp, simulated, data.rpm, data.speed,
        data.coolant, data.voltage, data.load, data.throttle)
}

object DiagnosticFunctionIds {
    val all = listOf("getConnectionStatus", "connectSavedAdapter", "getCapabilities", "readFaultCodes", "getLatestReport", "readLiveSnapshot", "lookupCode")
        .map { "app.leximo.ecu_vue.ai.BaseDiagnosticAppFunctions#$it" }
}
