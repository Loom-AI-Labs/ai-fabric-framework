package com.ai.fabric.realapps.behavior.web;

import com.ai.fabric.realapps.behavior.service.AgenticUiComposerService;
import com.ai.fabric.realapps.behavior.service.BehaviorDemoScenarioService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

@RestController
@RequestMapping("/api/behavior-demo")
@RequiredArgsConstructor
public class BehaviorDemoController {

    private final BehaviorDemoScenarioService service;
    private final AgenticUiComposerService agenticUiComposerService;
    private final Environment environment;
    private final ResourceLoader resourceLoader;

    @Value("${spring.application.name:behavior-churn-signals}")
    private String appName;

    @Value("${app.version:${APP_VERSION:1.0.0-SNAPSHOT}}")
    private String appVersion;

    @Value("${AI_FABRIC_VERSION:${ai-fabric.version:unknown}}")
    private String aiFabricVersion;

    @Value("${app.build-info.path:file:/app/build-info.properties}")
    private String buildInfoPath;

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        BuildInfo buildInfo = loadBuildInfo();
        String provider = environment.getProperty("ai.providers.llm-provider", "unknown");
        out.put("app", appName);
        out.put("version", buildValue(buildInfo, "version", "APP_VERSION"));
        out.put("aiFabricVersion", buildValue(buildInfo, "aiFabricVersion", "AI_FABRIC_VERSION"));
        out.put("commit", buildValue(buildInfo, "commit", "APP_BUILD_COMMIT", "GIT_COMMIT", "SOURCE_COMMIT", "COMMIT_SHA"));
        out.put("buildBranch", buildValue(buildInfo, "branch", "APP_BUILD_BRANCH", "GIT_BRANCH", "SOURCE_BRANCH", "BRANCH_NAME"));
        out.put("buildTime", buildValue(buildInfo, "builtAt", "APP_BUILD_TIME", "BUILD_TIME", "SOURCE_BUILD_TIME"));
        out.put("buildMetadataSource", buildInfo.source());
        out.put("provider", provider);
        out.put("providerMode", providerMode(provider));
        out.put("behaviorEnabled", environment.getProperty("ai.behavior.enabled", Boolean.class, false));
        out.put("behaviorMode", environment.getProperty("ai.behavior.mode", "unknown"));
        BehaviorDemoScenarioService.BehaviorDemoDashboard dashboard = service.dashboard();
        out.put("totalEvents", dashboard.totalEvents());
        out.put("insights", dashboard.insights().size());
        out.put("scenarios", dashboard.scenarios().size());
        out.put("checkedAt", Instant.now().toString());
        return out;
    }

    @GetMapping("/dashboard")
    public BehaviorDemoScenarioService.BehaviorDemoDashboard dashboard(@RequestParam(value = "sessionId", required = false) String sessionId) {
        return service.dashboard(sessionId);
    }

    @GetMapping("/scenarios")
    public java.util.List<BehaviorDemoScenarioService.DemoScenarioSummary> scenarios(@RequestParam(value = "sessionId", required = false) String sessionId) {
        return service.dashboard(sessionId).scenarios();
    }

    @PostMapping("/sessions")
    public BehaviorDemoScenarioService.DemoSessionResponse createSession(
        @RequestBody(required = false) BehaviorDemoScenarioService.CreateDemoSessionRequest request
    ) {
        return service.createSession(request);
    }

    @PostMapping("/seed")
    public BehaviorDemoScenarioService.BehaviorDemoDashboard seed(@RequestParam(value = "sessionId", required = false) String sessionId) {
        return service.seed(sessionId);
    }

    @PostMapping("/seed-and-analyze")
    public BehaviorDemoScenarioService.BehaviorDemoDashboard seedAndAnalyze(@RequestParam(value = "sessionId", required = false) String sessionId) {
        return service.seedAndAnalyze(sessionId);
    }

    @PostMapping("/reset")
    public BehaviorDemoScenarioService.ResetResult reset(
        @RequestBody(required = false) BehaviorDemoScenarioService.ResetRequest request
    ) {
        return service.reset(request);
    }

    @PostMapping("/scenarios/{userId}/analyze")
    public BehaviorDemoScenarioService.BehaviorScenarioResult analyze(@PathVariable String userId) {
        return service.analyze(userId);
    }

    @PostMapping("/scenarios/{userId}/signals")
    public BehaviorDemoScenarioService.BehaviorScenarioResult recordSignal(
        @PathVariable String userId,
        @RequestBody(required = false) BehaviorDemoScenarioService.RecordBehaviorSignalRequest request
    ) {
        return service.recordSignal(userId, request);
    }

    @PostMapping("/scenarios/{userId}/events")
    public BehaviorDemoScenarioService.BehaviorEventSummary recordEvent(
        @PathVariable String userId,
        @RequestBody(required = false) BehaviorDemoScenarioService.RecordBehaviorSignalRequest request
    ) {
        return service.recordEvent(userId, request);
    }

    @PostMapping("/scenarios/{userId}/positive-recovery")
    public BehaviorDemoScenarioService.BehaviorScenarioResult recordPositiveRecovery(@PathVariable String userId) {
        return service.recordPositiveRecovery(userId);
    }

    @PostMapping("/scenarios/{userId}/negative-churn")
    public BehaviorDemoScenarioService.BehaviorScenarioResult recordNegativeChurnSignals(@PathVariable String userId) {
        return service.recordNegativeChurnSignals(userId);
    }

    @PostMapping("/scenarios/{userId}/agentic-ui")
    public AgenticUiComposerService.AgenticUiResponse agenticUi(@PathVariable String userId) {
        return agenticUiComposerService.compose(service.analyze(userId));
    }

    @PostMapping("/scenarios/{userId}/retention-offer")
    public BehaviorDemoScenarioService.RetentionOfferDemoResult retentionOffer(
        @PathVariable String userId,
        @RequestBody(required = false) BehaviorDemoScenarioService.RetentionOfferDemoRequest request
    ) {
        return service.retentionOffer(userId, request);
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
        return new BuildInfo(values, buildInfoPath.trim());
    }

    private String buildValue(BuildInfo buildInfo, String buildInfoKey, String... environmentKeys) {
        if (buildInfo != null && buildInfo.values().containsKey(buildInfoKey)) {
            return buildInfo.values().get(buildInfoKey);
        }
        if (environmentKeys != null) {
            for (String key : environmentKeys) {
                String value = environment.getProperty(key);
                if (StringUtils.hasText(value)) {
                    return value;
                }
            }
        }
        if ("version".equals(buildInfoKey)) {
            return appVersion;
        }
        if ("aiFabricVersion".equals(buildInfoKey)) {
            return aiFabricVersion;
        }
        return "unknown";
    }

    private String providerMode(String provider) {
        if (!StringUtils.hasText(provider)) {
            return "unknown";
        }
        return "behavior-local".equalsIgnoreCase(provider.trim()) ? "deterministic-local" : "live-external";
    }

    private record BuildInfo(Map<String, String> values, String source) {
        static BuildInfo environmentFallback() {
            return new BuildInfo(Map.of(), "environment");
        }
    }
}
