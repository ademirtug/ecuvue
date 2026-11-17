package app.leximo.ecu_vue

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class RfcommConnectorTest {
    private class FakeConnection(private val action: () -> Unit = {}) : RfcommConnection {
        var closed = false
        override fun connect() = action()
        override fun close() { closed = true }
    }

    @Test fun compatibleSuccessDoesNotTriggerAuthenticatedPairing() {
        val modes = mutableListOf<RfcommMode>()
        val legacy = FakeConnection()
        val result = connectRfcomm({ true }, { false }, { mode -> modes += mode; legacy }, {})
        assertSame(legacy, result)
        assertEquals(listOf(RfcommMode.COMPATIBLE), modes)
        assertFalse(legacy.closed)
    }

    @Test fun authenticatedFallbackClosesTheFailedLegacySocket() {
        val modes = mutableListOf<RfcommMode>()
        val legacy = FakeConnection { throw IOException("Serial connection refused") }
        val secure = FakeConnection()
        val result = connectRfcomm({ true }, { false }, { mode -> modes += mode; if (mode == RfcommMode.COMPATIBLE) legacy else secure }, {})
        assertSame(secure, result)
        assertTrue(legacy.closed)
        assertFalse(secure.closed)
        assertEquals(listOf(RfcommMode.COMPATIBLE, RfcommMode.AUTHENTICATED), modes)
    }

    @Test fun bothFailuresKeepTheActualReasonsAndCloseBothSockets() {
        val legacy = FakeConnection { throw IOException("legacy timeout") }
        val secure = FakeConnection { throw IOException("authentication failed") }
        try {
            connectRfcomm({ true }, { false }, { if (it == RfcommMode.COMPATIBLE) legacy else secure }, {})
            fail("Expected an error")
        } catch (error: IOException) {
            assertTrue(error.message!!.contains("legacy timeout"))
            assertTrue(error.message!!.contains("authentication failed"))
        }
        assertTrue(legacy.closed)
        assertTrue(secure.closed)
    }

    @Test fun unpairedDeviceDoesNotOpenASocketOrGuessAPin() {
        var created = false
        try {
            connectRfcomm({ false }, { false }, { created = true; FakeConnection() }, {})
            fail("Expected pairing required")
        } catch (_: PairingRequiredException) {}
        assertFalse(created)
    }

    @Test fun cancellationWhileConnectingCannotOpenAFallbackSocket() {
        var cancelled = false
        var attempts = 0
        val legacy = FakeConnection { cancelled = true; throw IOException("closed") }
        try {
            connectRfcomm({ true }, { cancelled }, { attempts++; legacy }, {})
            fail("Expected cancellation")
        } catch (_: ConnectionCancelledException) {}
        assertEquals(1, attempts)
        assertTrue(legacy.closed)
    }

    @Test fun losingTheBondStopsFallbackAndReportsPairingRequired() {
        var paired = true
        var attempts = 0
        val legacy = FakeConnection { paired = false; throw IOException("authentication failed") }
        try {
            connectRfcomm({ paired }, { false }, { attempts++; legacy }, {})
            fail("Expected pairing required")
        } catch (_: PairingRequiredException) {}
        assertEquals(1, attempts)
        assertTrue(legacy.closed)
    }

    @Test fun permissionFailureDoesNotRetryAsAnAuthenticationFailure() {
        var attempts = 0
        val legacy = FakeConnection { throw SecurityException("Bluetooth permission revoked") }
        try {
            connectRfcomm({ true }, { false }, { attempts++; legacy }, {})
            fail("Expected permission failure")
        } catch (_: SecurityException) {}
        assertEquals(1, attempts)
        assertTrue(legacy.closed)
    }
}
