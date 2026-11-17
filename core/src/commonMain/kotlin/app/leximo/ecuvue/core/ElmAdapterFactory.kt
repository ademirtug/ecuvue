package app.leximo.ecuvue.core

/** Transport implementations are registered at the platform composition root. */
class ElmAdapterFactory(
    private val providers: Map<ConnectionKind, TransportProvider>,
    private val references: ReferenceRepository,
) : DiagnosticAdapterFactory {
    override suspend fun create(request: ConnectionRequest): DiagnosticAdapter {
        if (request.kind == ConnectionKind.SIMULATOR) return SimulatedAdapter()
        val provider = providers[request.kind] ?: throw DiagnosticException("connectionFailed", "Unsupported connection: ${request.kind}")
        val transport = provider.create(request)
        val manufacturers = references.manufacturers()
        return ElmDiagnosticAdapter(transport, request.name.ifBlank { request.id }, manufacturers::identify)
    }
}
