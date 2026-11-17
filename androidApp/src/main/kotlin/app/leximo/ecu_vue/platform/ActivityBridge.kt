package app.leximo.ecu_vue.platform

import app.leximo.ecuvue.core.DiagnosticException
import app.leximo.ecuvue.ui.BackNavigation

interface PermissionRequests { suspend fun request(permissions: Array<String>) }

/** Only this bridge can retain an Activity callback, and attachment has an explicit owner. */
class ActivityBridge : PermissionRequests, BackNavigation {
    private var owner: Any? = null
    private var requester: (suspend (Array<String>) -> Unit)? = null
    private var backAction: (() -> Unit)? = null
    fun attach(owner: Any, requester: suspend (Array<String>) -> Unit) {
        this.owner = owner
        this.requester = requester
        backAction = null
    }
    fun detach(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        requester = null
        backAction = null
    }
    override suspend fun request(permissions: Array<String>) {
        (requester ?: throw DiagnosticException("permissionDenied"))(permissions)
    }
    override fun setBackAction(action: (() -> Unit)?) { backAction = action }
    fun handleBack(): Boolean { val action = backAction ?: return false; action(); return true }
}
