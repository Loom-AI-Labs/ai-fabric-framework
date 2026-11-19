package com.subscription.hub.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentInfoServiceTest {

    @Test
    void packagedBuildIdentityWinsOverStaleRuntimeLabels() {
        var service = new DeploymentInfoService(new MockEnvironment()
            .withProperty("APP_VERSION", "old-app")
            .withProperty("AI_FABRIC_VERSION", "old-framework")
            .withProperty("APP_BUILD_COMMIT", "HEAD")
            .withProperty("APP_BUILD_BRANCH", "old-branch")
            .withProperty("APP_BUILD_TIME", "old-time"));
        var app = new Properties();
        app.setProperty("version", "qualified-app");
        var build = new Properties();
        build.setProperty("version", "container-app");
        build.setProperty("aiFabricVersion", "qualified-framework");
        build.setProperty("commit", "qualified-source");
        build.setProperty("branch", "main");
        build.setProperty("builtAt", "2026-11-19T21:00:00Z");
        ReflectionTestUtils.setField(service, "appProperties", app);
        ReflectionTestUtils.setField(service, "fileBuildProperties", build);

        assertThat(service.health()).containsEntry("version", "qualified-app")
            .containsEntry("aiFabricVersion", "qualified-framework")
            .containsEntry("commit", "qualified-source")
            .containsEntry("branch", "main")
            .containsEntry("builtAt", "2026-11-19T21:00:00Z");
    }

    @Test
    void healthIncludesDeploymentMetadataFromEnvironment() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("APP_VERSION", "1.2.3")
            .withProperty("AI_FABRIC_VERSION", "0.3.3")
            .withProperty("APP_BUILD_COMMIT", "unknown")
            .withProperty("git_commit", "abc1234")
            .withProperty("APP_BUILD_BRANCH", "unknown")
            .withProperty("git_branch", "main")
            .withProperty("APP_BUILD_TIME", "2026-07-03T12:00:00Z");

        Map<String, Object> health = new DeploymentInfoService(environment).health();

        assertThat(health)
            .containsEntry("status", "UP")
            .containsEntry("service", "ai-fabric-account-resolver")
            .containsEntry("version", "1.2.3")
            .containsEntry("aiFabricVersion", "0.3.3")
            .containsEntry("commit", "abc1234")
            .containsEntry("branch", "main")
            .containsEntry("builtAt", "2026-07-03T12:00:00Z");
        assertThat(health.get("startedAt")).isInstanceOf(String.class);
        assertThat(health.get("checkedAt")).isInstanceOf(String.class);
    }
}
