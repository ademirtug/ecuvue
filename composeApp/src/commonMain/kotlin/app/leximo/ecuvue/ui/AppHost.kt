package app.leximo.ecuvue.ui

import app.leximo.ecuvue.core.*

interface UiResources { suspend fun translations(): Translations }

class Translations(private val languages: Map<String, Map<String, String>>) {
    fun text(language: String, key: String, fallback: String = key): String =
        languages[language.substringBefore('-').substringBefore('_')]?.get(key) ?: languages["en"]?.get(key) ?: fallback
}

interface PlatformServices {
    val platform: String
    val systemLanguage: String get() = "en"
    fun openBluetoothSettings() {}
    fun openWifiSettings() {}
    fun screenViewed(name: String) {}
}

interface BackNavigation { fun setBackAction(action: (() -> Unit)?) }

/** UI facade over small injected ports. It does not construct or own infrastructure. */
interface AppHost : SettingsRepository, AdapterDiscovery, ConnectionController, PlatformServices, BackNavigation {
    val session: DiagnosticSession
    val references: ReferenceRepository
    val catalog: ReferenceCatalog
    val resources: UiResources
    val supportsAiTools: Boolean
    val savedAdapters: SavedAdapterRepository
}

class DefaultAppHost(
    override val session: DiagnosticSession,
    settings: SettingsRepository,
    discovery: AdapterDiscovery,
    connection: ConnectionController,
    override val references: ReferenceRepository,
    override val resources: UiResources,
    platform: PlatformServices,
    navigation: BackNavigation,
    private val aiAccess: AiAccessAvailability,
    override val savedAdapters: SavedAdapterRepository,
    override val catalog: ReferenceCatalog,
) : AppHost, SettingsRepository by settings, AdapterDiscovery by discovery, ConnectionController by connection,
    PlatformServices by platform, BackNavigation by navigation {
    override val supportsAiTools get() = aiAccess.supported
}
