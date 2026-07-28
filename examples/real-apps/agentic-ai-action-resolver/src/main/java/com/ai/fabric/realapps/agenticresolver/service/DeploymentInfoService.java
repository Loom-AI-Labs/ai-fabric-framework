package com.ai.fabric.realapps.agenticresolver.service;

import ai.fabric.execution.gateway.AIExecutionGateway;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.provider.AIProvider;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolverSpecialistConfiguration;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Service
public class DeploymentInfoService {

    private static final String APP_POM_PROPERTIES =
        "META-INF/maven/com.ai.fabric.examples/agentic-ai-action-resolver/pom.properties";
    private static final String BUILD_INFO_FILE = "/app/build-info.properties";

    private final Environment environment;
    private final Instant startedAt;
    private final Properties appProperties;
    private final Properties fileBuildProperties;
    private final List<AIProvider> providers;
    private final AIExecutionGateway executionGateway;
    private final SpecialistRegistry specialistRegistry;

    public DeploymentInfoService(
        Environment environment,
        List<AIProvider> providers,
        AIExecutionGateway executionGateway,
        SpecialistRegistry specialistRegistry
    ) {
        this.environment = environment;
        this.startedAt = Instant.now();
        this.appProperties = loadClasspathProperties(APP_POM_PROPERTIES);
        this.fileBuildProperties = loadFileProperties(BUILD_INFO_FILE);
        this.providers = providers != null ? List.copyOf(providers) : List.of();
        this.executionGateway = executionGateway;
        this.specialistRegistry = specialistRegistry;
    }

    public Map<String, Object> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("service", "agentic-ai-action-resolver");
        health.put("version", firstText(
            env("APP_VERSION"),
            appProperties.getProperty("version"),
            "unknown"
        ));
        health.put("aiFabricVersion", firstText(
            env("AI_FABRIC_VERSION"),
            fileBuildProperties.getProperty("aiFabricVersion"),
            "unknown"
        ));
        health.put("commit", firstText(
            env("APP_BUILD_COMMIT"),
            env("COOLIFY_GIT_COMMIT_SHA"),
            env("SOURCE_COMMIT"),
            env("GIT_COMMIT"),
            env("COMMIT_SHA"),
            env("RAILWAY_GIT_COMMIT_SHA"),
            env("git_commit"),
            fileBuildProperties.getProperty("commit"),
            "unknown"
        ));
        health.put("branch", firstText(
            env("APP_BUILD_BRANCH"),
            env("COOLIFY_GIT_BRANCH"),
            env("GIT_BRANCH"),
            env("SOURCE_BRANCH"),
            env("git_branch"),
            fileBuildProperties.getProperty("branch"),
            "unknown"
        ));
        health.put("builtAt", firstText(
            env("APP_BUILD_TIME"),
            env("BUILD_TIME"),
            fileBuildProperties.getProperty("builtAt"),
            "unknown"
        ));
        health.put("providerReadiness", providerReadiness());
        List<String> specialists = specialistRegistry.list().stream()
            .map(definition -> definition.id().toString())
            .sorted()
            .toList();
        health.put("execution", Map.of(
            "ready", executionGateway != null,
            "asyncDurability", "EPHEMERAL",
            "specialists", specialists,
            "accountResolverRegistered",
            specialists.contains(
                AccountResolverSpecialistConfiguration.SPECIALIST_ID.toString()
            )
        ));
        health.put("startedAt", startedAt.toString());
        health.put("checkedAt", Instant.now().toString());
        return health;
    }

    private Map<String, Object> providerReadiness() {
        List<String> configured = providers.stream()
            .map(AIProvider::getProviderName)
            .filter(StringUtils::hasText)
            .map(String::trim)
            .sorted()
            .toList();
        List<String> available = new ArrayList<>();
        for (AIProvider provider : providers) {
            try {
                if (provider.isAvailable()) {
                    available.add(provider.getProviderName());
                }
            } catch (RuntimeException ignored) {
                // Readiness reports unavailable rather than hiding or propagating provider failure.
            }
        }
        available.sort(String::compareTo);
        return Map.of(
            "ready", !available.isEmpty(),
            "configuredProviders", configured,
            "availableProviders", List.copyOf(available)
        );
    }

    private String env(String name) {
        String value = environment.getProperty(name);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String firstText(String... candidates) {
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate) && !"unknown".equalsIgnoreCase(candidate.trim())) {
                return candidate.trim();
            }
        }
        return "unknown";
    }

    private Properties loadClasspathProperties(String location) {
        Properties properties = new Properties();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream inputStream = classLoader.getResourceAsStream(location)) {
            if (inputStream != null) {
                properties.load(inputStream);
            }
        } catch (IOException ignored) {
            // Deployment metadata is diagnostic only; health should remain available.
        }
        return properties;
    }

    private Properties loadFileProperties(String location) {
        Properties properties = new Properties();
        Path path = Path.of(location);
        if (!Files.isRegularFile(path)) {
            return properties;
        }
        try (InputStream inputStream = Files.newInputStream(path)) {
            properties.load(inputStream);
        } catch (IOException ignored) {
            // Deployment metadata is diagnostic only; health should remain available.
        }
        return properties;
    }
}
