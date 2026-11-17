@file:Suppress("MissingPermission")
package app.leximo.ecu_vue.platform

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.hardware.usb.UsbManager
import app.leximo.ecuvue.core.*
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.*

class AndroidAdapterDiscovery(private val context: Context, private val permissions: AndroidPermissions) : AdapterDiscovery {
    override val connections = ConnectionKind.entries.toSet()
    @Volatile private var scanJob: Job? = null
    private val bluetooth get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val usb get() = context.getSystemService(UsbManager::class.java)
    override suspend fun pairedDevices(): List<AdapterDevice> {
        permissions.requireBluetooth()
        return bluetooth!!.bondedDevices.sortedBy { it.name ?: it.address }.map {
            AdapterDevice(it.address, it.name ?: it.address,
                if (it.type == BluetoothDevice.DEVICE_TYPE_LE) ConnectionKind.BLE else ConnectionKind.CLASSIC)
        }
    }
    override suspend fun bleDevices(): List<AdapterDevice> = coroutineScope {
        permissions.requireBluetooth(true)
        val scanner = bluetooth!!.bluetoothLeScanner ?: throw DiagnosticException("bluetoothUnavailable")
        val found = java.util.concurrent.ConcurrentHashMap<String, AdapterDevice>()
        var failure: DiagnosticException? = null
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name.orEmpty()
                val services = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid.toString().lowercase() }
                if (Regex("OBD|ELM|VLINK|VGATE|VEEPEAK", RegexOption.IGNORE_CASE).containsMatchIn(name) ||
                    services.any { it.startsWith("0000fff0") || it.startsWith("0000ffe0") || it.startsWith("6e400001") })
                    found[result.device.address] = AdapterDevice(result.device.address, name.ifBlank { result.device.address }, ConnectionKind.BLE)
            }
            override fun onScanFailed(errorCode: Int) { failure = DiagnosticException("connectionFailed", "BLE scan error $errorCode") }
        }
        scanJob = currentCoroutineContext()[Job]
        try { scanner.startScan(callback); delay(15_000); failure?.let { throw it }; found.values.sortedBy { it.name } }
        finally { runCatching { scanner.stopScan(callback) }; scanJob = null }
    }
    override fun stopDiscovery() { scanJob?.cancel(); scanJob = null }
    override suspend fun usbDevices(): List<AdapterDevice> = withContext(Dispatchers.IO) {
        UsbSerialProber.getDefaultProber().findAllDrivers(usb).map { AdapterDevice(it.device.deviceId.toString(), it.device.productName ?: it.device.deviceName, ConnectionKind.USB) }
    }
}
