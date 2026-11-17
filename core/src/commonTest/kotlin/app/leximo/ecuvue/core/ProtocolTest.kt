package app.leximo.ecuvue.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ProtocolTest {
    @Test fun dtcFamiliesAndCanCounts() {
        assertEquals(listOf("P0301", "C0123", "B0123", "U0123"), listOf(0x03 to 1, 0x41 to 0x23, 0x81 to 0x23, 0xC1 to 0x23).map { ElmParser.dtc(it.first, it.second) })
        assertEquals(listOf(Fault("P0301", FaultKind.STORED)), ElmParser.faults("43 01 03 01 00 00", 0x43, FaultKind.STORED, true))
        assertFailsWith<DiagnosticException> { ElmParser.faults("43 02 03 01", 0x43, FaultKind.STORED, true) }
        assertFailsWith<DiagnosticException> { ElmParser.faults("43 03", 0x43, FaultKind.STORED) }
        assertEquals(1, ElmParser.faults("43 03 01\r43 03 01", 0x43, FaultKind.STORED).size)
    }
    @Test fun numberedRepliesMustBeConsecutive() {
        assertEquals(listOf(listOf(1, 3, 1)), ElmParser.payloads("0: 43 01\r1: 03 01", 0x43))
        assertFailsWith<DiagnosticException> { ElmParser.payloads("0: 43 01\r2: 03 01", 0x43) }
    }
    @Test fun freezeFramesKeepEcuAndRejectFragmentsOrDuplicates() {
        val response = "7E8 06 42 0C 00 22 10 00\r7E9 06 42 0C 00 33 20 00"
        assertEquals(listOf(12, 0, 0x22, 0x10, 0), ElmParser.addressedPayloads(response, 0x42, 6)["7E8"])
        assertTrue(ElmParser.addressedPayloads("7E8 10 14 42 00 00 00 00 00", 0x42, 6).isEmpty())
        assertTrue(ElmParser.addressedPayloads("7E8 04 42 05 00 70\r7E8 04 42 05 00 71", 0x42, 6).isEmpty())
        assertEquals(listOf(5, 0, 100), ElmParser.addressedPayloads("18DAF110 04 42 05 00 64", 0x42, 7)["18DAF110"])
    }
    @Test fun clearAcknowledgementsAreNotGuessed() {
        assertEquals(ClearOutcome.ACKNOWLEDGED, ElmParser.parseClearResponse("7E8 01 44", 6).outcome)
        assertEquals(ClearOutcome.ACKNOWLEDGED, ElmParser.parseClearResponse("01 44", 6).outcome)
        assertEquals(ClearOutcome.PARTIAL, ElmParser.parseClearResponse("44\r7F 04 22", 6).outcome)
        assertEquals(ClearOutcome.REJECTED, ElmParser.parseClearResponse("7F 04 33", 6).outcome)
        assertEquals("clearAccessDenied", ElmParser.parseClearResponse("7F 04 33", 6).messageKey)
        assertEquals(ClearOutcome.UNCONFIRMED, ElmParser.parseClearResponse("OK", 6).outcome)
        assertEquals("clearPending", ElmParser.parseClearResponse("7F 04 78", 6).messageKey)
    }
    @Test fun vinReassemblyRejectsMissingOrConflictingFrames() {
        val vin = "WDD00000000000001"
        val bytes = (listOf(0x49, 2, 1) + vin.map(Char::code))
        val first = "7E8 10 14 " + bytes.take(6).joinToString(" ") { it.hex2() }
        val second = "7E8 21 " + bytes.drop(6).take(7).joinToString(" ") { it.hex2() }
        val third = "7E8 22 " + bytes.drop(13).take(7).joinToString(" ") { it.hex2() }
        assertEquals(vin, VinParser.parse("$first\r$second\r$third", 6))
        assertNull(VinParser.parse("$first\r$third", 6))
        assertNull(VinParser.parse("$first\r$third\r$second", 6))
        val direct = bytes.joinToString("") { it.hex2() }
        assertEquals(vin, VinParser.parse(direct, 6))
        assertNull(VinParser.parse(direct + "\r" + direct.dropLast(2) + "32", 6))
    }
    @Test fun handshakeAndSupportedSensorPolling() = runTest {
        val transport = FakeTransport { command -> when (command) {
            "ATZ" -> "ELM327 v1.5"; "ATE0", "ATL0", "ATS1", "ATH0", "ATSP0", "ATH1" -> "OK"
            "0100" -> "41 00 00 10 00 00" // RPM only
            "ATDPN" -> "A6"; "ATI" -> "ELM327"; "ATRV" -> "?"; "0902" -> "NO DATA"
            "010C" -> "41 0C 0C A0"; "03" -> "43 01 03 01"; "07" -> "7F 07 11"; "0A" -> "NO DATA"
            "020000" -> "?"; else -> error("Unexpected command $command")
        } }
        val adapter = ElmDiagnosticAdapter(transport, "Test")
        adapter.connect()
        assertEquals(6, adapter.capabilities.protocolNumber)
        assertEquals(setOf(12), adapter.capabilities.livePids)
        val data = adapter.readLiveData()
        assertEquals(808.0, data.rpm); assertNull(data.speed); assertNull(data.voltage)
        val first = adapter.readFaults()
        assertEquals(Support.UNSUPPORTED, first.pendingStatus)
        assertEquals(Support.UNAVAILABLE, first.permanentStatus)
        assertEquals("P0301", first.faults.single().code)
        adapter.readFaults()
        assertEquals(1, transport.commands.count { it == "07" })
        assertEquals(2, transport.commands.count { it == "0A" })
        assertEquals(1, transport.commands.count { it == "020000" })
        adapter.disconnect()
        assertFailsWith<DiagnosticException> { adapter.readLiveData() }
    }
    @Test fun capabilityContinuationPreservesPartialDiscoveryAndStopsMissingSensors() = runTest {
        val transport = FakeTransport { command -> when (command) {
            "ATZ" -> "ELM"; "ATE0", "ATL0", "ATS1", "ATH0", "ATSP0" -> "OK"
            "0100" -> "41 00 00 10 00 01"; "0120" -> "NO DATA"; "ATDPN" -> "6"; "ATI" -> "ELM"; "ATRV" -> "?"
            "010C" -> "NO DATA"; else -> error(command)
        } }
        val adapter = ElmDiagnosticAdapter(transport, "Test")
        adapter.connect()
        assertFalse(adapter.capabilities.pidDiscoveryComplete)
        repeat(4) { adapter.readLiveData() }
        assertEquals(3, transport.commands.count { it == "010C" })
        assertTrue(12 in adapter.capabilities.unavailableLivePids)
        assertFalse("0140" in transport.commands)
    }
    @Test fun failedHeaderRestorationDisconnects() = runTest {
        var vinRequested = false
        val transport = FakeTransport { command -> when (command) {
            "ATZ", "ATI" -> "ELM"; "ATE0", "ATL0", "ATS1", "ATSP0", "ATH1" -> "OK"
            "ATH0" -> if (vinRequested) "?" else "OK"
            "0100" -> "41 00 00 10 00 00"; "ATDPN" -> "6"; "ATRV" -> "?"
            "0902" -> { vinRequested = true; "NO DATA" }; else -> error(command)
        } }
        val adapter = ElmDiagnosticAdapter(transport, "Test")
        adapter.connect()
        assertFailsWith<DiagnosticException> { adapter.readVehicleIdentity() }
        assertFalse(transport.connected)
    }
    @Test fun freezeFrameValuesNeverComeFromAnotherEcu() = runTest {
        val replies = mapOf(
            "ATZ" to "ELM327", "ATI" to "ELM327", "ATDPN" to "A6", "ATRV" to "?",
            "0100" to "41 00 08 10 00 00", "03" to "43 01 03 01", "07" to "?", "0A" to "?",
            "020000" to "7E8 07 42 00 00 58 18 80 00\r7E9 07 42 00 00 58 18 80 00",
            "020200" to "7E8 05 42 02 00 03 01\r7E9 05 42 02 00 04 20",
            "020C00" to "7E9 05 42 0C 00 FF FF\r7E8 05 42 0C 00 1A F8",
            "020D00" to "7E9 04 42 0D 00 FF",
            "020500" to "7E8 04 42 05 00 7B", "020400" to "7E8 04 42 04 00 80", "021100" to "7E8 04 42 11 00 40",
        )
        val transport = FakeTransport { replies[it] ?: "OK" }
        val adapter = ElmDiagnosticAdapter(transport, "Test")
        adapter.connect()
        val frame = adapter.readFaults().freezeFrame!!
        assertEquals("7E8", frame.sourceEcu); assertEquals("P0301", frame.code)
        assertEquals(1726.0, frame.data.rpm); assertEquals(83.0, frame.data.coolant)
        assertNull(frame.data.speed)
        assertEquals("ATH0", transport.commands.last())
        adapter.disconnect()
    }
}

internal class FakeTransport(val reply: (String) -> String) : ElmTransport {
    var connected = false
    val commands = mutableListOf<String>()
    override suspend fun connect() { connected = true }
    override suspend fun disconnect() { connected = false }
    override suspend fun send(command: String): String { check(connected); commands += command; return reply(command) }
}
