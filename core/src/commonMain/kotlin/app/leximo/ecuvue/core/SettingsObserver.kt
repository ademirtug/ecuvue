package app.leximo.ecuvue.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Observes preferences independently of any Activity or Compose screen. */
class SettingsObserver(
    private val repository: SettingsRepository,
    private val appearance: LauncherAppearance,
    private val aiAccess: AiAccessAvailability,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    fun start() {
        if (job != null) return
        job = scope.launch {
            launch {
                repository.settings.map { it.theme to it.palette }.distinctUntilChanged().collect { (theme, palette) ->
                    appearance.schedule(theme, palette)
                }
            }
            launch {
                repository.settings.map { it.aiEnabled }.distinctUntilChanged().collectLatest { enabled ->
                    aiAccess.synchronize(enabled)
                }
            }
        }
    }
    fun stop() { job?.cancel(); job = null }
}

class MemorySettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val mutableSettings = MutableStateFlow(initial.also { it.validate() })
    override val settings = mutableSettings.asStateFlow()
    override suspend fun updateSettings(value: AppSettings) { value.validate(); mutableSettings.value = value }
}
