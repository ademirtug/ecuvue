package app.leximo.ecuvue.data

import app.leximo.ecu_vue.resources.Res
import app.leximo.ecuvue.core.*
import app.leximo.ecuvue.ui.Translations
import app.leximo.ecuvue.ui.UiResources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** One process-wide cache, shared by UI, adapter identification and assistant entry points. */
@OptIn(ExperimentalResourceApi::class)
class BundledAppResources(
    private val readBytes: suspend (String) -> ByteArray = { Res.readBytes(it) },
) : ReferenceRepository, UiResources {
    // Catalog parsing must never hold up translations or adapter identification.
    private val referenceMutex = Mutex()
    private val manufacturersMutex = Mutex()
    private val translationsMutex = Mutex()
    private var reference: CodeReference? = null
    private var manufacturers: VehicleManufacturers? = null
    private var translations: Translations? = null
    override suspend fun reference(): CodeReference {
        referenceMutex.lock()
        try {
            return reference ?: withContext(Dispatchers.Default) {
                CodeReference.parse(readBytes("files/reference/codes.v1.json").decodeToString())
            }.also { reference = it }
        } finally { referenceMutex.unlock() }
    }
    override suspend fun manufacturers(): VehicleManufacturers {
        manufacturersMutex.lock()
        try {
            return manufacturers ?: withContext(Dispatchers.Default) {
                VehicleManufacturers(readBytes("files/reference/vehicle_manufacturers.v1.json").decodeToString())
            }.also { manufacturers = it }
        } finally { manufacturersMutex.unlock() }
    }
    override suspend fun translations(): Translations {
        translationsMutex.lock()
        try {
            return translations ?: withContext(Dispatchers.Default) {
                val root = Json.parseToJsonElement(readBytes("files/translations.json").decodeToString()).jsonObject
                Translations(root.mapValues { (_, text) -> text.jsonObject.mapValues { it.value.jsonPrimitive.content } })
            }.also { translations = it }
        } finally { translationsMutex.unlock() }
    }
}
