package com.ai.fabric.examples.smoke.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DemoHealthAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DemoHealthAutoConfiguration.class))
        .withPropertyValues(
            "spring.application.name=demo-auto-config",
            "APP_BUILD_COMMIT=abc1234"
        );

    @Test
    void registersDemoHealthBeansForWebApps() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(DemoDeploymentInfoService.class);
            assertThat(context).hasSingleBean(DemoHealthController.class);
            assertThat(context.getBean(DemoHealthController.class).health())
                .containsEntry("service", "demo-auto-config")
                .containsEntry("commit", "abc1234");
        });
    }

    @Test
    void canBeDisabled() {
        contextRunner
            .withPropertyValues("ai.fabric.examples.demo-health.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(DemoDeploymentInfoService.class);
                assertThat(context).doesNotHaveBean(DemoHealthController.class);
            });
    }
}
