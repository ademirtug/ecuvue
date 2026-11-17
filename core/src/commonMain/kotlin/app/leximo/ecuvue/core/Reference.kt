package app.leximo.ecuvue.core

import kotlinx.serialization.json.*

data class CodeDescription(val title: String, val meaning: String, val causes: List<String>, val next: String)
data class ReferenceMatch(val code: String, val description: CodeDescription, val language: String, val isFallback: Boolean)

class CodeReference private constructor(
    val version: String, val sourceName: String, val licenseText: String,
    private val records: Map<String, Map<String, CodeDescription>>,
) {
    val count: Int get() = records.size
    fun lookup(code: String, language: String = "en"): ReferenceMatch? {
        val normalized = code.trim().uppercase()
        val texts = records[normalized] ?: return null
        val requested = language.replace('_', '-')
        val base = requested.substringBefore('-')
        val selected = when { requested in texts -> requested; base in texts -> base; else -> "en" }
        return ReferenceMatch(normalized, texts.getValue(selected), selected, selected != requested && selected != base)
    }
    fun search(query: String, language: String = "en", limit: Int = 30): List<ReferenceMatch> {
        val normalized = query.trim().uppercase()
        if (normalized.isEmpty() || limit <= 0) return emptyList()
        val ordered = records.keys.filter { it.startsWith(normalized) } + records.keys.filter {
            !it.startsWith(normalized) && (lookup(it, language)!!.description.title.uppercase().contains(normalized) ||
                records.getValue(it).getValue("en").title.uppercase().contains(normalized))
        }
        return ordered.take(limit.coerceAtMost(100)).mapNotNull { lookup(it, language) }
    }
    companion object {
        fun parse(source: String): CodeReference {
            val root = Json.parseToJsonElement(source).jsonObject
            require(root.getValue("schemaVersion").jsonPrimitive.int == 1) { "Unsupported reference schema" }
            fun JsonElement.text(): String = jsonPrimitive.content.also { require(it.isNotBlank()) { "Missing reference text" } }
            val records = linkedMapOf<String, Map<String, CodeDescription>>()
            val generic = Regex("^(P[02][0-9A-F]{3}|P3[4-9][0-9A-F]{2}|[BC]0[0-9A-F]{3}|U[03][0-9A-F]{3})$")
            val localePattern = Regex("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$")
            val validatedLocales = mutableSetOf<String>()
            for (element in root.getValue("records").jsonArray) {
                val item = element.jsonObject
                val code = item.getValue("code").text()
                require(generic.matches(code) && code !in records) { "Invalid or duplicate generic code: $code" }
                val translations = item.getValue("texts").jsonObject.mapValues { (locale, value) ->
                    // Thousands of records share the same few language tags.
                    if (validatedLocales.add(locale)) require(localePattern.matches(locale))
                    val text = value.jsonObject
                    CodeDescription(text.getValue("title").text(), text.getValue("meaning").text(),
                        text.getValue("causes").jsonArray.map { it.text() }, text.getValue("next").text())
                }
                require("en" in translations) { "English fallback required" }
                records[code] = translations
            }
            require(records.isNotEmpty())
            val metadata = root.getValue("source").jsonObject
            return CodeReference(root.getValue("datasetVersion").text(), metadata.getValue("name").text(),
                metadata.getValue("licenseText").text(), records)
        }
    }
}

class VehicleManufacturers(source: String) {
    private val entries = Json.parseToJsonElement(source).jsonObject.getValue("entries").jsonObject
    fun identify(vin: String): VehicleIdentity {
        val item = entries[vin.take(3)]?.jsonObject
        val makes = item?.get("makes")?.jsonPrimitive?.content?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        return VehicleIdentity(vin, item?.get("manufacturer")?.jsonPrimitive?.content, makes.singleOrNull())
    }
}
