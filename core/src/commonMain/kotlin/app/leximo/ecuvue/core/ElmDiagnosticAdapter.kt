package app.leximo.ecuvue.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class ElmDiagnosticAdapter(
    private val transport: ElmTransport, override val name: String,
    private val identify: (String) -> VehicleIdentity = { VehicleIdentity(it) },
) : DiagnosticAdapter {
    private var connected = false
    private var protocol: Int? = null
    private var identity: String? = null
    private val supported = mutableSetOf<Int>()
    private val unavailable = mutableSetOf<Int>()
    private val missing = mutableMapOf<Int, Int>()
    private var discoveryComplete = false
    private var initialVoltage: Double? = null
    private var voltage = Support.UNKNOWN
    private var pending = Support.UNKNOWN
    private var permanent = Support.UNKNOWN
    private var freeze = Support.UNKNOWN
    override val capabilities get() = Capabilities(identity, protocol, supported.toSet(), discoveryComplete,
        voltage, pending, permanent, freeze, unavailable.toSet())

    private suspend fun command(value: String): String {
        if (!connected) throw DiagnosticException("connectionLost")
        return transport.send(value)
    }
    private fun ok(value: String) = value.uppercase().split(Regex("[\r\n]+")).any { it.trim() == "OK" }
    private fun parseVoltage(value: String) = Regex("^\\s*(\\d+(?:\\.\\d+)?)\\s*V\\s*$", RegexOption.IGNORE_CASE)
        .matchEntire(value)?.groupValues?.get(1)?.toDoubleOrNull()

    override suspend fun connect() {
        supported.clear(); unavailable.clear(); missing.clear()
        protocol = null; identity = null; discoveryComplete = false; initialVoltage = null
        voltage = Support.UNKNOWN; pending = Support.UNKNOWN; permanent = Support.UNKNOWN; freeze = Support.UNKNOWN
        try {
            transport.connect(); connected = true
            val reset = command("ATZ")
            for (value in listOf("ATE0", "ATL0", "ATS1", "ATH0", "ATSP0")) {
                if (!ok(command(value))) throw DiagnosticException("adapterSetupFailed")
            }
            supported += ElmParser.supportedPids(command("0100"), 0x41, 0) ?: throw DiagnosticException("vehicleUnavailable")
            protocol = Regex("^A?([1-9])$").matchEntire(command("ATDPN").trim().uppercase())?.groupValues?.get(1)?.toInt()
                ?: throw DiagnosticException("adapterSetupFailed")
            discoveryComplete = true
            var base = 0x20
            while (base <= 0xE0 && base in supported) {
                val page = ElmParser.supportedPids(command("01${base.hex2()}"), 0x41, base)
                if (page == null) { discoveryComplete = false; break }
                supported += page; base += 0x20
            }
            val reported = command("ATI").trim().takeUnless { it.isEmpty() || '?' in it || it.uppercase() == "OK" } ?: reset
            identity = reported.split(Regex("[\r\n]+")).filter { it.isNotBlank() && it.trim().uppercase() != "ATZ" }
                .joinToString(" ").replace(Regex("[\\x00-\\x1F]"), "").trim().take(120)
            val response = command("ATRV")
            initialVoltage = parseVoltage(response)
            voltage = when { initialVoltage != null -> Support.SUPPORTED; '?' in response -> Support.UNSUPPORTED; else -> Support.UNAVAILABLE }
        } catch (error: Throwable) {
            withContext(NonCancellable) { runCatching { disconnect() } }
            throw error
        }
    }
    override suspend fun disconnect() { connected = false; transport.disconnect() }
    override suspend fun readVehicleIdentity(): VehicleIdentity? {
        if (!ok(command("ATH1"))) return null
        val vin = try { VinParser.parse(command("0902"), protocol!!) } finally { restoreHeaders() }
        return vin?.let(identify)
    }
    private suspend fun restoreHeaders() = withContext(NonCancellable) {
        try {
            if (!ok(command("ATH0"))) throw DiagnosticException("connectionLost", "Could not restore adapter framing")
        } catch (error: Throwable) { runCatching { disconnect() }; throw error }
    }
    private fun modeStatus(response: String, mode: Int): Support = when {
        ElmParser.payloads(response, mode + 0x40).isNotEmpty() -> Support.SUPPORTED
        response.trim() == "?" || ElmParser.payloads(response, 0x7F).any { it.size >= 2 && it[0] == mode && it[1] in listOf(0x11, 0x12) } -> Support.UNSUPPORTED
        else -> Support.UNAVAILABLE
    }
    override suspend fun readFaults(): FaultReport {
        val stored = command("03")
        if (modeStatus(stored, 3) != Support.SUPPORTED) throw DiagnosticException("scanFailed")
        val pendingReply = if (pending == Support.UNSUPPORTED) "" else command("07")
        val permanentReply = if (permanent == Support.UNSUPPORTED) "" else command("0A")
        if (pending != Support.UNSUPPORTED) pending = modeStatus(pendingReply, 7)
        if (permanent != Support.UNSUPPORTED) permanent = modeStatus(permanentReply, 10)
        val faults = ElmParser.faults(stored, 0x43, FaultKind.STORED, capabilities.isCan) +
            ElmParser.faults(pendingReply, 0x47, FaultKind.PENDING, capabilities.isCan) +
            ElmParser.faults(permanentReply, 0x4A, FaultKind.PERMANENT, capabilities.isCan)
        val snapshot = if (freeze == Support.UNSUPPORTED) null else readFreezeFrame()
        return FaultReport(faults, pendingStatus = pending, permanentStatus = permanent, freezeFrameStatus = freeze, freezeFrame = snapshot)
    }
    private suspend fun readFreezeFrame(): FreezeFrame? {
        if (!ok(command("ATH1"))) { freeze = Support.UNAVAILABLE; return null }
        try {
            val supportResponse = command("020000")
            val responses = ElmParser.addressedPayloads(supportResponse, 0x42, protocol!!)
            if (responses.isEmpty()) {
                val negatives = ElmParser.addressedPayloads(supportResponse, 0x7F, protocol!!).values
                freeze = if (supportResponse.trim() == "?" || negatives.any { it.size >= 2 && it[0] == 2 && it[1] in listOf(0x11, 0x12) }) Support.UNSUPPORTED else Support.UNAVAILABLE
                return null
            }
            val masks = responses.mapNotNull { (ecu, payload) -> ElmParser.supportedPidPayloads(listOf(payload), 0, 0)?.let { ecu to it } }.toMap()
            if (masks.isEmpty()) { freeze = Support.UNAVAILABLE; return null }
            freeze = Support.SUPPORTED
            if (masks.values.none { 2 in it }) return null
            val codes = ElmParser.addressedPayloads(command("020200"), 0x42, protocol!!)
            val association = masks.keys.sorted().firstNotNullOfOrNull { ecu ->
                val bytes = codes[ecu]
                if (2 in masks.getValue(ecu) && bytes != null && bytes.size >= 4 && bytes[0] == 2 && bytes[1] == 0)
                    ElmParser.dtc(bytes[2], bytes[3])?.let { ecu to it } else null
            } ?: return null
            val (ecu, code) = association
            suspend fun sensor(pid: Int, length: Int): List<Int>? {
                if (pid !in masks.getValue(ecu)) return null
                val data = ElmParser.addressedPayloads(command("02${pid.hex2()}00"), 0x42, protocol!!)[ecu]
                return if (data != null && data.size >= length + 2 && data[0] == pid && data[1] == 0) data.subList(2, 2 + length) else null
            }
            val rpm = sensor(0x0C, 2); val speed = sensor(0x0D, 1); val coolant = sensor(5, 1)
            val load = sensor(4, 1); val throttle = sensor(0x11, 1)
            return FreezeFrame(code, ecu, readings(rpm, speed, coolant, load, throttle))
        } finally { restoreHeaders() }
    }
    override suspend fun clearFaults() = ElmParser.parseClearResponse(command("04"), protocol)
    private suspend fun pid(pid: Int, length: Int): List<Int>? {
        if (pid !in supported || pid in unavailable) return null
        val response = command("01${pid.hex2()}")
        val data = ElmParser.pid(response, pid)
        if (data != null && data.size >= length) { missing.remove(pid); return data }
        missing[pid] = (missing[pid] ?: 0) + 1
        if (missing.getValue(pid) >= 3 || modeStatus(response, 1) == Support.UNSUPPORTED) unavailable += pid
        return null
    }
    override suspend fun readLiveData(): LiveData {
        if (!connected) throw DiagnosticException("connectionLost")
        val rpm = pid(0x0C, 2); val speed = pid(0x0D, 1); val coolant = pid(5, 1)
        val load = pid(4, 1); val throttle = pid(0x11, 1)
        var volts = initialVoltage; initialVoltage = null
        if (voltage == Support.SUPPORTED && volts == null) volts = parseVoltage(command("ATRV"))
        return readings(rpm, speed, coolant, load, throttle).copy(voltage = volts)
    }
    private fun readings(rpm: List<Int>?, speed: List<Int>?, coolant: List<Int>?, load: List<Int>?, throttle: List<Int>?) = LiveData(
        rpm = rpm?.let { (it[0] * 256 + it[1]) / 4.0 }, speed = speed?.first()?.toDouble(),
        coolant = coolant?.first()?.minus(40.0), load = load?.first()?.times(100.0 / 255), throttle = throttle?.first()?.times(100.0 / 255))
}
