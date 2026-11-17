@file:Suppress("MissingPermission")
package app.leximo.ecu_vue.transport

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.leximo.ecu_vue.*
import app.leximo.ecuvue.core.*
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val watchdog = Executors.newSingleThreadScheduledExecutor { action -> Thread(action, "ECU I/O deadline").apply { isDaemon = true } }

/** Closing the underlying handle unblocks I/O even when an OS read ignores interruption. */
internal suspend fun <T> boundedIo(timeoutMillis: Long, close: () -> Unit, action: () -> T): T = withContext(Dispatchers.IO) {
    val expired = AtomicBoolean(false)
    val timer = watchdog.schedule({ expired.set(true); runCatching(close) }, timeoutMillis, TimeUnit.MILLISECONDS)
    try {
        val value = runInterruptible { action() }
        if (expired.get()) throw DiagnosticException("timeout")
        value
    } catch (error: Throwable) {
        runCatching(close)
        when {
            error is CancellationException -> throw error
            expired.get() || error is SocketTimeoutException -> throw DiagnosticException("timeout", error.message, error)
            error is DiagnosticException -> throw error
            error is SecurityException -> throw DiagnosticException("permissionDenied", error.message, error)
            else -> throw DiagnosticException("connectionLost", error.message, error)
        }
    } finally { timer.cancel(false) }
}

abstract class BlockingPromptTransport : ElmTransport {
    private val commands = Mutex()
    protected abstract fun write(bytes: ByteArray)
    protected abstract fun read(buffer: ByteArray): Int
    protected abstract fun close()
    override suspend fun disconnect() = withContext(Dispatchers.IO) { close() }
    override suspend fun send(command: String): String = commands.withLock {
        if (!Regex("[A-Za-z0-9 ]{1,32}").matches(command)) throw DiagnosticException("commandFailed")
        boundedIo(if (command.trim() == "04") 45_000 else 18_000, ::close) {
            write((command + "\r").toByteArray(Charsets.US_ASCII))
            val response = StringBuilder()
            val buffer = ByteArray(4096)
            while (true) {
                val count = read(buffer)
                if (count < 0) throw IOException("Adapter closed the connection")
                for (index in 0 until count) {
                    val value = buffer[index].toInt() and 255
                    if (value == 62) return@boundedIo response.toString()
                    response.append(value.toChar())
                    if (response.length > 65536) throw IOException("Response exceeds 64 KiB")
                }
            }
            @Suppress("UNREACHABLE_CODE") ""
        }
    }
}

class ClassicTransport(private val device: BluetoothDevice) : BlockingPromptTransport() {
    @Volatile private var socket: BluetoothSocket? = null
    private val cancelled = AtomicBoolean(false)
    override suspend fun connect() {
        close(); cancelled.set(false)
        withContext(Dispatchers.IO) {
            try {
                val selected = connectRfcomm(
                    isPaired = { device.bondState == BluetoothDevice.BOND_BONDED }, isCancelled = cancelled::get,
                    create = { mode ->
                        val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                        val native = if (mode == RfcommMode.COMPATIBLE) device.createInsecureRfcommSocketToServiceRecord(spp)
                            else device.createRfcommSocketToServiceRecord(spp)
                        object : RfcommConnection {
                            override fun connect() {
                                val timer = watchdog.schedule({ runCatching { native.close() } }, 12, TimeUnit.SECONDS)
                                try { native.connect() } finally { timer.cancel(false) }
                            }
                            override fun close() { native.close() }
                            fun nativeSocket() = native
                        }.also { socket = native }
                    }, onAttempt = {},
                )
                if (cancelled.get()) { selected.close(); throw DiagnosticException("connectionLost") }
            } catch (error: PairingRequiredException) { throw DiagnosticException("pairingRequired", error.message) }
            catch (error: SecurityException) { throw DiagnosticException("permissionDenied", error.message) }
            catch (error: Exception) { close(); throw DiagnosticException("connectionFailed", error.message) }
        }
    }
    override fun write(bytes: ByteArray) { val stream = socket?.outputStream ?: throw IOException("Disconnected"); stream.write(bytes); stream.flush() }
    override fun read(buffer: ByteArray) = (socket?.inputStream ?: throw IOException("Disconnected")).read(buffer)
    override fun close() { cancelled.set(true); val old = socket; socket = null; runCatching { old?.close() } }
}

class WifiTransport(private val context: Context, private val host: String, private val port: Int) : BlockingPromptTransport() {
    @Volatile private var socket: Socket? = null
    override suspend fun connect() {
        require(host.isNotBlank() && host.length <= 253 && !host.contains(Regex("[\\s/]")) && port in 1..65535)
        boundedIo(15_000, ::close) {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            val network = manager.allNetworks.firstOrNull { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                ?: throw DiagnosticException("connectionFailed", "Join the OBD adapter's Wi-Fi network first.")
            val selected = network.socketFactory.createSocket()
            socket = selected
            selected.connect(InetSocketAddress(network.getAllByName(host).first(), port), 12_000)
            selected.tcpNoDelay = true
        }
    }
    override fun write(bytes: ByteArray) { val stream = socket?.outputStream ?: throw IOException("Disconnected"); stream.write(bytes); stream.flush() }
    override fun read(buffer: ByteArray) = (socket?.inputStream ?: throw IOException("Disconnected")).read(buffer)
    override fun close() { val old = socket; socket = null; runCatching { old?.close() } }
}

class UsbTransport(private val context: Context, private val device: UsbDevice, private val baud: Int) : BlockingPromptTransport() {
    @Volatile private var serial: UsbSerialPort? = null
    override suspend fun connect() {
        require(baud in listOf(9600, 38400, 57600, 115200))
        boundedIo(15_000, ::close) {
            val manager = context.getSystemService(UsbManager::class.java)
            if (!manager.hasPermission(device)) throw DiagnosticException("usbPermissionDenied")
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: throw DiagnosticException("connectionFailed", "Unsupported USB serial adapter")
            val connection = manager.openDevice(device) ?: throw DiagnosticException("usbPermissionDenied")
            val selected = driver.ports.first(); serial = selected
            try {
                selected.open(connection)
                selected.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                runCatching { selected.dtr = true; selected.rts = true }
            } catch (error: Throwable) { runCatching { selected.close() }; connection.close(); throw error }
        }
    }
    override fun write(bytes: ByteArray) { (serial ?: throw IOException("Disconnected")).write(bytes, 3000) }
    override fun read(buffer: ByteArray) = (serial ?: throw IOException("Disconnected")).read(buffer, 500)
    override fun close() { val old = serial; serial = null; runCatching { old?.close() } }
}
