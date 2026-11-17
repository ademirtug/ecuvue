package app.leximo.ecu_vue

import android.view.ViewTreeObserver
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.leximo.ecuvue.core.CatalogState
import app.leximo.ecuvue.core.CodeReference
import app.leximo.ecuvue.core.ReferenceCatalog
import app.leximo.ecuvue.ui.AppHost
import app.leximo.ecuvue.ui.EcuVueApp
import kotlinx.coroutines.*
import org.koin.android.ext.android.getKoin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupIntegrationTest {
    @get:Rule val ui = createEmptyComposeRule()

    @Test fun coldLaunchDrawsPastSplashAndShowsOverview() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val firstDraw = CountDownLatch(1)
            lateinit var listener: ViewTreeObserver.OnDrawListener
            scenario.onActivity { activity ->
                listener = ViewTreeObserver.OnDrawListener { firstDraw.countDown() }
                activity.window.decorView.viewTreeObserver.addOnDrawListener(listener)
                activity.window.decorView.invalidate()
            }
            try {
                // A retained native splash cancels drawing even when Compose semantics exist.
                assertTrue("Native splash prevented the first app frame", firstDraw.await(10, TimeUnit.SECONDS))
                ui.waitUntil(30_000) {
                    ui.onAllNodesWithTag("nav-overview").fetchSemanticsNodes().isNotEmpty()
                }
                ui.onNodeWithTag("nav-overview").assertIsDisplayed()
            } finally {
                scenario.onActivity { it.window.decorView.viewTreeObserver.removeOnDrawListener(listener) }
            }
        }
    }

    @Test fun catalogObserversEnableSearchAfterRetryWithoutBlockingNavigation() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstAttempt = CompletableDeferred<CodeReference>()
        val reference = CodeReference.parse("""{
            "schemaVersion":1,"datasetVersion":"test","source":{"name":"test","licenseText":"CC0"},
            "records":[{"code":"P0301","texts":{"en":{"title":"Misfire","meaning":"Meaning","causes":[],"next":"Inspect"}}}]
        }""")
        var attempts = 0
        val testCatalog = ReferenceCatalog(scope, { if (++attempts == 1) firstAttempt.await() else reference })
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ui.waitUntil(10_000) { ui.onAllNodesWithTag("nav-overview").fetchSemanticsNodes().isNotEmpty() }
                scenario.onActivity { activity ->
                    val original = activity.getKoin().get<AppHost>()
                    val host = object : AppHost by original { override val catalog = testCatalog }
                    activity.setContent { EcuVueApp(host) }
                }
                ui.waitUntil(10_000) { ui.onAllNodesWithTag("reference-query").fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithTag("reference-query").performScrollTo().assertIsNotEnabled()
                ui.onNodeWithTag("catalog-loading").assertExists()
                ui.onNodeWithTag("nav-settings").performClick()
                ui.onNodeWithTag("theme-dropdown").assertExists()
                ui.onNodeWithTag("nav-overview").performClick()
                ui.onNodeWithTag("reference-query").performScrollTo().assertIsNotEnabled()

                firstAttempt.completeExceptionally(IllegalStateException("Read failed"))
                ui.waitUntil(10_000) { testCatalog.state.value is CatalogState.Failed }
                ui.onNodeWithTag("catalog-retry").performScrollTo().performClick()
                ui.waitUntil(10_000) { testCatalog.state.value is CatalogState.Ready }
                ui.onNodeWithTag("reference-query").performScrollTo().assertIsEnabled().performTextReplacement("P0301")
                ui.onNode(hasText("P0301") and !hasSetTextAction()).performScrollTo().assertIsDisplayed()
            }
        } finally { scope.cancel() }
    }
}
