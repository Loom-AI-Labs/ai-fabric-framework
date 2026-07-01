package com.ai.fabric.realapps.vectorreadiness.service;

import ai.fabric.rag.VectorDatabaseService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class VectorReadinessService {

    private static final String ENTITY_TYPE = "readiness-document";
    private static final String ENTITY_ID = "readiness-1";

    private final ObjectProvider<VectorDatabaseService> vectorDatabaseServiceProvider;

    public VectorReadinessService(ObjectProvider<VectorDatabaseService> vectorDatabaseServiceProvider) {
        this.vectorDatabaseServiceProvider = vectorDatabaseServiceProvider;
    }

    public ReadinessReport readiness() {
        VectorDatabaseService service = vectorDatabaseServiceProvider.getIfAvailable();
        if (service == null) {
            return new ReadinessReport("NOT_READY", "No VectorDatabaseService bean is available.", Map.of());
        }
        Map<String, Object> diagnostics = service.adminDiagnostics();
        boolean lifecycleReady = service.supportsExactFetchById()
            && service.supportsSearchMetadataFiltering()
            && service.supportsClearByEntityType();
        String status = lifecycleReady ? "READY" : "WARN";
        String message = lifecycleReady
            ? "Provider supports core lifecycle/admin checks."
            : "Provider is available but has lifecycle/admin caveats.";
        return new ReadinessReport(status, message, diagnostics);
    }

    public LifecycleRunResult runLifecycle() {
        VectorDatabaseService service = vectorDatabaseServiceProvider.getIfAvailable();
        if (service == null) {
            return new LifecycleRunResult(false, "NOT_READY", "No vector provider configured.", Map.of());
        }
        try {
            String vectorId = service.storeVector(
                ENTITY_TYPE,
                ENTITY_ID,
                "Readiness document for metadata filter and lifecycle checks",
                List.of(0.1d, 0.2d, 0.3d),
                Map.of("tenantId", "readiness", "source", "vector-readiness-playground")
            );
            boolean exists = service.vectorExists(ENTITY_TYPE, ENTITY_ID);
            boolean removed = service.removeVector(ENTITY_TYPE, ENTITY_ID);
            return new LifecycleRunResult(true, "READY", "Lifecycle scenario completed.", Map.of(
                "vectorId", vectorId,
                "existsAfterStore", exists,
                "removed", removed,
                "provider", service.vectorProviderName(),
                "metadataFilteredSearch", service.supportsSearchMetadataFiltering()
            ));
        } catch (Exception ex) {
            return new LifecycleRunResult(false, "NOT_READY", ex.getClass().getSimpleName(), Map.of(
                "provider", service.vectorProviderName()
            ));
        }
    }

    public record ReadinessReport(String status, String message, Map<String, Object> diagnostics) {}

    public record LifecycleRunResult(boolean success, String status, String message, Map<String, Object> evidence) {}
}
