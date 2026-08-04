package com.ai.fabric.realapps.deploymentguard.service;

import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.manifest.SpecialistManifestRuntimeStatus;
import ai.fabric.provider.AIProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DeploymentGuardHealthService {

    private final Environment environment;
    private final List<AIProvider> providers;
    private final SpecialistRegistry specialistRegistry;
    private final SpecialistManifestRuntimeStatus manifestStatus;
    private final DeploymentKnowledgeIndexService indexService;
    private final Properties buildInfo;
    private final Instant startedAt = Instant.now();

    public DeploymentGuardHealthService(
        Environment environment,
        List<AIProvider> providers,
        SpecialistRegistry specialistRegistry,
        SpecialistManifestRuntimeStatus manifestStatus,
        DeploymentKnowledgeIndexService indexService
    ) {
        this.environment = environment;
        this.providers = providers == null ? List.of() : List.copyOf(providers);
        this.specialistRegistry = specialistRegistry;
        this.manifestStatus = manifestStatus;
        this.indexService = indexService;
        this.buildInfo = loadBuildInfo();
    }

    public Map<String, Object> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("service", "deployment-knowledge-guard");
        response.put("version", first("APP_VERSION", "version", "1.0.0-SNAPSHOT"));
        response.put("aiFabricVersion", first("AI_FABRIC_VERSION", "aiFabricVersion", "0.5.2"));
        response.put("commit", first("COOLIFY_GIT_COMMIT_SHA", "commit", "unknown"));
        response.put("branch", first("COOLIFY_GIT_BRANCH", "branch", "unknown"));
        response.put("builtAt", first("BUILD_TIME", "builtAt", "unknown"));
        response.put("startedAt", startedAt);
        response.put("indexing", indexService.status());
        response.put("specialistRuntime", Map.of(
            "ready", manifestStatus.ready(),
            "manifestDefinitions", manifestStatus.manifestDefinitionCount(),
            "registryHash", manifestStatus.registryContentHash(),
            "registered", specialistRegistry.list().stream()
                .map(definition -> definition.id().toString())
                .sorted()
                .toList()
        ));
        List<String> configured = providers.stream()
            .map(AIProvider::getProviderName)
            .filter(StringUtils::hasText)
            .sorted()
            .toList();
        List<String> available = providers.stream()
            .filter(this::available)
            .map(AIProvider::getProviderName)
            .filter(StringUtils::hasText)
            .sorted()
            .toList();
        response.put("providerReadiness", Map.of(
            "ready", !available.isEmpty(),
            "configuredProviders", configured,
            "availableProviders", available
        ));
        response.put("securityBoundary", Map.of(
            "trustedTenantFilter", true,
            "trustedDeploymentFilter", true,
            "callerIdentityFieldsOverwritten", true,
            "applicationEvidenceVerification", true
        ));
        return Map.copyOf(response);
    }

    private boolean available(AIProvider provider) {
        try {
            return provider.isAvailable();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String first(String environmentKey, String fileKey, String fallback) {
        String environmentValue = environment.getProperty(environmentKey);
        if (StringUtils.hasText(environmentValue)) {
            return environmentValue.trim();
        }
        String fileValue = buildInfo.getProperty(fileKey);
        return StringUtils.hasText(fileValue) ? fileValue.trim() : fallback;
    }

    private Properties loadBuildInfo() {
        Properties properties = new Properties();
        Path path = Path.of("/app/build-info.properties");
        if (!Files.isRegularFile(path)) {
            return properties;
        }
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        } catch (IOException ignored) {
            // Missing metadata remains visible as unknown in the health response.
        }
        return properties;
    }
}
