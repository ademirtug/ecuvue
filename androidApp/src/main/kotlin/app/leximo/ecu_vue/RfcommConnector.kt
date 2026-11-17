package app.leximo.ecu_vue

import java.io.IOException

internal enum class RfcommMode { COMPATIBLE, AUTHENTICATED }

internal interface RfcommConnection {
    fun connect()
    fun close()
}

internal class PairingRequiredException : IOException("Pair the adapter in Android Bluetooth settings first.")
internal class ConnectionCancelledException : IOException("Bluetooth connection was cancelled.")

/** Only already-paired devices are eligible. No PIN is guessed or supplied.
 * Legacy serial modules may not support authenticated RFCOMM, even with a
 * valid Android bond, so try the public compatibility API before secure SPP.
 */
internal fun connectRfcomm(
    isPaired: () -> Boolean,
    isCancelled: () -> Boolean,
    create: (RfcommMode) -> RfcommConnection,
    onAttempt: (RfcommConnection) -> Unit,
): RfcommConnection {
    val failures = mutableListOf<String>()
    for (mode in RfcommMode.entries) {
        if (isCancelled()) throw ConnectionCancelledException()
        if (!isPaired()) throw PairingRequiredException()
        var candidate: RfcommConnection? = null
        try {
            candidate = create(mode)
            onAttempt(candidate)
            if (isCancelled()) throw ConnectionCancelledException()
            candidate.connect()
            if (isCancelled()) throw ConnectionCancelledException()
            return candidate
        } catch (error: Exception) {
            try { candidate?.close() } catch (_: Exception) {}
            if (isCancelled() || error is ConnectionCancelledException) throw ConnectionCancelledException()
            if (error !is IOException) throw error
            failures += "${mode.name.lowercase()}: ${error.message ?: error.javaClass.simpleName}"
        }
    }
    throw IOException(failures.joinToString("; "))
}
