package app.leximo.ecu_vue.data

import android.content.SharedPreferences
import app.leximo.ecuvue.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidSettingsRepository(
    private val preferences: SharedPreferences,
    legacy: SharedPreferences,
) : SettingsRepository {
    private val mutex = Mutex()
    private val mutableSettings = MutableStateFlow(AppSettings(
        preferences.getString("theme", legacy.getString("flutter.theme", "system")).takeIf { it in AppSettings.themes } ?: "system",
        preferences.getString("palette", legacy.getString("flutter.palette", "monochrome")).takeIf { it in AppSettings.palettes } ?: "monochrome",
        preferences.getString("language", legacy.getString("flutter.language", "")).takeIf { it in AppSettings.languages } ?: "",
        preferences.getBoolean("aiEnabled", false),
    ))
    override val settings = mutableSettings.asStateFlow()
    override suspend fun updateSettings(value: AppSettings) = mutex.withLock {
        value.validate()
        val saved = withContext(Dispatchers.IO) {
            preferences.edit().putString("theme", value.theme).putString("palette", value.palette)
                .putString("language", value.language).putBoolean("aiEnabled", value.aiEnabled).commit()
        }
        if (!saved) throw DiagnosticException("unexpectedError", "Could not save application settings")
        mutableSettings.value = value
    }
}

class AndroidSnapshotStore(private val preferences: SharedPreferences) : SnapshotStore {
    override fun read(): SavedReport? = preferences.getString("latestReport", null)?.let {
        runCatching { SnapshotCodec.decode(it) }.getOrNull()
    }
    override fun save(report: SavedReport?) {
        // Complete persistence before returning a diagnostic report to an assistant.
        if (!preferences.edit().putString("latestReport", report?.let(SnapshotCodec::encode)).commit())
            throw DiagnosticException("unexpectedError", "Could not save the diagnostic report")
    }
}

class AndroidSavedAdapterRepository(private val preferences: SharedPreferences) : StoredSavedAdapterRepository(
    preferences.getString("savedAdapter", null)?.let { runCatching { SavedAdapterCodec.decode(it) }.getOrNull() },
) {
    override suspend fun persist(value: SavedAdapter?) {
        val saved = withContext(Dispatchers.IO) {
            preferences.edit().putString("savedAdapter", value?.let(SavedAdapterCodec::encode)).commit()
        }
        if (!saved) throw DiagnosticException("unexpectedError", "Could not save adapter preferences")
    }
}
