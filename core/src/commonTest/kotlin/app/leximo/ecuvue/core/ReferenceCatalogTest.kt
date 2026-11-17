@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package app.leximo.ecuvue.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class ReferenceCatalogTest {
    private fun reference() = CodeReference.parse("""{
        "schemaVersion":1,"datasetVersion":"test","source":{"name":"test","licenseText":"CC0"},
        "records":[{"code":"P0301","texts":{"en":{"title":"Misfire","meaning":"Meaning","causes":[],"next":"Inspect"}}}]
    }""")

    @Test fun observersReceiveReadyAfterOneBackgroundLoad() = runTest {
        val gate = CompletableDeferred<CodeReference>()
        var calls = 0
        val catalog = ReferenceCatalog(backgroundScope, { calls++; gate.await() }, StandardTestDispatcher(testScheduler))
        val observed = mutableListOf<CatalogState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { catalog.state.collect { observed += it } }
        repeat(10) { catalog.load() }
        assertEquals(0, calls) // load() never executes the loader on its caller.
        runCurrent()
        assertEquals(1, calls)
        assertEquals(listOf<CatalogState>(CatalogState.Loading), observed)
        val expected = reference()
        gate.complete(expected)
        runCurrent()
        assertEquals(listOf(CatalogState.Loading, CatalogState.Ready(expected)), observed)
        catalog.load(); runCurrent()
        assertEquals(1, calls)
    }

    @Test fun failedLoadNotifiesObserversAndCanBeRetried() = runTest {
        val expected = reference()
        var calls = 0
        val catalog = ReferenceCatalog(backgroundScope, {
            if (++calls == 1) error("Unavailable")
            expected
        }, StandardTestDispatcher(testScheduler))
        val observed = mutableListOf<CatalogState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { catalog.state.collect { observed += it } }
        catalog.load(); runCurrent()
        assertEquals(CatalogState.Failed("Unavailable"), catalog.state.value)
        catalog.load(); runCurrent()
        assertEquals(listOf(CatalogState.Loading, CatalogState.Failed("Unavailable"), CatalogState.Loading, CatalogState.Ready(expected)), observed)
        assertEquals(2, calls)
    }

    @Test fun applicationShutdownCancelsLoadingWithoutReportingFailure() = runTest {
        val applicationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var cancelled = false
        val catalog = ReferenceCatalog(applicationScope, {
            try { awaitCancellation() } finally { cancelled = true }
        }, StandardTestDispatcher(testScheduler))
        catalog.load(); runCurrent()
        applicationScope.cancel(); runCurrent()
        assertTrue(cancelled)
        assertEquals(CatalogState.Loading, catalog.state.value)
    }
}
