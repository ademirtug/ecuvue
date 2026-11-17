@file:Suppress("MissingPermission", "DEPRECATION")
package app.leximo.ecu_vue.transport

import android.bluetooth.*
import android.content.Context
import android.os.Build
import app.leximo.ecuvue.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class BleTransport(private val context: Context, private val device: BluetoothDevice) : ElmTransport {
    private data class Profile(val service: UUID, val write: UUID, val notify: UUID)
    private fun uuid(short: String): UUID = UUID.fromString(if (short.length == 4) "0000$short-0000-1000-8000-00805f9b34fb" else short)
    private val profiles = listOf(
        Profile(uuid("fff0"), uuid("fff2"), uuid("fff1")),
        Profile(uuid("ffe0"), uuid("ffe1"), uuid("ffe1")),
        Profile(uuid("6e400001-b5a3-f393-e0a9-e50e24dcca9e"), uuid("6e400002-b5a3-f393-e0a9-e50e24dcca9e"), uuid("6e400003-b5a3-f393-e0a9-e50e24dcca9e")),
    )
    @Volatile private var gatt: BluetoothGatt? = null
    private var connected = CompletableDeferred<Unit>()
    private var discovered = CompletableDeferred<Unit>()
    private var subscribed = CompletableDeferred<Unit>()
    private var pendingWrite: CompletableDeferred<Unit>? = null
    private var input = Channel<ByteArray>(64)
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var withResponse = true
    private val commands = Mutex()
    private fun failed(error: DiagnosticException) {
        connected.completeExceptionally(error); discovered.completeExceptionally(error)
        subscribed.completeExceptionally(error); pendingWrite?.completeExceptionally(error)
        input.close(error)
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) connected.complete(Unit)
            else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS)
                failed(DiagnosticException("connectionLost", "BLE GATT status $status"))
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) discovered.complete(Unit) else failed(DiagnosticException("bleUnsupported", "Service discovery status $status"))
        }
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) subscribed.complete(Unit) else failed(DiagnosticException("bleUnsupported", "Notification setup status $status"))
        }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) pendingWrite?.complete(Unit)
            else pendingWrite?.completeExceptionally(DiagnosticException("commandFailed", "BLE write status $status"))
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { receive(value) }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) receive(characteristic.value ?: byteArrayOf())
        }
    }
    private fun receive(value: ByteArray) {
        if (!input.trySend(value.copyOf()).isSuccess) failed(DiagnosticException("commandFailed", "BLE notification buffer exceeded"))
    }
    override suspend fun connect() = withContext(Dispatchers.IO) {
        connected = CompletableDeferred(); discovered = CompletableDeferred(); subscribed = CompletableDeferred(); input = Channel(64)
        try {
            withTimeout(30_000) {
                val selected = device.connectGatt(context.applicationContext, false, callback, BluetoothDevice.TRANSPORT_LE)
                    ?: throw DiagnosticException("connectionFailed")
                gatt = selected
                withTimeout(12_000) { connected.await() }
                if (!selected.discoverServices()) throw DiagnosticException("bleUnsupported")
                discovered.await()
                var notification: BluetoothGattCharacteristic? = null
                for (profile in profiles) {
                    val service = selected.getService(profile.service) ?: continue
                    val write = service.getCharacteristic(profile.write) ?: continue
                    val notify = service.getCharacteristic(profile.notify) ?: continue
                    if (write.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0 ||
                        notify.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) == 0) continue
                    writeCharacteristic = write; notification = notify
                    withResponse = write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                    break
                }
                val notify = notification ?: throw DiagnosticException("bleUnsupported")
                if (!selected.setCharacteristicNotification(notify, true)) throw DiagnosticException("bleUnsupported")
                val descriptor = notify.getDescriptor(uuid("2902")) ?: throw DiagnosticException("bleUnsupported")
                val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                val accepted = if (Build.VERSION.SDK_INT >= 33) selected.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                    else { descriptor.value = value; selected.writeDescriptor(descriptor) }
                if (!accepted) throw DiagnosticException("bleUnsupported")
                subscribed.await()
            }
        } catch (error: Throwable) { withContext(NonCancellable) { disconnect() }; throw error }
    }
    override suspend fun send(command: String): String = commands.withLock {
        if (!Regex("[A-Za-z0-9 ]{1,32}").matches(command)) throw DiagnosticException("commandFailed")
        try {
            withTimeout(if (command.trim() == "04") 45_000 else 18_000) {
                val native = gatt ?: throw DiagnosticException("connectionLost")
                val write = writeCharacteristic ?: throw DiagnosticException("connectionLost")
                // Discard unsolicited data before writing, never after a request has started.
                while (input.tryReceive().isSuccess) { }
                val writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                for (chunk in (command + "\r").toByteArray(Charsets.US_ASCII).toList().chunked(20)) {
                    val bytes = chunk.toByteArray()
                    val ack = CompletableDeferred<Unit>(); pendingWrite = ack
                    val accepted = if (Build.VERSION.SDK_INT >= 33) native.writeCharacteristic(write, bytes, writeType) == BluetoothStatusCodes.SUCCESS
                        else { write.writeType = writeType; write.value = bytes; native.writeCharacteristic(write) }
                    if (!accepted) throw DiagnosticException("commandFailed", "BLE write was not accepted")
                    if (withResponse) withTimeout(3_000) { ack.await() } else delay(10)
                    pendingWrite = null
                }
                val result = StringBuilder()
                while (true) {
                    for (byte in input.receive()) {
                        val value = byte.toInt() and 255
                        if (value == 62) return@withTimeout result.toString()
                        result.append(value.toChar())
                        if (result.length > 65536) throw DiagnosticException("commandFailed", "Response exceeds 64 KiB")
                    }
                }
                @Suppress("UNREACHABLE_CODE") ""
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) { disconnect() }
            if (error is TimeoutCancellationException) throw DiagnosticException("timeout")
            throw error
        }
    }
    override suspend fun disconnect() {
        val old = gatt; gatt = null; writeCharacteristic = null
        runCatching { old?.disconnect() }; runCatching { old?.close() }
        failed(DiagnosticException("connectionLost"))
    }
}
