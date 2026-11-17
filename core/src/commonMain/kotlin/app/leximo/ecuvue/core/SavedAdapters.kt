package app.leximo.ecuvue.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Local device details are never returned to an assistant. USB IDs alone are not stable identities. */
@Serializable data class SavedAdapter(
    val request: ConnectionRequest,
    val assistantReconnectAllowed: Boolean = false,
    val usbVendorId: Int? = null,
    val usbProductId: Int? = null,
    val usbSerialNumber: String? = null,
) {
    val reconnectSupported: Boolean get() = when (request.kind) {
        ConnectionKind.CLASSIC, ConnectionKind.BLE, ConnectionKind.WIFI -> true
        ConnectionKind.USB -> usbVendorId != null && usbProductId != null && !usbSerialNumber.isNullOrBlank()
        ConnectionKind.SIMULATOR -> false
    }
    fun sameDevice(other: SavedAdapter): Boolean = request.kind == other.request.kind && when (request.kind) {
        ConnectionKind.USB -> reconnectSupported && other.reconnectSupported && usbVendorId == other.usbVendorId &&
            usbProductId == other.usbProductId && usbSerialNumber == other.usbSerialNumber
        else -> request.id == other.request.id && request.port == other.request.port && request.baud == other.request.baud
    }
}

interface SavedAdapterRepository {
    val savedAdapter: StateFlow<SavedAdapter?>
    suspend fun remember(adapter: SavedAdapter)
    suspend fun allowAssistantReconnect(allowed: Boolean)
    suspend fun forget()
}

/** Serialize persistence and publish only after a successful write. Changing devices revokes approval. */
open class StoredSavedAdapterRepository(initial: SavedAdapter? = null) : SavedAdapterRepository {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(initial?.takeUnless { it.request.kind == ConnectionKind.SIMULATOR })
    override val savedAdapter = mutable.asStateFlow()
    protected open suspend fun persist(value: SavedAdapter?) {}
    override suspend fun remember(adapter: SavedAdapter) = mutex.withLock {
        if (adapter.request.kind == ConnectionKind.SIMULATOR) return@withLock
        val previous = mutable.value
        val value = adapter.copy(assistantReconnectAllowed = adapter.reconnectSupported &&
            previous?.sameDevice(adapter) == true && previous.assistantReconnectAllowed)
        persist(value)
        mutable.value = value
    }
    override suspend fun allowAssistantReconnect(allowed: Boolean) = mutex.withLock {
        val previous = mutable.value ?: throw DiagnosticException("noSavedAdapter")
        if (allowed && !previous.reconnectSupported) throw DiagnosticException("savedAdapterUnsupported")
        val value = previous.copy(assistantReconnectAllowed = allowed)
        persist(value)
        mutable.value = value
    }
    override suspend fun forget() = mutex.withLock { persist(null); mutable.value = null }
}

object SavedAdapterCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(value: SavedAdapter): String = json.encodeToString(value)
    fun decode(value: String): SavedAdapter = json.decodeFromString(value)
}

/** Only connects a locally selected, approved device; accepts no address or command from an agent. */
class AssistantConnectionTools(
    private val session: DiagnosticSession,
    private val saved: SavedAdapterRepository,
    private val controller: ConnectionController,
    private val allowed: () -> Boolean,
) {
    private val mutex = Mutex()
    private fun authorize() { if (!allowed()) throw DiagnosticException("aiAccessDisabled") }
    suspend fun connectSavedAdapter(): SessionState = mutex.withLock {
        authorize()
        if (session.state.value.connected) return@withLock session.state.value
        val selected = saved.savedAdapter.value ?: throw DiagnosticException("noSavedAdapter")
        fun checkApproval() {
            authorize()
            val current = saved.savedAdapter.value
            if (current == null || !current.sameDevice(selected) || !current.assistantReconnectAllowed)
                throw DiagnosticException("adapterReconnectNotAllowed")
            if (!current.reconnectSupported) throw DiagnosticException("savedAdapterUnsupported")
        }
        if (!selected.reconnectSupported) throw DiagnosticException("savedAdapterUnsupported")
        checkApproval()
        controller.connectForAssistant(selected, ::checkApproval)
        authorize()
        session.state.value
    }
}
