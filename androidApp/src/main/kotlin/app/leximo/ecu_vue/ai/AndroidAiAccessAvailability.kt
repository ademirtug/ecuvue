@file:OptIn(androidx.appfunctions.ExperimentalAppFunctionsApi::class)
package app.leximo.ecu_vue.ai

import android.content.Context
import android.os.Build
import androidx.appfunctions.AppFunctionManager
import app.leximo.ecuvue.core.AiAccessAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class AndroidAiAccessAvailability(private val context: Context) : AiAccessAvailability {
    override val supported get() = Build.VERSION.SDK_INT >= 36 && AppFunctionManager.getInstance(context) != null
    override suspend fun synchronize(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < 36) return
        val manager = AppFunctionManager.getInstance(context) ?: return
        repeat(5) { attempt ->
            try {
                for (id in DiagnosticFunctionIds.all) manager.setAppFunctionEnabled(id,
                    if (enabled) AppFunctionManager.APP_FUNCTION_STATE_ENABLED else AppFunctionManager.APP_FUNCTION_STATE_DISABLED)
                return
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (attempt < 4) delay((attempt + 1) * 500L)
                else android.util.Log.i("EcuVueFunctions", "Function indexing is not ready; the runtime opt-in gate remains authoritative.")
            }
        }
    }
}
