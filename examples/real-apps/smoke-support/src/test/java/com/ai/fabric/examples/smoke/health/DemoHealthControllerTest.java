package com.ai.fabric.examples.smoke.health;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class DemoHealthControllerTest {

    @Test
    void returnsSharedDemoHealthPayload() {
        DemoDeploymentInfoService service = new DemoDeploymentInfoService(new MockEnvironment()
            .withProperty("spring.application.name", "shared-demo")
            .withProperty("APP_BUILD_COMMIT", "def5678"));

        assertThat(new DemoHealthController(service).health())
            .containsEntry("status", "UP")
            .containsEntry("service", "shared-demo")
            .containsEntry("commit", "def5678");
    }

    @Test
    void mergesApplicationOwnedReadinessWithoutReplacingBuildIdentity() {
        DemoDeploymentInfoService service = new DemoDeploymentInfoService(
            new MockEnvironment()
                .withProperty("spring.application.name", "shared-demo")
                .withProperty("APP_BUILD_COMMIT", "def5678")
        );

        assertThat(new DemoHealthController(
            service,
            java.util.List.of(() -> java.util.Map.of(
                "status", "DOWN",
                "storage", java.util.Map.of("vector", "DOWN")
            ))
        ).health())
            .containsEntry("status", "DOWN")
            .containsEntry("service", "shared-demo")
            .containsEntry("commit", "def5678")
            .containsKey("storage");
    }
}
