@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package app.leximo.ecu_vue

import app.leximo.ecu_vue.platform.DiagnosticLifecycleObserver
import app.leximo.ecuvue.core.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*

class DiagnosticLifecycleObserverTest {
    @Test fun assistantColdStartDoesNotBeginAutomaticPollingWithoutAnActivity() = runTest {
        val session = DiagnosticSession(backgroundScope)
        val discovery = object : AdapterDiscovery { override val connections = emptySet<ConnectionKind>() }
        val appearance = object : LauncherAppearance {
            override fun schedule(theme: String, palette: String) {}
            override fun refresh() {}
        }
        DiagnosticLifecycleObserver(session, discovery, appearance)
        val simulator = SimulatedAdapter()
        var reads = 0
        session.connect(object : DiagnosticAdapter by simulator {
            override suspend fun readLiveData(): LiveData { reads++; return LiveData(rpm = 750.0) }
        })
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, reads)
        assertEquals(750.0, session.readLiveSnapshot().data.rpm!!, 0.0)
        assertEquals(1, reads)
        session.disconnect()
    }
}
