package app.leximo.ecuvue.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import app.leximo.ecuvue.di.startDemoKoin
import org.koin.core.KoinApplication

private var container: KoinApplication? = null

/** iOS UI/engine preview. Native adapter connections and App Intents are a later target. */
fun MainViewController(): platform.UIKit.UIViewController {
    val app = container ?: startDemoKoin("iOS").also { container = it }
    return ComposeUIViewController {
        val host = remember { app.koin.get<AppHost>() }
        EcuVueApp(host)
    }
}
