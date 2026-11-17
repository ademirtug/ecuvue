@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package app.leximo.ecuvue.presentation

import app.leximo.ecuvue.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class ConnectionPresenterTest {
    private val classic = AdapterDevice("paired", "Classic OBD", ConnectionKind.CLASSIC)
    private val ble = AdapterDevice("nearby", "BLE OBD", ConnectionKind.BLE)

    @Test fun pairedDevicesRemainVisibleAndBleResultsAreDeduplicated() = runTest {
        val gate = CompletableDeferred<Unit>()
        val discovery = object : AdapterDiscovery {
            override val connections = setOf(ConnectionKind.CLASSIC, ConnectionKind.BLE)
            override suspend fun pairedDevices() = listOf(classic)
            override suspend fun bleDevices(): List<AdapterDevice> { gate.await(); return listOf(ble, ble) }
        }
        val presenter = ConnectionPresenter(discovery, backgroundScope)
        presenter.loadPaired(); runCurrent()
        presenter.findAdapters(); presenter.findAdapters(); runCurrent()
        assertEquals(listOf(classic), presenter.state.value.adapters)
        assertTrue(presenter.state.value.searching)
        gate.complete(Unit); runCurrent()
        assertEquals(setOf(classic, ble), presenter.state.value.adapters.toSet())
        assertEquals(2, presenter.state.value.adapters.size)
        assertFalse(presenter.state.value.searching)
        presenter.close()
    }

    @Test fun scanFailurePreservesPairedAdaptersAndCanBeDismissed() = runTest {
        val discovery = object : AdapterDiscovery {
            override val connections = setOf(ConnectionKind.CLASSIC, ConnectionKind.BLE)
            override suspend fun pairedDevices() = listOf(classic)
            override suspend fun bleDevices(): List<AdapterDevice> = throw DiagnosticException("permissionDenied")
        }
        val presenter = ConnectionPresenter(discovery, backgroundScope)
        presenter.loadPaired(); runCurrent(); presenter.findAdapters(); runCurrent()
        assertEquals(listOf(classic), presenter.state.value.adapters)
        assertEquals("permissionDenied", presenter.state.value.bluetoothError?.key)
        assertFalse(presenter.state.value.searching)
        presenter.dismissBluetoothError()
        assertNull(presenter.state.value.bluetoothError)
        presenter.close()
    }

    @Test fun closingScreenCancelsDiscoveryWithoutCancellingParentScope() = runTest {
        var cancelled = false
        var stops = 0
        val discovery = object : AdapterDiscovery {
            override val connections = setOf(ConnectionKind.BLE)
            override suspend fun bleDevices(): List<AdapterDevice> {
                try { awaitCancellation() } finally { cancelled = true }
            }
            override fun stopDiscovery() { stops++ }
        }
        val presenter = ConnectionPresenter(discovery, backgroundScope)
        presenter.findAdapters(); runCurrent(); presenter.close(); runCurrent()
        assertTrue(cancelled)
        assertEquals(1, stops)
        assertFalse(presenter.state.value.searching)
        assertTrue(backgroundScope.isActive)
    }
}
