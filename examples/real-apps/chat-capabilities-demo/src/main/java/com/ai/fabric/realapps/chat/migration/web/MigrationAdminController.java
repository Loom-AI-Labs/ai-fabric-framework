package com.ai.fabric.realapps.chat.migration.web;

import com.ai.fabric.realapps.chat.migration.service.DemoDataResetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/migration")
@RequiredArgsConstructor
public class MigrationAdminController {

    private final DemoDataResetService demoDataResetService;

    /**
     * Clear demo commerce data + related vectors (migration reset).
     *
     * <p>This is a destructive operation intended for local demo resets.</p>
     */
    @PostMapping("/clear")
    public ResponseEntity<?> clear(@Valid @RequestBody ClearRequest request) {
        if (!Boolean.TRUE.equals(request.getConfirm())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "success", false,
                "message", "confirm=true is required to clear demo data"
            ));
        }

        boolean clearVectors = request.getClearVectors() == null || request.getClearVectors();
        boolean clearIndexingQueue = request.getClearIndexingQueue() == null || request.getClearIndexingQueue();

        return ResponseEntity.ok(Map.of(
            "success", true,
            "result", demoDataResetService.clearDemoData(clearVectors, clearIndexingQueue)
        ));
    }

    @Data
    public static class ClearRequest {
        @NotNull
        private Boolean confirm;
        private Boolean clearVectors = true;
        private Boolean clearIndexingQueue = true;
    }
}
