package app.leximo.ecu_vue

import androidx.appfunctions.metadata.AppFunctionPackageMetadata
import androidx.appfunctions.metadata.AppFunctionComponentsMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class AppFunctionMetadataTest {
    @Test
    fun packagedAppMetadataHasDescriptionsReadableByAppFunctions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Use the library's real parser on compiled resources via the manifest property.
        // The old child-element format parsed as XML but returned two empty descriptions.
        val metadata = AppFunctionPackageMetadata(
            packageName = context.packageName,
            components = AppFunctionComponentsMetadata(),
        ).resolveAppFunctionAppMetadata(context)

        assertNotNull("AppFunctions must resolve the packaged app metadata", metadata)
        val appMetadata = requireNotNull(metadata)
        assertTrue("Agent description must be nonempty", appMetadata.description.isNotBlank())
        assertTrue("Display description must be nonempty", appMetadata.displayDescription.isNotBlank())
    }
}
