package app.leximo.ecu_vue.platform

import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.leximo.ecuvue.ui.PlatformServices
import com.google.firebase.analytics.FirebaseAnalytics
import java.util.Locale

class AndroidPlatformServices(private val context: Context, private val analytics: FirebaseAnalytics) : PlatformServices {
    override val platform = "Android"
    override val systemLanguage get() = Locale.getDefault().language
    override fun openBluetoothSettings() { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    override fun openWifiSettings() { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    override fun screenViewed(name: String) {
        // Stable route names only; never log vehicle readings or identifiers.
        runCatching { analytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW,
            android.os.Bundle().apply { putString(FirebaseAnalytics.Param.SCREEN_NAME, name); putString(FirebaseAnalytics.Param.SCREEN_CLASS, "EcuVue") }) }
    }
}
