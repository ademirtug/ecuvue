package app.leximo.ecu_vue

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import app.leximo.ecuvue.core.DiagnosticSession
import app.leximo.ecuvue.di.ApplicationScope
import org.koin.android.ext.android.inject
import app.leximo.ecu_vue.platform.ConnectionServiceLauncher

/** Owns the visible connection lifetime; diagnostics live in the application, not an Activity. */
class DiagnosticConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session: DiagnosticSession by inject()
    private val applicationScope: CoroutineScope by inject(ApplicationScope)
    private val launcher: ConnectionServiceLauncher by inject()
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("diagnostic_connection", "Vehicle connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val disconnect = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction("disconnect"), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "diagnostic_connection").setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("ECU Vue").setContentText("Vehicle adapter connection active")
            .setContentIntent(open).setOngoing(true).addAction(0, "Disconnect", disconnect).build()
        ServiceCompat.startForeground(this, 327, notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        scope.launch {
            delay(1_000)
            session.state.collectLatest { if (it.simulated || (!it.connected && !it.busy)) stopSelf() }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        launcher.started() // onCreate has completed startForeground before any connector is allowed to run.
        if (intent?.action == "disconnect") scope.launch { runCatching { session.stop() }; stopSelf() }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        scope.cancel()
        // Do not leave an unadvertised physical connection after the service is stopped.
        if (session.state.value.connected && !session.state.value.simulated)
            applicationScope.launch { runCatching { session.stop() } }
        super.onDestroy()
    }
}
