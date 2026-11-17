@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package app.leximo.ecuvue.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import kotlin.test.*

class SettingsObserverTest {
    @Test fun onlyRelevantChangesReachEachObserverAndStopDetaches() = runTest {
        val repository = MemorySettingsRepository()
        val appearance = mutableListOf<Pair<String, String>>()
        val access = mutableListOf<Boolean>()
        val observer = SettingsObserver(repository, object : LauncherAppearance {
            override fun schedule(theme: String, palette: String) { appearance += theme to palette }
            override fun refresh() {}
        }, object : AiAccessAvailability {
            override val supported = true
            override suspend fun synchronize(enabled: Boolean) { access += enabled }
        }, backgroundScope)
        observer.start(); observer.start(); runCurrent()
        assertEquals(listOf("system" to "monochrome"), appearance)
        assertEquals(listOf(false), access)
        repository.updateSettings(repository.settings.value.copy(language = "tr")); runCurrent()
        assertEquals(1, appearance.size)
        assertEquals(1, access.size)
        repository.updateSettings(repository.settings.value.copy(theme = "dark", palette = "iceBlue")); runCurrent()
        assertEquals("dark" to "iceBlue", appearance.last())
        assertEquals(1, access.size)
        repository.updateSettings(repository.settings.value.copy(aiEnabled = true)); runCurrent()
        assertEquals(listOf(false, true), access)
        assertEquals(2, appearance.size)
        observer.stop()
        repository.updateSettings(AppSettings()); runCurrent()
        assertEquals(2, access.size)
        observer.start(); runCurrent()
        assertEquals(false, access.last())
        assertEquals("system" to "monochrome", appearance.last())
        observer.stop()
    }

    @Test fun rapidRevocationCancelsStaleEnableOperation() = runTest {
        val repository = MemorySettingsRepository()
        val started = mutableListOf<Boolean>()
        val completed = mutableListOf<Boolean>()
        val observer = SettingsObserver(repository, object : LauncherAppearance {
            override fun schedule(theme: String, palette: String) {}
            override fun refresh() {}
        }, object : AiAccessAvailability {
            override val supported = true
            override suspend fun synchronize(enabled: Boolean) {
                started += enabled
                delay(1_000)
                completed += enabled
            }
        }, backgroundScope)
        observer.start(); runCurrent()
        repository.updateSettings(AppSettings(aiEnabled = true)); runCurrent()
        repository.updateSettings(AppSettings(aiEnabled = false)); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(false, true, false), started)
        assertEquals(listOf(false), completed)
        observer.stop()
    }

    @Test fun invalidSettingsDoNotReplaceObservedState() = runTest {
        val repository = MemorySettingsRepository()
        assertFailsWith<IllegalArgumentException> { repository.updateSettings(AppSettings(theme = "invalid")) }
        assertFailsWith<IllegalArgumentException> { repository.updateSettings(AppSettings(palette = "invalid")) }
        assertFailsWith<IllegalArgumentException> { repository.updateSettings(AppSettings(language = "invalid")) }
        assertEquals(AppSettings(), repository.settings.value)
    }
}
