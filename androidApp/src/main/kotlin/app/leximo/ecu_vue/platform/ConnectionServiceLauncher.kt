package app.leximo.ecu_vue.platform

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import app.leximo.ecu_vue.DiagnosticConnectionService
import app.leximo.ecuvue.core.DiagnosticException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Wait for startForeground before transport failure/cancellation can request service cleanup. */
class ConnectionServiceLauncher(private val context: Context) {
    @Volatile private var pending: CompletableDeferred<Unit>? = null

    suspend fun start() {
        val ready = CompletableDeferred<Unit>()
        pending = ready
        try {
            ContextCompat.startForegroundService(context, Intent(context, DiagnosticConnectionService::class.java))
            withTimeout(10_000) { ready.await() }
        } catch (error: TimeoutCancellationException) {
            // Do not stop a service that has not called startForeground yet. Its idle observer cleans up.
            throw DiagnosticException("assistantForegroundRequired")
        } catch (error: RuntimeException) {
            if (Build.VERSION.SDK_INT >= 31 && error is ForegroundServiceStartNotAllowedException)
                throw DiagnosticException("assistantForegroundRequired")
            if (error is SecurityException) throw DiagnosticException("assistantSetupRequired")
            throw error
        } finally { if (pending === ready) pending = null }
    }

    fun started() { pending?.complete(Unit) }
}
