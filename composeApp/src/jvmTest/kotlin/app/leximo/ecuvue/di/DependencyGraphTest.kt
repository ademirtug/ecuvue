package app.leximo.ecuvue.di

import app.leximo.ecuvue.core.*
import app.leximo.ecuvue.presentation.ConnectionPresenter
import app.leximo.ecuvue.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.*

class DependencyGraphTest {
    @Test fun graphSharesOneSessionAndTheOptInRepositoryAcrossUiAndTools() = runTest {
        val applicationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val container = koinApplication {
            modules(sharedModule, demoModule("Test"), module {
                single<CoroutineScope>(ApplicationScope) { applicationScope }
            })
        }
        try {
            val host = container.koin.get<AppHost>()
            val session = container.koin.get<DiagnosticSession>()
            val tools = container.koin.get<ReadOnlyDiagnosticTools>()
            val connections = container.koin.get<AssistantConnectionTools>()
            val settings = container.koin.get<SettingsRepository>()
            assertSame(session, host.session)
            assertSame<Any>(host.references, host.resources)
            assertSame(container.koin.get<ReferenceCatalog>(), host.catalog)
            assertSame(host.settings, settings.settings)
            assertFailsWith<DiagnosticException> { tools.connectionStatus() }
            settings.updateSettings(AppSettings(aiEnabled = true))
            host.connect(ConnectionRequest(ConnectionKind.SIMULATOR))
            assertTrue(tools.connectionStatus().connected)
            assertEquals(session.state.value.sessionId, connections.connectSavedAdapter().sessionId)
            assertSame(session.state.value, tools.connectionStatus())
            val report = tools.readFaultCodes()
            assertEquals(report.report, host.session.state.value.report)
            settings.updateSettings(AppSettings(aiEnabled = false))
            assertEquals("aiAccessDisabled", assertFailsWith<DiagnosticException> { tools.latestReport() }.key)
            assertEquals("aiAccessDisabled", assertFailsWith<DiagnosticException> { connections.connectSavedAdapter() }.key)
            session.disconnect()
        } finally {
            container.close()
            // The overridden scope belongs to this test rather than the default graph definition.
            applicationScope.cancel()
        }
    }

    @Test fun screenFactoriesAcceptAnAlternateDiscoveryPort() = runTest {
        val container = koinApplication { modules(sharedModule, demoModule("Test")) }
        try {
            val expected = AdapterDevice("fake", "Injected adapter", ConnectionKind.CLASSIC)
            val discovery = object : AdapterDiscovery {
                override val connections = setOf(ConnectionKind.CLASSIC)
                override suspend fun pairedDevices() = listOf(expected)
            }
            val presenter = container.koin.get<ConnectionPresenter> { parametersOf(discovery, backgroundScope) }
            presenter.loadPaired()
            testScheduler.runCurrent()
            assertEquals(listOf(expected), presenter.state.value.adapters)
            presenter.close()
        } finally { container.close() }
    }
}
