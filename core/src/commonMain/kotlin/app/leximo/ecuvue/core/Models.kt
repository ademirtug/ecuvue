package app.leximo.ecuvue.core

import kotlinx.serialization.Serializable
import kotlin.time.Clock

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

@Serializable enum class FaultKind { STORED, PENDING, PERMANENT }
@Serializable enum class Support { UNKNOWN, SUPPORTED, UNSUPPORTED, UNAVAILABLE }
@Serializable enum class ClearOutcome { ACKNOWLEDGED, PARTIAL, REJECTED, UNCONFIRMED }
@Serializable data class ClearResult(val outcome: ClearOutcome, val messageKey: String = "codesCleared", val details: String = "")
@Serializable data class Fault(val code: String, val kind: FaultKind)
@Serializable data class LiveData(
    val timestamp: Long = nowMillis(),
    val rpm: Double? = null, val speed: Double? = null, val coolant: Double? = null,
    val voltage: Double? = null, val load: Double? = null, val throttle: Double? = null,
)
@Serializable data class FreezeFrame(val code: String, val sourceEcu: String, val data: LiveData)
@Serializable data class FaultReport(
    val faults: List<Fault>, val scannedAt: Long = nowMillis(),
    val pendingStatus: Support = Support.UNKNOWN, val permanentStatus: Support = Support.UNKNOWN,
    val freezeFrameStatus: Support = Support.UNKNOWN, val freezeFrame: FreezeFrame? = null,
)
@Serializable data class VehicleIdentity(val vin: String, val manufacturer: String? = null, val make: String? = null) {
    val displayName: String get() = make ?: manufacturer ?: "Vehicle"
}
@Serializable data class Capabilities(
    val adapterIdentity: String? = null, val protocolNumber: Int? = null,
    val livePids: Set<Int> = emptySet(), val pidDiscoveryComplete: Boolean = false,
    val voltage: Support = Support.UNKNOWN, val pendingCodes: Support = Support.UNKNOWN,
    val permanentCodes: Support = Support.UNKNOWN, val freezeFrame: Support = Support.UNKNOWN,
    val unavailableLivePids: Set<Int> = emptySet(),
) {
    val isCan: Boolean get() = protocolNumber in 6..9
    val protocolName: String get() = when (protocolNumber) {
        1 -> "SAE J1850 PWM"; 2 -> "SAE J1850 VPW"; 3 -> "ISO 9141-2"
        4 -> "ISO 14230-4 (5-baud)"; 5 -> "ISO 14230-4 (fast init)"
        6 -> "ISO 15765-4 CAN (11-bit, 500 kbit/s)"; 7 -> "ISO 15765-4 CAN (29-bit, 500 kbit/s)"
        8 -> "ISO 15765-4 CAN (11-bit, 250 kbit/s)"; 9 -> "ISO 15765-4 CAN (29-bit, 250 kbit/s)"
        else -> "Unknown"
    }
}

class DiagnosticException(val key: String, val details: String? = null, cause: Throwable? = null) : Exception(details ?: key, cause)

interface ElmTransport {
    suspend fun connect()
    suspend fun send(command: String): String
    suspend fun disconnect()
}

/** Physical adapters and the explicit simulator implement the same contract. */
interface DiagnosticAdapter {
    val name: String
    val simulated: Boolean get() = false
    val capabilities: Capabilities
    suspend fun connect()
    suspend fun disconnect()
    suspend fun readVehicleIdentity(): VehicleIdentity?
    suspend fun readFaults(): FaultReport
    suspend fun readLiveData(): LiveData
    suspend fun clearFaults(): ClearResult
}

@Serializable enum class ConnectionKind { CLASSIC, BLE, WIFI, USB, SIMULATOR }
data class AdapterDevice(val id: String, val name: String, val kind: ConnectionKind)
@Serializable data class ConnectionRequest(val kind: ConnectionKind, val id: String = "", val name: String = "", val port: Int = 35000, val baud: Int = 38400)
data class LiveSnapshot(val data: LiveData, val simulated: Boolean, val sessionId: String)
