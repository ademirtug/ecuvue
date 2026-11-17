package app.leximo.ecuvue.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BundledAppResourcesTest {
    @Test fun catalogLoadDoesNotBlockTranslationsOrManufacturerLookup() = runTest {
        val catalogStarted = CompletableDeferred<Unit>()
        val releaseCatalog = CompletableDeferred<Unit>()
        val resources = BundledAppResources { path ->
            when (path) {
                "files/reference/codes.v1.json" -> {
                    catalogStarted.complete(Unit)
                    releaseCatalog.await()
                    error("Catalog unavailable")
                }
                "files/translations.json" -> """{"en":{"overview":"Overview"}}""".encodeToByteArray()
                "files/reference/vehicle_manufacturers.v1.json" -> """{"entries":{}}""".encodeToByteArray()
                else -> error("Unexpected asset: $path")
            }
        }
        val catalog = async { runCatching { resources.reference() } }
        catalogStarted.await()
        try {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    assertEquals("Overview", resources.translations().text("en", "overview"))
                    assertNull(resources.manufacturers().identify("ZZZ00000000000001").manufacturer)
                }
            }
            assertFalse(catalog.isCompleted)
        } finally { releaseCatalog.complete(Unit) }
        assertTrue(catalog.await().isFailure)
    }
}
