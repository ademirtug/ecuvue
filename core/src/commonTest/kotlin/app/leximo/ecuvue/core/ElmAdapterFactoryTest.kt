package app.leximo.ecuvue.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ElmAdapterFactoryTest {
    private val references = object : ReferenceRepository {
        override suspend fun reference(): CodeReference = error("Code reference not needed for adapter creation")
        override suspend fun manufacturers() = VehicleManufacturers("""{"entries":{}}""")
    }
    @Test fun registeredProviderReceivesTheOriginalRequest() = runTest {
        var received: ConnectionRequest? = null
        val transport = object : ElmTransport {
            override suspend fun connect() {}
            override suspend fun send(command: String): String = error("Factory must not communicate with an ECU")
            override suspend fun disconnect() {}
        }
        val factory = ElmAdapterFactory(mapOf(ConnectionKind.WIFI to TransportProvider { request -> received = request; transport }), references)
        val request = ConnectionRequest(ConnectionKind.WIFI, "192.168.0.10", "Test adapter", port = 35000)
        val adapter = factory.create(request)
        assertEquals(request, received)
        assertEquals("Test adapter", adapter.name)
        assertFalse(adapter.simulated)
    }
    @Test fun simulatorRequiresNeitherHardwareNorReferenceLoading() = runTest {
        val unavailable = object : ReferenceRepository {
            override suspend fun reference(): CodeReference = error("Unavailable")
            override suspend fun manufacturers(): VehicleManufacturers = error("Unavailable")
        }
        val factory = ElmAdapterFactory(emptyMap(), unavailable)
        assertTrue(factory.create(ConnectionRequest(ConnectionKind.SIMULATOR)).simulated)
        assertEquals("connectionFailed", assertFailsWith<DiagnosticException> { factory.create(ConnectionRequest(ConnectionKind.BLE)) }.key)
    }
}
