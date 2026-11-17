@file:Suppress("MissingPermission")
package app.leximo.ecu_vue.di

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.hardware.usb.UsbManager
import app.leximo.ecu_vue.LauncherIcons
import app.leximo.ecu_vue.ai.AndroidAiAccessAvailability
import app.leximo.ecu_vue.data.*
import app.leximo.ecu_vue.platform.*
import app.leximo.ecu_vue.transport.*
import app.leximo.ecuvue.core.*
import app.leximo.ecuvue.di.ApplicationScope
import app.leximo.ecuvue.ui.*
import com.google.firebase.analytics.FirebaseAnalytics
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

private val UserPreferences = named("userPreferences")
private val LegacyPreferences = named("legacyPreferences")

val androidModule = module {
    single<SharedPreferences>(UserPreferences) { androidContext().getSharedPreferences("ecu_vue", Context.MODE_PRIVATE) }
    single<SharedPreferences>(LegacyPreferences) { androidContext().getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE) }
    single<SettingsRepository> { AndroidSettingsRepository(get(UserPreferences), get(LegacyPreferences)) }
    single<SnapshotStore> { AndroidSnapshotStore(get(UserPreferences)) }
    single<SavedAdapterRepository> { AndroidSavedAdapterRepository(get(UserPreferences)) }
    single { ActivityBridge() }
    single<BackNavigation> { get<ActivityBridge>() }
    single<PermissionRequests> { get<ActivityBridge>() }
    single { AndroidPermissions(androidContext(), get()) }
    single<AdapterDiscovery> { AndroidAdapterDiscovery(androidContext(), get()) }
    single<AiAccessAvailability> { AndroidAiAccessAvailability(androidContext()) }
    single<LauncherAppearance> { LauncherIcons(androidContext()) }
    single { FirebaseAnalytics.getInstance(androidContext()) }
    single<PlatformServices> { AndroidPlatformServices(androidContext(), get()) }
    single<Map<ConnectionKind, TransportProvider>> {
        val context = androidContext()
        val permissions = get<AndroidPermissions>()
        val bluetooth = context.getSystemService(BluetoothManager::class.java)
        val usb = context.getSystemService(UsbManager::class.java)
        mapOf(
            ConnectionKind.CLASSIC to TransportProvider { request ->
                permissions.requireBluetooth()
                ClassicTransport(bluetooth.adapter.getRemoteDevice(request.id))
            },
            ConnectionKind.BLE to TransportProvider { request ->
                permissions.requireBluetooth(true)
                BleTransport(context, bluetooth.adapter.getRemoteDevice(request.id))
            },
            ConnectionKind.WIFI to TransportProvider { request -> WifiTransport(context, request.id, request.port) },
            ConnectionKind.USB to TransportProvider { request ->
                val device = usb.deviceList.values.firstOrNull { it.deviceId.toString() == request.id }
                    ?: throw DiagnosticException("connectionFailed", "USB adapter removed")
                permissions.requireUsb(device)
                UsbTransport(context, device, request.baud)
            },
        )
    }
    single { ConnectionServiceLauncher(androidContext()) }
    single<ConnectionController> { AndroidConnectionController(androidContext(), get(), get(), get(), get(), get(ApplicationScope), get(), get()) }
    single { DiagnosticLifecycleObserver(get(), get(), get()) }
}
