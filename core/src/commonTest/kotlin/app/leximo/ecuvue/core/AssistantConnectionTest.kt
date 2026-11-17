@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package app.leximo.ecuvue.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class AssistantConnectionTest {
    private val first = SavedAdapter(ConnectionRequest(ConnectionKind.CLASSIC, "AA:BB:CC:DD:EE:01", "Car adapter"))

    @Test fun changingAdaptersRevokesConsentButRenamingDoesNot() = runTest {
        val repository = StoredSavedAdapterRepository()
        repository.remember(first)
        assertFalse(repository.savedAdapter.value!!.assistantReconnectAllowed)
        repository.allowAssistantReconnect(true)
        repository.remember(first.copy(request = first.request.copy(name = "Renamed adapter")))
        assertTrue(repository.savedAdapter.value!!.assistantReconnectAllowed)
        repository.remember(first.copy(request = first.request.copy(id = "AA:BB:CC:DD:EE:02")))
        assertFalse(repository.savedAdapter.value!!.assistantReconnectAllowed)
        repository.forget()
        assertNull(repository.savedAdapter.value)
    }

    @Test fun simulatorNeverReplacesThePhysicalProfileAndUsbNeedsStableIdentity() = runTest {
        val repository = StoredSavedAdapterRepository()
        repository.remember(first)
        repository.remember(SavedAdapter(ConnectionRequest(ConnectionKind.SIMULATOR)))
        assertEquals(first, repository.savedAdapter.value)
        val usb = SavedAdapter(ConnectionRequest(ConnectionKind.USB, "12", "USB"))
        repository.remember(usb)
        assertEquals("savedAdapterUnsupported", assertFailsWith<DiagnosticException> { repository.allowAssistantReconnect(true) }.key)
        val stable = usb.copy(usbVendorId = 1, usbProductId = 2, usbSerialNumber = "unit1")
        repository.remember(stable)
        repository.allowAssistantReconnect(true)
        repository.remember(stable.copy(request = stable.request.copy(id = "37")))
        assertTrue(repository.savedAdapter.value!!.assistantReconnectAllowed)
        assertEquals(repository.savedAdapter.value, SavedAdapterCodec.decode(SavedAdapterCodec.encode(repository.savedAdapter.value!!)))
    }

    @Test fun persistenceFailureDoesNotPublishApprovalOrForgetTheProfile() = runTest {
        val repository = object : StoredSavedAdapterRepository(first) {
            override suspend fun persist(value: SavedAdapter?) { throw DiagnosticException("unexpectedError") }
        }
        assertFailsWith<DiagnosticException> { repository.allowAssistantReconnect(true) }
        assertFailsWith<DiagnosticException> { repository.forget() }
        assertEquals(first, repository.savedAdapter.value)
    }

    @Test fun disabledMissingAndUnapprovedRequestsNeverReachTheController() = runTest {
        val repository = StoredSavedAdapterRepository()
        val session = DiagnosticSession(backgroundScope)
        var enabled = false
        val controller = object : ConnectionController {
            override suspend fun connect(request: ConnectionRequest) { fail("Must not connect") }
            override suspend fun connectForAssistant(adapter: SavedAdapter, authorize: () -> Unit) { fail("Must not connect") }
        }
        val tools = AssistantConnectionTools(session, repository, controller) { enabled }
        assertEquals("aiAccessDisabled", assertFailsWith<DiagnosticException> { tools.connectSavedAdapter() }.key)
        enabled = true
        assertEquals("noSavedAdapter", assertFailsWith<DiagnosticException> { tools.connectSavedAdapter() }.key)
        repository.remember(first)
        assertEquals("adapterReconnectNotAllowed", assertFailsWith<DiagnosticException> { tools.connectSavedAdapter() }.key)
    }

    @Test fun simultaneousRequestsReuseOneConnectionAndDoNotSwitchAnExistingSession() = runTest {
        val repository = StoredSavedAdapterRepository()
        repository.remember(first)
        repository.allowAssistantReconnect(true)
        val session = DiagnosticSession(backgroundScope)
        var connections = 0
        val controller = object : ConnectionController {
            override suspend fun connect(request: ConnectionRequest) { fail("Use assistant path") }
            override suspend fun connectForAssistant(adapter: SavedAdapter, authorize: () -> Unit) {
                connections++
                delay(100)
                session.connect(SimulatedAdapter(), authorize)
            }
        }
        val tools = AssistantConnectionTools(session, repository, controller) { true }
        val a = async { tools.connectSavedAdapter() }
        val b = async { tools.connectSavedAdapter() }
        assertEquals(a.await().sessionId, b.await().sessionId)
        assertEquals(1, connections)
        repository.forget()
        assertTrue(tools.connectSavedAdapter().simulated)
        assertEquals(1, connections)
    }

    @Test fun revokingConsentDuringHandshakeClosesTheNewConnection() = runTest {
        val repository = StoredSavedAdapterRepository()
        repository.remember(first)
        repository.allowAssistantReconnect(true)
        val session = DiagnosticSession(backgroundScope)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var disconnected = false
        val adapter = object : DiagnosticAdapter by SimulatedAdapter() {
            override suspend fun connect() { started.complete(Unit); finish.await() }
            override suspend fun disconnect() { disconnected = true }
        }
        val actualController = object : ConnectionController {
            override suspend fun connect(request: ConnectionRequest) { fail("Use assistant path") }
            override suspend fun connectForAssistant(selected: SavedAdapter, authorize: () -> Unit) { session.connect(adapter, authorize) }
        }
        val tools = AssistantConnectionTools(session, repository, actualController) { true }
        val result = async { runCatching { tools.connectSavedAdapter() } }
        started.await()
        repository.allowAssistantReconnect(false)
        finish.complete(Unit)
        assertEquals("adapterReconnectNotAllowed", (result.await().exceptionOrNull() as DiagnosticException).key)
        assertFalse(session.state.value.connected)
        assertTrue(disconnected)
    }
}
