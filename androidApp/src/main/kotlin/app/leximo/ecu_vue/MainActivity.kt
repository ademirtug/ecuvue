package app.leximo.ecu_vue

import android.os.Bundle
import android.os.SystemClock
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import app.leximo.ecuvue.core.DiagnosticException
import app.leximo.ecuvue.ui.EcuVueApp
import app.leximo.ecuvue.ui.AppHost
import app.leximo.ecuvue.ui.UiResources
import app.leximo.ecuvue.ui.Translations
import app.leximo.ecu_vue.platform.ActivityBridge
import org.koin.android.ext.android.inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val host: AppHost by inject()
    private val activityBridge: ActivityBridge by inject()
    private val resources: UiResources by inject()
    private var permissionResult: CompletableDeferred<Unit>? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val pending = permissionResult; permissionResult = null
        if (result.values.all { it }) pending?.complete(Unit)
        else pending?.completeExceptionally(DiagnosticException("permissionDenied"))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        var startupPending = true
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val startupStarted = SystemClock.uptimeMillis()
        // Catalog readiness is independent of the native splash.
        splash.setKeepOnScreenCondition { startupPending }
        enableEdgeToEdge()
        activityBridge.attach(this) { permissions ->
            if (permissionResult != null) throw DiagnosticException("busy")
            val result = CompletableDeferred<Unit>(); permissionResult = result
            try { permissionLauncher.launch(permissions); result.await() }
            finally { if (permissionResult === result) permissionResult = null }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!activityBridge.handleBack()) { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        // Prepare the first interface before installing Compose content. Nothing here awaits a UI frame.
        lifecycleScope.launch {
            var translations: Translations? = null
            var startupError: String? = null
            try { translations = resources.translations() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                startupError = error.message ?: "Could not load interface translations"
                android.util.Log.w("ECUVue", "UI asset preload failed", error)
            }
            android.util.Log.i("ECUVue", "UI asset preload finished in ${SystemClock.uptimeMillis() - startupStarted} ms")
            setContent {
                val settings by host.settings.collectAsState()
                val systemDark = LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                val dark = when (settings.theme) { "dark" -> true; "light" -> false; else -> systemDark }
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                EcuVueApp(host, initialTranslations = translations, initialStartupError = startupError)
            }
            // The next draw composes the main interface (or Retry), never a loading placeholder.
            startupPending = false
        }
    }
    override fun onDestroy() {
        activityBridge.detach(this)
        permissionResult?.cancel(CancellationException("Activity closed")); permissionResult = null
        super.onDestroy()
    }
}
