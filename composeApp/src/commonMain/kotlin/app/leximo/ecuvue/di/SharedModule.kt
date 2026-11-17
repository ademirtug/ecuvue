package app.leximo.ecuvue.di

import app.leximo.ecuvue.core.*
import app.leximo.ecuvue.data.BundledAppResources
import app.leximo.ecuvue.presentation.ConnectionPresenter
import app.leximo.ecuvue.ui.*
import kotlinx.coroutines.*
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

val ApplicationScope = named("applicationScope")

val sharedModule = module {
    single<CoroutineScope>(ApplicationScope) { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) } onClose { it?.cancel() }
    single { BundledAppResources() }
    single<ReferenceRepository> { get<BundledAppResources>() }
    single<UiResources> { get<BundledAppResources>() }
    single { ReferenceCatalog(get(ApplicationScope), get<ReferenceRepository>()::reference) }
    single { DiagnosticSession(get(ApplicationScope), get()) }
    single<DiagnosticAdapterFactory> { ElmAdapterFactory(get(), get()) }
    single {
        val settings = get<SettingsRepository>()
        ReadOnlyDiagnosticTools(get(), { settings.settings.value.aiEnabled }, get<ReferenceRepository>()::reference)
    }
    single { SettingsObserver(get(), get(), get(), get(ApplicationScope)) }
    single { AssistantConnectionTools(get(), get(), get()) { get<SettingsRepository>().settings.value.aiEnabled } }
    single<AppHost> { DefaultAppHost(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    factory { (discovery: AdapterDiscovery, scope: CoroutineScope) -> ConnectionPresenter(discovery, scope) }
}

/** Desktop/iOS preview bindings; hardware and assistant implementations belong to their platforms. */
fun demoModule(platform: String, language: String = "en") = module {
    single<SettingsRepository> { MemorySettingsRepository() }
    single<SnapshotStore> { MemorySnapshotStore() }
    single<SavedAdapterRepository> { StoredSavedAdapterRepository() }
    single<Map<ConnectionKind, TransportProvider>> { emptyMap() }
    single<AdapterDiscovery> { object : AdapterDiscovery { override val connections = setOf(ConnectionKind.SIMULATOR) } }
    single<ConnectionController> {
        val session = get<DiagnosticSession>()
        val factory = get<DiagnosticAdapterFactory>()
        object : ConnectionController {
            override suspend fun connect(request: ConnectionRequest) {
                require(request.kind == ConnectionKind.SIMULATOR) { "Hardware connections are not implemented on this platform" }
                session.connect(factory.create(request))
            }
        }
    }
    single<PlatformServices> { object : PlatformServices {
        override val platform = platform
        override val systemLanguage = language
    } }
    single<BackNavigation> { object : BackNavigation { override fun setBackAction(action: (() -> Unit)?) {} } }
    single<LauncherAppearance> { object : LauncherAppearance {
        override fun schedule(theme: String, palette: String) {}
        override fun refresh() {}
    } }
    single<AiAccessAvailability> { object : AiAccessAvailability {
        override val supported = false
        override suspend fun synchronize(enabled: Boolean) {}
    } }
}

fun startDemoKoin(platform: String, language: String = "en") = startKoin {
    modules(sharedModule, demoModule(platform, language))
}.also {
    it.koin.get<SettingsObserver>().start()
    it.koin.get<ReferenceCatalog>().load()
}
