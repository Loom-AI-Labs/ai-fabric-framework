package ai.fabric.execution.specialist.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.execution.specialist.SpecialistDefinitionSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MicrometerSpecialistManifestMetricsTest {

    @Test
    void recordsManifestLifecycleRegistryAndExecutionMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSpecialistManifestMetrics metrics =
            new MicrometerSpecialistManifestMetrics(registry);

        metrics.recordLoad("success", "none");
        metrics.recordValidation("failed", "unknown_mode");
        metrics.recordRegistryCounts(2, 3);
        metrics.recordExecution(
            SpecialistDefinitionSource.MANIFEST,
            "succeeded"
        );

        assertThat(registry.get("ai.fabric.specialist.manifest.load")
            .tags("result", "success", "reason", "none")
            .counter()
            .count())
            .isEqualTo(1.0);
        assertThat(registry.get("ai.fabric.specialist.manifest.validation")
            .tags("result", "failed", "reason", "unknown_mode")
            .counter()
            .count())
            .isEqualTo(1.0);
        assertThat(registry.get("ai.fabric.specialist.registry.definition.count")
            .tag("source", "java")
            .gauge()
            .value())
            .isEqualTo(2.0);
        assertThat(registry.get("ai.fabric.specialist.registry.definition.count")
            .tag("source", "manifest")
            .gauge()
            .value())
            .isEqualTo(3.0);
        assertThat(registry.get("ai.fabric.specialist.registry.definition.count")
            .tag("source", "all")
            .gauge()
            .value())
            .isEqualTo(5.0);
        assertThat(registry.get("ai.fabric.specialist.execution.by.source")
            .tags("source", "manifest", "result", "succeeded")
            .counter()
            .count())
            .isEqualTo(1.0);
    }

    @Test
    void boundsDynamicTagsAndUsesSafeFallbacks() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSpecialistManifestMetrics metrics =
            new MicrometerSpecialistManifestMetrics(registry);
        String longReason = "X".repeat(120);

        metrics.recordLoad(" ", longReason);
        metrics.recordExecution(null, null);

        assertThat(registry.get("ai.fabric.specialist.manifest.load")
            .tag("result", "unknown")
            .counter()
            .getId()
            .getTag("reason"))
            .hasSize(80)
            .isEqualTo("x".repeat(80));
        assertThat(registry.get("ai.fabric.specialist.execution.by.source")
            .tags("source", "unknown", "result", "unknown")
            .counter()
            .count())
            .isEqualTo(1.0);
    }
}
