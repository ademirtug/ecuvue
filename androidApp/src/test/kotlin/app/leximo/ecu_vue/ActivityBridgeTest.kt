package app.leximo.ecu_vue

import app.leximo.ecu_vue.platform.ActivityBridge
import app.leximo.ecuvue.core.DiagnosticException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class ActivityBridgeTest {
    @Test fun destroyingAnOlderActivityDoesNotDetachItsReplacement() = runBlocking {
        val bridge = ActivityBridge()
        val old = Any()
        val current = Any()
        var permissions = emptyList<String>()
        var backs = 0
        bridge.attach(old) { error("Old Activity must not receive permission requests") }
        bridge.attach(current) { permissions = it.toList() }
        bridge.setBackAction { backs++ }
        bridge.detach(old)
        bridge.request(arrayOf("new-permission"))
        assertEquals(listOf("new-permission"), permissions)
        assertTrue(bridge.handleBack())
        assertEquals(1, backs)
        bridge.detach(current)
        assertFalse(bridge.handleBack())
        try { bridge.request(arrayOf("permission")); fail("Detached bridge must deny requests") }
        catch (error: DiagnosticException) { assertEquals("permissionDenied", error.key) }
    }
}
