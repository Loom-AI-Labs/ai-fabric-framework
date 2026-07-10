package com.ai.fabric.realapps.chat.demo.web;

import com.ai.fabric.realapps.chat.demo.service.DemoReadinessService;
import com.ai.fabric.realapps.chat.demo.service.DemoStage;
import com.ai.fabric.realapps.chat.demo.service.DemoStageSeedService;
import com.ai.fabric.realapps.chat.migration.service.DemoDataResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
public class DemoController {

    private final DemoReadinessService readinessService;
    private final DemoStageSeedService stageSeedService;
    private final DemoDataResetService resetService;
    private final Environment environment;
    private final ResourceLoader resourceLoader;

    @Value("${app.demo.controls.enabled:true}")
    private boolean demoControlsEnabled;

    @Value("${app.demo.controls.api-key:}")
    private String demoControlsApiKey;

    @Value("${app.demo.controls.api-key-header:X-DEMO-API-KEY}")
    private String demoControlsApiKeyHeader;

    @Value("${spring.application.name:chat-capabilities-demo}")
    private String appName;

    @Value("${project.version:1.0.0-SNAPSHOT}")
    private String appVersion;

    @Value("${ai-fabric.version:0.3.3}")
    private String aiFabricVersion;

    @Value("${app.build-info.path:file:/app/build-info.properties}")
    private String buildInfoPath;

    @GetMapping("/readiness")
    public DemoReadinessService.ReadinessReport readiness() {
        return readinessService.readiness();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("app", appName);
        out.put("version", appVersion);
        out.put("aiFabricVersion", aiFabricVersion);
        BuildInfo buildInfo = loadBuildInfo();
        out.put("commit", buildValue(buildInfo, "commit", "APP_BUILD_COMMIT", "GIT_COMMIT", "SOURCE_COMMIT", "COMMIT_SHA"));
        out.put("buildBranch", buildValue(buildInfo, "branch", "APP_BUILD_BRANCH", "GIT_BRANCH", "SOURCE_BRANCH", "BRANCH_NAME"));
        out.put("buildTime", buildValue(buildInfo, "builtAt", "APP_BUILD_TIME", "BUILD_TIME", "SOURCE_BUILD_TIME"));
        out.put("buildMetadataSource", buildInfo.source());
        out.put("checkedAt", Instant.now().toString());
        out.put("demoControlsEnabled", demoControlsEnabled);
        out.put("chatSessionEnabled", environment.getProperty("ai.chat.enabled", Boolean.class, true));
        out.put("ragEnabled", environment.getProperty("ai.service.features.enable-rag", Boolean.class, true));
        out.put("dataSyncEnabled", environment.getProperty("ai.data-sync.enabled", Boolean.class, true));
        out.put("vectorProvider", environment.getProperty("ai.vector-db.type", "unknown"));
        out.put("readiness", readinessService.readiness());
        return out;
    }

    @PostMapping("/stages/{stage}")
    public ResponseEntity<?> seedStage(@PathVariable String stage, HttpServletRequest request) {
        ResponseEntity<?> denied = requireDemoControls(request);
        if (denied != null) {
            return denied;
        }
        try {
            return ResponseEntity.ok(stageSeedService.seed(DemoStage.fromPath(stage)));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ex.getMessage()
            ));
        }
    }

    @PostMapping("/reset")
    public ResponseEntity<?> reset(@Valid @RequestBody ResetRequest request, HttpServletRequest httpRequest) {
        ResponseEntity<?> denied = requireDemoControls(httpRequest);
        if (denied != null) {
            return denied;
        }
        if (!Boolean.TRUE.equals(request.getConfirm())) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", "confirm=true is required to reset demo data"
            ));
        }
        boolean clearVectors = request.getClearVectors() == null || request.getClearVectors();
        boolean clearIndexingQueue = request.getClearIndexingQueue() == null || request.getClearIndexingQueue();
        return ResponseEntity.ok(Map.of(
            "success", true,
            "result", resetService.clearDemoData(clearVectors, clearIndexingQueue),
            "readiness", readinessService.readiness()
        ));
    }

    private ResponseEntity<?> requireDemoControls(HttpServletRequest request) {
        if (!demoControlsEnabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "success", false,
                "message", "Demo controls are disabled"
            ));
        }
        if (!StringUtils.hasText(demoControlsApiKey)) {
            return null;
        }
        String header = StringUtils.hasText(demoControlsApiKeyHeader) ? demoControlsApiKeyHeader : "X-DEMO-API-KEY";
        String provided = request != null ? request.getHeader(header) : null;
        if (!StringUtils.hasText(provided) || !MessageDigest.isEqual(
            demoControlsApiKey.trim().getBytes(StandardCharsets.UTF_8),
            provided.trim().getBytes(StandardCharsets.UTF_8)
        )) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "success", false,
                "message", "Unauthorized"
            ));
        }
        return null;
    }

    private BuildInfo loadBuildInfo() {
        if (!StringUtils.hasText(buildInfoPath) || resourceLoader == null) {
            return BuildInfo.environmentFallback();
        }
        Resource resource = resourceLoader.getResource(buildInfoPath.trim());
        if (!resource.exists()) {
            return BuildInfo.environmentFallback();
        }
        Properties properties = new Properties();
        try (InputStream input = resource.getInputStream()) {
            properties.load(input);
        } catch (Exception ignored) {
            return BuildInfo.environmentFallback();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            String value = properties.getProperty(name);
            if (StringUtils.hasText(name) && StringUtils.hasText(value)) {
                values.put(name.trim(), value.trim());
            }
        }
        return new BuildInfo(buildInfoPath.trim(), values);
    }

    private String buildValue(BuildInfo buildInfo, String key, String... fallbackKeys) {
        if (buildInfo != null && buildInfo.fromBuildInfoFile()) {
            String value = buildInfo.values().get(key);
            return StringUtils.hasText(value) ? value.trim() : "unknown";
        }
        return firstConfigured(fallbackKeys);
    }

    private String firstConfigured(String... keys) {
        for (String key : keys) {
            String value = environment.getProperty(key);
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "unknown";
    }

    private record BuildInfo(String source, Map<String, String> values) {
        private static BuildInfo environmentFallback() {
            return new BuildInfo("environment", Map.of());
        }

        private boolean fromBuildInfoFile() {
            return !"environment".equals(source);
        }
    }

    @Data
    public static class ResetRequest {
        @NotNull
        private Boolean confirm;
        private Boolean clearVectors = true;
        private Boolean clearIndexingQueue = true;
    }
}
