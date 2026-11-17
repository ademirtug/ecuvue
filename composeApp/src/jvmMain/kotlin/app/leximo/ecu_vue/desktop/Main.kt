package app.leximo.ecu_vue.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.leximo.ecuvue.ui.AppHost
import app.leximo.ecuvue.di.startDemoKoin
import app.leximo.ecuvue.ui.EcuVueApp
import java.util.Locale
import org.koin.core.context.stopKoin

fun main() {
    val container = startDemoKoin("Desktop", Locale.getDefault().language)
    try {
        application {
            val host = remember { container.koin.get<AppHost>() }
            Window(onCloseRequest = ::exitApplication, title = "ECU Vue") { EcuVueApp(host) }
        }
    } finally { stopKoin() }
}
