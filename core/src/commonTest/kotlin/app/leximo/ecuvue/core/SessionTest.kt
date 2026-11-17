@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package app.leximo.ecuvue.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class SessionTest {
    @Test fun simulatorScanClearAndPersistence() = runTest {
        val store = MemorySnapshotStore()
        val session = DiagnosticSession(backgroundScope, store)
        session.connect(SimulatedAdapter())
        val before = session.scan()
        assertTrue(before.simulated)
        assertEquals(3, before.report.faults.size)
        assertNotNull(before.report.freezeFrame)
        val roundTrip = SnapshotCodec.decode(SnapshotCodec.encode(before))
        assertEquals(before, roundTrip)
        session.clearFaults()
        assertEquals(listOf(Fault("P0420", FaultKind.PERMANENT)), session.state.value.report?.faults)
        assertNull(session.state.value.report?.freezeFrame)
        session.disconnect()
        assertNull(session.state.value.report)
        val restored = DiagnosticSession(backgroundScope, store)
        assertFalse(restored.state.value.connected)
        assertNotNull(restored.state.value.latestReport)
    }
    @Test fun uiAndAiOperationsNeverInterleave() = runTest {
        val adapter = RecordingAdapter()
        val session = DiagnosticSession(backgroundScope)
        session.connect(adapter)
        val scan = async { session.scan() }
        runCurrent()
        val read = async { session.readLive() }
        scan.await(); read.await()
        assertEquals(listOf("scan:start", "scan:end", "live:start", "live:end"), adapter.events)
        session.disconnect()
    }
    @Test fun pollingPausesInBackgroundButExplicitToolsStillWork() = runTest {
        val adapter = RecordingAdapter()
        val session = DiagnosticSession(backgroundScope)
        session.connect(adapter)
        session.setForeground(false)
        advanceTimeBy(10_000); runCurrent()
        assertTrue(adapter.events.isEmpty())
        val tools = ReadOnlyDiagnosticTools(session, { true }) { error("Not needed") }
        tools.readLiveSnapshot()
        assertEquals(listOf("live:start", "live:end"), adapter.events)
        session.disconnect()
    }
    @Test fun disabledAiCannotAccessCachedOrFreshData() = runTest {
        val adapter = RecordingAdapter()
        val session = DiagnosticSession(backgroundScope)
        session.connect(adapter)
        val tools = ReadOnlyDiagnosticTools(session, { false }) { error("Must not load") }
        assertFailsWith<DiagnosticException> { tools.connectionStatus() }
        assertFailsWith<DiagnosticException> { tools.latestReport() }
        assertFailsWith<DiagnosticException> { tools.readLiveSnapshot() }
        assertFailsWith<DiagnosticException> { tools.readFaultCodes() }
        assertFailsWith<DiagnosticException> { tools.lookupCode("P0301", "en") }
        assertTrue(adapter.events.isEmpty())
        session.disconnect()
    }
    @Test fun clearIsNeverReplayedAndFailedVerificationInvalidatesOldReport() = runTest {
        val adapter = RecordingAdapter()
        val store = MemorySnapshotStore()
        val session = DiagnosticSession(backgroundScope, store)
        session.connect(adapter); session.scan()
        adapter.failScan = true
        val error = assertFailsWith<DiagnosticException> { session.clearFaults() }
        assertEquals("clearVerificationFailed", error.key)
        assertEquals(1, adapter.clears)
        assertNull(session.state.value.report); assertNull(store.read())
        session.disconnect()
    }
    @Test fun rejectedClearPreservesReport() = runTest {
        val adapter = RecordingAdapter()
        val session = DiagnosticSession(backgroundScope)
        session.connect(adapter)
        val before = session.scan()
        adapter.rejectClear = true
        assertFailsWith<DiagnosticException> { session.clearFaults() }
        assertEquals(before.report, session.state.value.report)
        assertEquals(before, session.state.value.latestReport)
        session.disconnect()
    }
    @Test fun waitingCallerGetsExplicitBusyError() = runTest {
        val adapter = RecordingAdapter().apply { scanDelay = 10_000 }
        val session = DiagnosticSession(backgroundScope)
        session.connect(adapter)
        val scan = async { session.scan() }
        runCurrent()
        assertEquals("busy", assertFailsWith<DiagnosticException> { session.readLive() }.key)
        scan.await(); session.disconnect()
    }
    @Test fun revokedAccessIsCheckedAgainAfterWaitingForTheAdapter() = runTest {
        var allowed = true
        val adapter = RecordingAdapter().apply { scanDelay = 1_000 }
        val session = DiagnosticSession(backgroundScope)
        session.connect(adapter)
        val scan = async { session.scan() }
        runCurrent()
        val tools = ReadOnlyDiagnosticTools(session, { allowed }) { error("Not used") }
        val request = async { runCatching { tools.readLiveSnapshot() } }
        runCurrent(); allowed = false
        scan.await()
        assertEquals("aiAccessDisabled", (request.await().exceptionOrNull() as DiagnosticException).key)
        assertFalse("live:start" in adapter.events)
        session.disconnect()
    }
    @Test fun stoppingACancelledOperationClosesTheSessionAndDoesNotReplayCommands() = runTest {
        val adapter = RecordingAdapter().apply { scanDelay = 20_000 }
        val session = DiagnosticSession(backgroundScope)
        session.connect(adapter)
        val request = launch { session.scan() }
        runCurrent()
        session.stop()
        request.join()
        assertFalse(session.state.value.connected)
        assertFalse(session.state.value.busy)
        assertEquals(listOf("scan:start"), adapter.events)
    }
}

private class RecordingAdapter : DiagnosticAdapter {
    override val name = "Test"
    override val capabilities = Capabilities()
    val events = mutableListOf<String>()
    var failScan = false
    var rejectClear = false
    var scanDelay = 100L
    var clears = 0
    override suspend fun connect() {}
    override suspend fun disconnect() {}
    override suspend fun readVehicleIdentity(): VehicleIdentity? = null
    override suspend fun readFaults(): FaultReport {
        events += "scan:start"; delay(scanDelay); events += "scan:end"
        if (failScan) throw DiagnosticException("scanFailed")
        return FaultReport(listOf(Fault("P0301", FaultKind.STORED)))
    }
    override suspend fun readLiveData(): LiveData { events += "live:start"; delay(100); events += "live:end"; return LiveData(rpm = 800.0) }
    override suspend fun clearFaults(): ClearResult {
        clears++
        return ClearResult(if (rejectClear) ClearOutcome.REJECTED else ClearOutcome.ACKNOWLEDGED, if (rejectClear) "clearRejected" else "codesCleared")
    }
}
