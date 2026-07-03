package com.ai.fabric.examples.smoke.health;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DemoDeploymentInfoServiceTest {

    @Test
    void healthIncludesDeploymentMetadataFromEnvironment() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.application.name", "demo-app")
            .withProperty("APP_VERSION", "1.0.0")
            .withProperty("AI_FABRIC_VERSION", "0.3.1")
            .withProperty("APP_BUILD_COMMIT", "abc1234")
            .withProperty("APP_BUILD_BRANCH", "unknown")
            .withProperty("git_branch", "main")
            .withProperty("APP_BUILD_TIME", "2026-07-03T12:00:00Z");

        Map<String, Object> health = new DemoDeploymentInfoService(environment).health();

        assertThat(health)
            .containsEntry("status", "UP")
            .containsEntry("service", "demo-app")
            .containsEntry("version", "1.0.0")
            .containsEntry("aiFabricVersion", "0.3.1")
            .containsEntry("commit", "abc1234")
            .containsEntry("branch", "main")
            .containsEntry("builtAt", "2026-07-03T12:00:00Z");
        assertThat(health.get("startedAt")).isInstanceOf(String.class);
        assertThat(health.get("checkedAt")).isInstanceOf(String.class);
    }
}
