package com.ai.fabric.examples.smoke.health;

import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public class DemoDeploymentInfoService {

    private static final String BUILD_INFO_FILE = "/app/build-info.properties";

    private final Environment environment;
    private final Instant startedAt;
    private final Properties fileBuildProperties;

    public DemoDeploymentInfoService(Environment environment) {
        this.environment = environment;
        this.startedAt = Instant.now();
        this.fileBuildProperties = loadFileProperties(BUILD_INFO_FILE);
    }

    public Map<String, Object> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("service", firstText(
            env("APP_SERVICE_NAME"),
            environment.getProperty("spring.application.name"),
            "ai-fabric-real-app-demo"
        ));
        health.put("version", firstText(
            env("APP_VERSION"),
            fileBuildProperties.getProperty("version"),
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
        health.put("startedAt", startedAt.toString());
        health.put("checkedAt", Instant.now().toString());
        return health;
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
