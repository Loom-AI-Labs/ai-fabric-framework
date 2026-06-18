package ai.fabric.pii.config;

import ai.fabric.intent.orchestration.pipeline.steps.PIIDetectionStep;
import ai.fabric.privacy.pii.PIIDetectionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PIIAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PIIAutoConfiguration.class));

    @Test
    void doesNotCreateRuntimeBeansWhenDisabled() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(PIIDetectionService.class);
            assertThat(context).doesNotHaveBean(PIIDetectionStep.class);
        });
    }

    @Test
    void createsServiceAndPipelineStepWhenEnabled() {
        contextRunner
            .withPropertyValues("ai.pii-detection.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(PIIDetectionService.class);
                assertThat(context).hasSingleBean(PIIDetectionStep.class);
            });
    }

    @Test
    void backsOffWhenApplicationProvidesCustomService() {
        PIIDetectionService customService = mock(PIIDetectionService.class);

        contextRunner
            .withBean(PIIDetectionService.class, () -> customService)
            .withPropertyValues("ai.pii-detection.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(PIIDetectionService.class);
                assertThat(context.getBean(PIIDetectionService.class)).isSameAs(customService);
                assertThat(context).hasSingleBean(PIIDetectionStep.class);
            });
    }

    @Test
    void backsOffWhenApplicationProvidesCustomStep() {
        PIIDetectionStep customStep = mock(PIIDetectionStep.class);

        contextRunner
            .withBean(PIIDetectionStep.class, () -> customStep)
            .withPropertyValues("ai.pii-detection.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(PIIDetectionStep.class);
                assertThat(context.getBean(PIIDetectionStep.class)).isSameAs(customStep);
            });
    }

    @Test
    void autoConfigurationImportsRegistersPiiAutoConfiguration() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertThat(input).isNotNull();
            String imports = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(imports).contains(PIIAutoConfiguration.class.getName());
        }
    }
}
