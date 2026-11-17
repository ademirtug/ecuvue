package app.leximo.ecu_vue.platform

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.leximo.ecuvue.core.*

class DiagnosticLifecycleObserver(
    private val session: DiagnosticSession,
    private val discovery: AdapterDiscovery,
    private val appearance: LauncherAppearance,
) : DefaultLifecycleObserver {
    // An assistant may cold-start the process without ever starting an Activity/onStop callback.
    init { session.setForeground(false) }
    override fun onStart(owner: LifecycleOwner) { session.setForeground(true) }
    override fun onStop(owner: LifecycleOwner) {
        discovery.stopDiscovery()
        session.setForeground(false)
        // Disabling a launch alias can finish its task; only switch icons in the background.
        runCatching { appearance.refresh() }.onFailure { android.util.Log.w("ECUVue", "Could not update the launcher icon", it) }
    }
}
