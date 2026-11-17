package app.leximo.ecuvue.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

@Serializable data class SavedReport(val report: FaultReport, val sessionId: String, val simulated: Boolean,
    val adapterName: String, val capabilities: Capabilities, val vehicle: VehicleIdentity? = null)

interface SnapshotStore {
    fun read(): SavedReport?
    fun save(report: SavedReport?)
}
class MemorySnapshotStore : SnapshotStore {
    private var report: SavedReport? = null
    override fun read() = report
    override fun save(report: SavedReport?) { this.report = report }
}
object SnapshotCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(report: SavedReport): String = json.encodeToString(report)
    fun decode(value: String): SavedReport = json.decodeFromString(value)
}
data class SessionState(
    val connected: Boolean = false, val busy: Boolean = false, val operation: String? = null,
    val sessionId: String = "", val adapterName: String = "", val simulated: Boolean = false,
    val capabilities: Capabilities = Capabilities(), val vehicle: VehicleIdentity? = null,
    val report: FaultReport? = null, val latestReport: SavedReport? = null,
    val live: LiveData? = null, val history: List<LiveData> = emptyList(),
    val errorKey: String? = null, val errorDetails: String? = null, val notice: String? = null,
)

/** One mutex protects entire operations, including framing changes, UI writes and AI reads. */
class DiagnosticSession(private val scope: CoroutineScope, private val snapshots: SnapshotStore = MemorySnapshotStore()) {
    private val mutableState = MutableStateFlow(SessionState(latestReport = snapshots.read()))
    val state: StateFlow<SessionState> = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var adapter: DiagnosticAdapter? = null
    private var poll: Job? = null
    private var foreground = true
    private val activeOperation = MutableStateFlow<Job?>(null)

    private suspend fun <T> operation(name: String, task: suspend () -> T): T {
        // Return a real error if another operation doesn't finish promptly; never report a silent success.
        if (withTimeoutOrNull(5_000) { mutex.lock(); true } != true) throw DiagnosticException("busy")
        mutableState.update { it.copy(busy = true, operation = name, errorKey = null, errorDetails = null, notice = null) }
        activeOperation.value = currentCoroutineContext()[Job]
        try {
            return task()
        } catch (error: Throwable) {
            val diagnostic = when (error) {
                is DiagnosticException -> error
                is TimeoutCancellationException -> DiagnosticException("timeout")
                is CancellationException -> null
                else -> DiagnosticException("unexpectedError", error.message)
            }
            if (diagnostic != null) {
                if (diagnostic.key in setOf("connectionLost", "commandFailed", "timeout", "permissionDenied")) {
                    withContext(NonCancellable) { closeCurrent() }
                }
                mutableState.update { it.copy(errorKey = diagnostic.key, errorDetails = diagnostic.details?.take(600)) }
                throw diagnostic
            }
            withContext(NonCancellable) { closeCurrent() }
            throw error
        } finally {
            mutableState.update { it.copy(busy = false, operation = null, capabilities = adapter?.capabilities ?: Capabilities()) }
            activeOperation.value = null
            mutex.unlock()
        }
    }
    private fun current(): DiagnosticAdapter = adapter?.takeIf { state.value.connected } ?: throw DiagnosticException("connectionLost")
    private suspend fun closeCurrent() {
        poll?.cancel(); poll = null
        val old = adapter; adapter = null
        mutableState.update { SessionState(latestReport = it.latestReport, busy = it.busy, operation = it.operation) }
        runCatching { old?.disconnect() }
    }
    suspend fun connect(selected: DiagnosticAdapter, authorize: () -> Unit = {}) = operation("connecting") {
        authorize()
        closeCurrent()
        adapter = selected
        try {
            withTimeout(120_000) { selected.connect() }
            authorize()
            mutableState.update { it.copy(connected = true, adapterName = selected.name, simulated = selected.simulated,
                sessionId = Random.nextLong().toString(16) + Random.nextLong().toString(16), capabilities = selected.capabilities,
                busy = true, operation = "connecting") }
            // Identify once before enabling polling. No model/year is guessed from the VIN.
            val vehicle = selected.readVehicleIdentity()
            authorize()
            mutableState.update { it.copy(vehicle = vehicle) }
            poll = scope.launch {
                while (isActive) {
                    delay(2_000)
                    if (foreground && mutex.tryLock()) {
                        try { sampleLocked() } catch (error: CancellationException) { throw error }
                        catch (error: Throwable) {
                            val key = (error as? DiagnosticException)?.key ?: "unexpectedError"
                            // Cleanup cannot cancel its own coroutine halfway through closing the transport.
                            withContext(NonCancellable) { closeCurrent() }
                            mutableState.update { it.copy(errorKey = key, errorDetails = error.message?.take(600)) }
                        } finally { mutex.unlock() }
                    }
                }
            }
            Unit
        } catch (error: Throwable) {
            withContext(NonCancellable) { closeCurrent() }
            throw error
        }
    }
    suspend fun disconnect() = operation("disconnecting") { closeCurrent() }
    /** User-initiated stop closes I/O first to unblock a pending OS read. Never exposed to AI. */
    suspend fun stop() {
        activeOperation.value?.cancel()
        withContext(NonCancellable) { runCatching { adapter?.disconnect() } }
        operation("disconnecting") { closeCurrent() }
    }
    suspend fun scan(authorize: () -> Unit = {}): SavedReport = operation("scanning") {
        authorize()
        val report = withTimeout(120_000) { current().readFaults() }
        val state = state.value
        val saved = SavedReport(report, state.sessionId, state.simulated, state.adapterName, current().capabilities, state.vehicle)
        snapshots.save(saved)
        mutableState.update { it.copy(report = report, latestReport = saved) }
        saved
    }
    suspend fun readLive(): LiveData = operation("reading") { withTimeout(120_000) { sampleLocked() } }
    suspend fun readLiveSnapshot(authorize: () -> Unit = {}): LiveSnapshot = operation("reading") {
        authorize()
        val data = withTimeout(120_000) { sampleLocked() }
        LiveSnapshot(data, state.value.simulated, state.value.sessionId)
    }
    private suspend fun sampleLocked(): LiveData {
        val data = current().readLiveData()
        mutableState.update { it.copy(live = data, history = (it.history + data).takeLast(45), capabilities = current().capabilities) }
        return data
    }
    /** UI-only action. This method is deliberately absent from the read-only tools facade. */
    suspend fun clearFaults(): ClearResult = operation("clearing") {
        val selected = current()
        val previous = state.value
        snapshots.save(null)
        mutableState.update { it.copy(report = null, latestReport = null) }
        val result = selected.clearFaults()
        if (result.outcome == ClearOutcome.REJECTED) {
            snapshots.save(previous.latestReport)
            mutableState.update { it.copy(report = previous.report, latestReport = previous.latestReport) }
            throw DiagnosticException(result.messageKey, result.details)
        }
        try {
            val report = selected.readFaults()
            val s = state.value
            val saved = SavedReport(report, s.sessionId, s.simulated, s.adapterName, selected.capabilities, s.vehicle)
            snapshots.save(saved)
            mutableState.update { it.copy(report = report, latestReport = saved,
                notice = if (result.outcome == ClearOutcome.ACKNOWLEDGED && report.faults.any { f -> f.kind != FaultKind.PERMANENT }) "resetAcceptedCodesRemain" else result.messageKey,
                errorDetails = result.details) }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if ((error as? DiagnosticException)?.key in setOf("connectionLost", "commandFailed", "timeout")) {
                withContext(NonCancellable) { closeCurrent() }
            }
            throw DiagnosticException(if (result.outcome == ClearOutcome.ACKNOWLEDGED) "clearVerificationFailed" else result.messageKey,
                result.details + "; verification: " + error.message, error)
        }
        result
    }
    fun setForeground(value: Boolean) { foreground = value }
    suspend fun setDriving(value: Boolean) = operation("reading") { (current() as? SimulatedAdapter)?.driving = value; sampleLocked(); Unit }
    fun dismissMessage() { mutableState.update { it.copy(errorKey = null, errorDetails = null, notice = null) } }
}

/** Reusable by native AppFunctions and a future authenticated MCP relay. No raw commands or writes. */
class ReadOnlyDiagnosticTools(
    private val session: DiagnosticSession, private val allowed: () -> Boolean,
    private val reference: suspend () -> CodeReference,
) {
    private fun authorize() { if (!allowed()) throw DiagnosticException("aiAccessDisabled", "Enable AI access in ECU Vue settings first.") }
    fun connectionStatus(): SessionState { authorize(); return session.state.value }
    fun latestReport(): SavedReport { authorize(); return session.state.value.latestReport ?: throw DiagnosticException("noScan") }
    suspend fun readFaultCodes(): SavedReport { authorize(); val report = session.scan(::authorize); authorize(); return report }
    suspend fun readLiveSnapshot(): LiveSnapshot { authorize(); val result = session.readLiveSnapshot(::authorize); authorize(); return result }
    suspend fun lookupCode(code: String, language: String): ReferenceMatch? {
        authorize()
        if (!Regex("^[PCBU][0-3][0-9A-F]{3}$").matches(code.trim().uppercase())) throw DiagnosticException("invalidCode")
        return reference().lookup(code, language)
    }
}
