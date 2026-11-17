package app.leximo.ecuvue.core

import kotlinx.coroutines.delay
import kotlin.math.sin

class SimulatedAdapter : DiagnosticAdapter {
    override val name = "ECU Vue Simulator"
    override val simulated = true
    private var connected = false
    private var cleared = false
    private var tick = 0
    var driving = false
    override val capabilities = Capabilities("ECU Vue simulated ELM327", 6, setOf(4, 5, 12, 13, 17), true,
        Support.SUPPORTED, Support.SUPPORTED, Support.SUPPORTED, Support.SUPPORTED)
    private fun checkConnected() { if (!connected) throw DiagnosticException("connectionLost") }
    override suspend fun connect() { delay(650); connected = true }
    override suspend fun disconnect() { connected = false }
    override suspend fun readVehicleIdentity(): VehicleIdentity { checkConnected(); return VehicleIdentity("WDD00000000000001", "Mercedes-Benz") }
    override suspend fun readFaults(): FaultReport {
        checkConnected(); delay(850); checkConnected()
        return FaultReport(buildList {
            if (!cleared) { add(Fault("P0301", FaultKind.STORED)); add(Fault("P0171", FaultKind.PENDING)) }
            add(Fault("P0420", FaultKind.PERMANENT))
        }, pendingStatus = Support.SUPPORTED, permanentStatus = Support.SUPPORTED, freezeFrameStatus = Support.SUPPORTED,
            freezeFrame = if (cleared) null else FreezeFrame("P0301", "7E8", LiveData(rpm = 2180.0, speed = 54.0, coolant = 91.0, load = 47.0, throttle = 23.0)))
    }
    override suspend fun clearFaults(): ClearResult { checkConnected(); delay(650); checkConnected(); cleared = true; return ClearResult(ClearOutcome.ACKNOWLEDGED) }
    override suspend fun readLiveData(): LiveData {
        checkConnected(); tick++
        val wave = sin(tick * .35)
        return LiveData(rpm = if (driving) 2300 + wave * 550 else 810 + wave * 35,
            speed = if (driving) 62 + wave * 14 else 0.0, coolant = 88 + sin(tick * .08) * 3,
            voltage = 14.2 + wave * .12, load = if (driving) 43 + wave * 12 else 18 + wave * 2,
            throttle = if (driving) 26 + wave * 8 else 8 + wave)
    }
}
