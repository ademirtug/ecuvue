package app.leximo.ecu_vue.platform

import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.leximo.ecu_vue.DiagnosticConnectionService
import app.leximo.ecuvue.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AndroidConnectionController(
    private val context: Context,
    private val session: DiagnosticSession,
    private val discovery: AdapterDiscovery,
    private val factory: DiagnosticAdapterFactory,
    private val permissions: AndroidPermissions,
    private val scope: CoroutineScope,
    private val saved: SavedAdapterRepository,
    private val serviceLauncher: ConnectionServiceLauncher,
) : ConnectionController {
    private val connectionMutex = Mutex()
    @Volatile private var activeUsbDeviceId: Int? = null
    init {
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    else { @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) }
                if (device?.deviceId == activeUsbDeviceId && session.state.value.connected)
                    scope.launch { runCatching { session.stop() } }
            }
        }, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override suspend fun connect(request: ConnectionRequest) = connectionMutex.withLock {
        connectInternal(request, interactive = true)
        if (request.kind != ConnectionKind.SIMULATOR) {
            val device = if (request.kind == ConnectionKind.USB) context.getSystemService(UsbManager::class.java)
                .deviceList.values.firstOrNull { it.deviceId.toString() == request.id } else null
            saved.remember(SavedAdapter(request, usbVendorId = device?.vendorId, usbProductId = device?.productId,
                usbSerialNumber = device?.let { runCatching { it.serialNumber }.getOrNull() }))
        }
    }

    override suspend fun connectForAssistant(adapter: SavedAdapter, authorize: () -> Unit) {
        if (withTimeoutOrNull(5_000) { connectionMutex.lock(); true } != true) throw DiagnosticException("busy")
        try {
            authorize()
            // A simultaneous manual connection wins. Never replace an active session from an AI call.
            if (session.state.value.connected) return
            val request = if (adapter.request.kind == ConnectionKind.USB) {
                val usb = context.getSystemService(UsbManager::class.java)
                val matches = usb.deviceList.values.filter { device ->
                    device.vendorId == adapter.usbVendorId && device.productId == adapter.usbProductId &&
                        usb.hasPermission(device) && runCatching { device.serialNumber == adapter.usbSerialNumber }.getOrDefault(false)
                }
                if (matches.size != 1) throw DiagnosticException("assistantSetupRequired")
                adapter.request.copy(id = matches.single().deviceId.toString())
            } else adapter.request
            withTimeout(120_000) { connectInternal(request, interactive = false, authorize) }
        } catch (error: TimeoutCancellationException) {
            throw DiagnosticException("timeout")
        } finally { connectionMutex.unlock() }
    }

    private suspend fun connectInternal(request: ConnectionRequest, interactive: Boolean, authorize: () -> Unit = {}) {
        authorize()
        if (!interactive) {
            when (request.kind) {
                ConnectionKind.CLASSIC, ConnectionKind.BLE -> permissions.requireBluetooth(request.kind == ConnectionKind.BLE, interactive = false)
                ConnectionKind.USB -> {
                    val device = context.getSystemService(UsbManager::class.java).deviceList.values
                        .firstOrNull { it.deviceId.toString() == request.id } ?: throw DiagnosticException("assistantSetupRequired")
                    permissions.requireUsb(device, interactive = false)
                }
                ConnectionKind.WIFI -> Unit
                ConnectionKind.SIMULATOR -> throw DiagnosticException("savedAdapterUnsupported")
            }
        }
        discovery.stopDiscovery()
        val adapter = factory.create(request)
        if (request.kind == ConnectionKind.SIMULATOR) {
            session.connect(adapter)
            activeUsbDeviceId = null
            return
        }
        if (interactive) permissions.requireNotifications()
        authorize()
        serviceLauncher.start()
        activeUsbDeviceId = if (request.kind == ConnectionKind.USB) request.id.toInt() else null
        try { session.connect(adapter, authorize) }
        catch (error: Throwable) {
            activeUsbDeviceId = null
            context.stopService(Intent(context, DiagnosticConnectionService::class.java))
            throw error
        }
    }
}
