package app.leximo.ecuvue.presentation

import app.leximo.ecuvue.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ConnectionDiscoveryState(
    val adapters: List<AdapterDevice> = emptyList(),
    val pairedLoading: Boolean = false, val searching: Boolean = false, val searched: Boolean = false,
    val bluetoothError: DiagnosticException? = null,
    val usbDevices: List<AdapterDevice> = emptyList(), val usbLoading: Boolean = false,
    val usbError: DiagnosticException? = null,
)

/** Screen-owned discovery state. It knows no Android APIs, widgets, preferences or ECU commands. */
class ConnectionPresenter(private val discovery: AdapterDiscovery, parentScope: CoroutineScope) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val mutableState = MutableStateFlow(ConnectionDiscoveryState())
    val state = mutableState.asStateFlow()
    private var initialized = false
    private var searchJob: Job? = null
    private var paired = emptyList<AdapterDevice>()
    private var ble = emptyList<AdapterDevice>()

    fun loadPaired() {
        if (initialized) return
        initialized = true
        if (ConnectionKind.CLASSIC !in discovery.connections) return
        mutableState.update { it.copy(pairedLoading = true) }
        scope.launch {
            try { paired = discovery.pairedDevices(); publishAdapters() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.update { it.copy(bluetoothError = diagnostic(error)) } }
            finally { mutableState.update { it.copy(pairedLoading = false) } }
        }
    }
    fun findAdapters() {
        if (state.value.searching || state.value.pairedLoading) return
        mutableState.update { it.copy(searching = true, searched = false, bluetoothError = null) }
        ble = emptyList()
        publishAdapters()
        searchJob = scope.launch {
            try {
                if (ConnectionKind.CLASSIC in discovery.connections) { paired = discovery.pairedDevices(); publishAdapters() }
                if (ConnectionKind.BLE in discovery.connections) { ble = discovery.bleDevices(); publishAdapters() }
                mutableState.update { it.copy(searched = true) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.update { it.copy(bluetoothError = diagnostic(error)) } }
            finally { mutableState.update { it.copy(searching = false) } }
        }
    }
    fun stopDiscovery() { searchJob?.cancel(); discovery.stopDiscovery() }
    fun dismissBluetoothError() { mutableState.update { it.copy(bluetoothError = null) } }
    fun dismissUsbError() { mutableState.update { it.copy(usbError = null) } }
    fun refreshUsb() {
        if (state.value.usbLoading) return
        mutableState.update { it.copy(usbLoading = true, usbError = null) }
        scope.launch {
            try { val devices = discovery.usbDevices(); mutableState.update { it.copy(usbDevices = devices) } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.update { it.copy(usbError = diagnostic(error)) } }
            finally { mutableState.update { it.copy(usbLoading = false) } }
        }
    }
    fun close() { stopDiscovery(); scope.cancel() }
    private fun publishAdapters() {
        val adapters = (paired + ble).distinctBy { it.kind to it.id }.sortedWith(compareBy({ it.name.lowercase() }, { it.kind.name }))
        mutableState.update { it.copy(adapters = adapters) }
    }
    private fun diagnostic(error: Exception) = error as? DiagnosticException ?: DiagnosticException("unexpectedError", error.message)
}
