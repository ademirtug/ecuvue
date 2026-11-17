package app.leximo.ecuvue.core

import kotlinx.coroutines.flow.StateFlow

data class AppSettings(val theme: String = "system", val palette: String = "monochrome", val language: String = "", val aiEnabled: Boolean = false) {
    fun validate() {
        require(theme in themes) { "Unknown theme: $theme" }
        require(palette in palettes) { "Unknown palette: $palette" }
        require(language in languages) { "Unknown language: $language" }
    }
    companion object {
        val themes = setOf("system", "light", "dark")
        val palettes = setOf("monochrome", "iceBlue", "racingGreen", "sunset")
        val languages = setOf("", "en", "tr", "es", "de", "ja", "zh", "fr", "ar", "it")
    }
}

interface SettingsRepository {
    val settings: StateFlow<AppSettings>
    suspend fun updateSettings(value: AppSettings)
}

interface ReferenceRepository {
    suspend fun reference(): CodeReference
    suspend fun manufacturers(): VehicleManufacturers
}

interface AdapterDiscovery {
    val connections: Set<ConnectionKind>
    suspend fun pairedDevices(): List<AdapterDevice> = emptyList()
    suspend fun bleDevices(): List<AdapterDevice> = emptyList()
    suspend fun usbDevices(): List<AdapterDevice> = emptyList()
    fun stopDiscovery() {}
}

interface ConnectionController {
    suspend fun connect(request: ConnectionRequest)
    suspend fun connectForAssistant(adapter: SavedAdapter, authorize: () -> Unit) {
        throw DiagnosticException("assistantConnectionUnsupported")
    }
}

interface DiagnosticAdapterFactory { suspend fun create(request: ConnectionRequest): DiagnosticAdapter }

fun interface TransportProvider { suspend fun create(request: ConnectionRequest): ElmTransport }

interface LauncherAppearance {
    fun schedule(theme: String, palette: String)
    fun refresh()
}

interface AiAccessAvailability {
    val supported: Boolean
    suspend fun synchronize(enabled: Boolean)
}
