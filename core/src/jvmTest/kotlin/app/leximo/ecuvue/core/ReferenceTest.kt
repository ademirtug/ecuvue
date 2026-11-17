package app.leximo.ecuvue.core

import java.io.File
import kotlin.test.*

class ReferenceTest {
    private val assets = File(System.getProperty("ecuVue.referenceDirectory"))
    @Test fun completeCatalogValidatesAndPreservesFallbackAndCoverage() {
        val reference = CodeReference.parse(File(assets, "codes.v1.json").readText())
        assertEquals(9532, reference.count)
        assertEquals("2026.10.28.1", reference.version)
        assertNotNull(reference.lookup("P0301"))
        assertFalse(reference.lookup("p0301", "tr")!!.isFallback)
        assertTrue(reference.lookup("P0301", "ja")!!.isFallback)
        assertNull(reference.lookup("P1301"))
        assertTrue(reference.search("P03").all { it.code.startsWith("P03") })
        assertTrue(reference.search("", limit = 8).isEmpty())
        assertTrue(reference.licenseText.isNotBlank())
    }
    @Test fun missingFallbackAndManufacturerSpecificCodesAreRejected() {
        val source = """{"schemaVersion":1,"datasetVersion":"test","source":{"name":"test","licenseText":"CC0"},"records":[{"code":"P0301","texts":{"en":{"title":"Title","meaning":"Meaning","causes":[],"next":"Inspect"}}}]}"""
        assertEquals(1, CodeReference.parse(source).count)
        assertFailsWith<IllegalArgumentException> { CodeReference.parse(source.replace("\"en\"", "\"tr\"")) }
        assertFailsWith<IllegalArgumentException> { CodeReference.parse(source.replace("\"P0301\"", "\"P1301\"")) }
    }
    @Test fun languageTagsAreValidatedAlongsideEnglishFallback() {
        val description = """{"title":"Title","meaning":"Meaning","causes":[],"next":"Inspect"}"""
        fun catalog(locale: String) = """{
            "schemaVersion":1,"datasetVersion":"test","source":{"name":"test","licenseText":"CC0"},
            "records":[{"code":"P0301","texts":{"en":$description,"$locale":$description}}]
        }"""
        assertEquals("en-US", CodeReference.parse(catalog("en-US")).lookup("P0301", "en-US")!!.language)
        for (locale in listOf("english", "en-")) {
            assertFailsWith<IllegalArgumentException> { CodeReference.parse(catalog(locale)) }
        }
    }
    @Test fun manufacturerLookupDoesNotGuessModelOrYear() {
        val manufacturers = VehicleManufacturers(File(assets, "vehicle_manufacturers.v1.json").readText())
        assertNotNull(manufacturers.identify("WDD00000000000001").manufacturer)
        assertEquals(VehicleIdentity("ZZZ00000000000001"), manufacturers.identify("ZZZ00000000000001"))
    }
}
