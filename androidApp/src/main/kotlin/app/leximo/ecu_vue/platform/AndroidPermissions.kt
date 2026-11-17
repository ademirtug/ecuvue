@file:Suppress("MissingPermission")
package app.leximo.ecu_vue.platform

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.leximo.ecuvue.core.DiagnosticException
import kotlinx.coroutines.*

class AndroidPermissions(private val context: Context, private val requests: PermissionRequests) {
    private val bluetooth get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val usb get() = context.getSystemService(UsbManager::class.java)
    suspend fun requireBluetooth(scan: Boolean = false, interactive: Boolean = true) {
        val required = if (Build.VERSION.SDK_INT >= 31) {
            if (scan) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN) else arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else if (scan) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION) else emptyArray()
        val missing = required.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
        if (missing.isNotEmpty()) {
            if (!interactive || !isForeground()) throw DiagnosticException("assistantSetupRequired")
            requests.request(missing)
        }
        if (required.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }) throw DiagnosticException("permissionDenied")
        val adapter = bluetooth ?: throw DiagnosticException("bluetoothUnavailable")
        if (!adapter.isEnabled) throw DiagnosticException("bluetoothOff")
        if (scan && Build.VERSION.SDK_INT < 31 && !context.getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            !context.getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.NETWORK_PROVIDER)) throw DiagnosticException("bleLocationRequired")
    }
    suspend fun requireUsb(device: UsbDevice, interactive: Boolean = true) {
        if (usb.hasPermission(device)) return
        if (!interactive || !isForeground()) throw DiagnosticException("assistantSetupRequired")
        withTimeout(60_000) {
            val granted = CompletableDeferred<Boolean>()
            val action = "${context.packageName}.USB_PERMISSION"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val selected = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        else { @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) }
                    if (selected?.deviceId == device.deviceId) granted.complete(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                usb.requestPermission(device, PendingIntent.getBroadcast(context, device.deviceId, Intent(action).setPackage(context.packageName), flags))
                if (!granted.await() || !usb.hasPermission(device)) throw DiagnosticException("usbPermissionDenied")
            } finally { context.unregisterReceiver(receiver) }
        }
    }

    suspend fun requireNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            try { requests.request(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
            catch (error: DiagnosticException) { if (error.key != "permissionDenied") throw error }
        }
    }
    private fun isForeground() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
