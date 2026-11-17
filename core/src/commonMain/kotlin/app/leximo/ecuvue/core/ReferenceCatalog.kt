package app.leximo.ecuvue.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

sealed interface CatalogState {
    data object Loading : CatalogState
    data class Ready(val reference: CodeReference) : CatalogState
    data class Failed(val message: String) : CatalogState
}

/** Application-owned background loader; observers receive the complete catalog atomically. */
class ReferenceCatalog(
    private val scope: CoroutineScope,
    private val loadReference: suspend () -> CodeReference,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val loading = Mutex()
    private val mutableState = MutableStateFlow<CatalogState>(CatalogState.Loading)
    val state = mutableState.asStateFlow()

    /** Starts immediately, coalesces concurrent requests, and permits retry after failure. */
    fun load() {
        if (!loading.tryLock()) return
        if (mutableState.value is CatalogState.Ready) { loading.unlock(); return }
        mutableState.value = CatalogState.Loading
        scope.launch(dispatcher) {
            try { mutableState.value = CatalogState.Ready(loadReference()) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutableState.value = CatalogState.Failed(error.message ?: "Could not load code catalog")
            }
        }.invokeOnCompletion { loading.unlock() }
    }
}
