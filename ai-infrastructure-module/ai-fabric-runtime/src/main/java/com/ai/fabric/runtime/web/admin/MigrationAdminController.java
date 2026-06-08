package com.ai.fabric.runtime.web.admin;

import com.ai.fabric.runtime.auth.RuntimeRequestAuthResolver;
import com.ai.infrastructure.rag.VectorDatabaseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin endpoints intended for demo/runtime maintenance.
 *
 * <p>Note: the "commerce demo reset" controller lives in a Real_App. This runtime-only version
 * focuses on clearing the vector index so you can re-index cleanly.</p>
 */
@RestController
@RequestMapping("/api/admin/migration")
public class MigrationAdminController {

    private final ObjectProvider<VectorDatabaseService> vectorDatabaseServiceProvider;
    private final RuntimeRequestAuthResolver runtimeRequestAuthResolver;

    public MigrationAdminController(ObjectProvider<VectorDatabaseService> vectorDatabaseServiceProvider,
                                    RuntimeRequestAuthResolver runtimeRequestAuthResolver) {
        this.vectorDatabaseServiceProvider = vectorDatabaseServiceProvider;
        this.runtimeRequestAuthResolver = runtimeRequestAuthResolver;
    }

    /**
     * Clear vectors from the configured vector DB.
     *
     * <p>To avoid accidental wipes on public deployments, this requires {@code confirm=true}
     * either in JSON body or as a query param.</p>
     */
    @PostMapping("/clear")
    public ResponseEntity<?> clear(@RequestParam(value = "confirm", required = false) Boolean confirmParam,
                                   @RequestParam(value = "clearVectors", required = false) Boolean clearVectorsParam,
                                   @RequestBody(required = false) ClearRequest body,
                                   HttpServletRequest httpRequest) {
        runtimeRequestAuthResolver.requireScope(
            runtimeRequestAuthResolver.resolveVerifiedPrivateContext(httpRequest, "/api/admin/migration/clear"),
            RuntimeAdminScopeCatalog.RUNTIME_MIGRATION_CLEAR,
            "/api/admin/migration/clear"
        );

        Boolean confirm = body != null ? body.getConfirm() : null;
        if (confirm == null) {
            confirm = confirmParam;
        }
        if (!Boolean.TRUE.equals(confirm)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "success", false,
                "message", "confirm=true is required"
            ));
        }

        Boolean clearVectors = body != null ? body.getClearVectors() : null;
        if (clearVectors == null) {
            clearVectors = clearVectorsParam;
        }
        if (clearVectors == null) {
            clearVectors = true;
        }

        String reason = body != null ? body.getReason() : null;
        if (StringUtils.hasText(reason)) {
            reason = reason.trim();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clearedVectors", false);
        result.put("removedVectors", 0L);

        VectorDatabaseService vectorDatabaseService = vectorDatabaseServiceProvider.getIfAvailable();
        if (vectorDatabaseService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "success", false,
                "message", "No VectorDatabaseService is configured for this runtime."
            ));
        }

        if (Boolean.TRUE.equals(clearVectors)) {
            long removed = vectorDatabaseService.clearVectors();
            result.put("clearedVectors", true);
            result.put("removedVectors", removed);
        }

        String message = "Migration clear completed";
        if (StringUtils.hasText(vectorDatabaseService.getClass().getSimpleName())) {
            message = message + " (" + vectorDatabaseService.getClass().getSimpleName() + ")";
        }

        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", message,
            "result", result
        ));
    }

    @Data
    public static class ClearRequest {
        @NotNull
        private Boolean confirm;
        private Boolean clearVectors = true;
        private String reason;
    }
}
