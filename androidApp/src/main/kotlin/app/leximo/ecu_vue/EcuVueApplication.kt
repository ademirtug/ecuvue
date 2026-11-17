package app.leximo.ecu_vue

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import app.leximo.ecu_vue.di.androidModule
import app.leximo.ecu_vue.platform.DiagnosticLifecycleObserver
import app.leximo.ecuvue.core.SettingsObserver
import app.leximo.ecuvue.core.ReferenceCatalog
import app.leximo.ecuvue.di.sharedModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class EcuVueApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val container = startKoin {
            androidContext(this@EcuVueApplication)
            modules(sharedModule, androidModule)
        }
        container.koin.get<SettingsObserver>().start()
        container.koin.get<ReferenceCatalog>().load()
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.koin.get<DiagnosticLifecycleObserver>())
    }
}
