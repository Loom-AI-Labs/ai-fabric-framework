package ai.fabric.execution.chain.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MicrometerSpecialistChainManifestMetricsTest {

    @Test
    void publishesOnlyBoundedStartupDimensionsAndRegistryCounts() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SpecialistChainManifestMetrics metrics =
            new MicrometerSpecialistChainManifestMetrics(registry);

        metrics.recordLoad("loaded", "none");
        metrics.recordCompilation("compiled", "none");
        metrics.recordCompilation(
            "rejected",
            "CHAIN_MANIFEST_TARGET_NOT_READ_ONLY"
        );
        metrics.recordRegistryCounts(2, 3, 1);
        metrics.recordMapping("rejected", "chain_input_mapping_failed");
        metrics.recordProjection(
            "rejected",
            "chain_result_projection_failed"
        );

        assertThat(registry.counter(
            "ai.fabric.specialist.chain.manifest.load",
            "result",
            "loaded",
            "reason",
            "none"
        ).count()).isEqualTo(1);
        assertThat(registry.counter(
            "ai.fabric.specialist.chain.manifest.compilation",
            "result",
            "compiled",
            "reason",
            "none"
        ).count()).isEqualTo(1);
        assertThat(registry.counter(
            "ai.fabric.specialist.chain.manifest.mapping",
            "result",
            "rejected",
            "reason",
            "chain_input_mapping_failed"
        ).count()).isEqualTo(1);
        assertThat(registry.counter(
            "ai.fabric.specialist.chain.manifest.projection",
            "result",
            "rejected",
            "reason",
            "chain_result_projection_failed"
        ).count()).isEqualTo(1);
        assertThat(registry.counter(
            "ai.fabric.specialist.chain.manifest.compilation",
            "result",
            "rejected",
            "reason",
            "chain_manifest_target_not_read_only"
        ).count()).isEqualTo(1);
        assertThat(gauge(registry, "java")).isEqualTo(2);
        assertThat(gauge(registry, "manifest")).isEqualTo(3);
        assertThat(gauge(registry, "inactive")).isEqualTo(1);
        assertThat(gauge(registry, "all")).isEqualTo(5);
    }

    private double gauge(SimpleMeterRegistry registry, String source) {
        return registry.get(
            "ai.fabric.specialist.chain.registry.definition.count"
        ).tag("source", source).gauge().value();
    }
}
