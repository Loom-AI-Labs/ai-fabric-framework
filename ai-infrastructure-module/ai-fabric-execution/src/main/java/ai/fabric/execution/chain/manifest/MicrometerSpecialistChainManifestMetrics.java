package ai.fabric.execution.chain.manifest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Micrometer-backed declarative-chain startup metrics. */
public final class MicrometerSpecialistChainManifestMetrics
    implements SpecialistChainManifestMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger javaDefinitions = new AtomicInteger();
    private final AtomicInteger manifestDefinitions = new AtomicInteger();
    private final AtomicInteger inactiveManifests = new AtomicInteger();
    private final AtomicInteger totalDefinitions = new AtomicInteger();

    public MicrometerSpecialistChainManifestMetrics(
        MeterRegistry meterRegistry
    ) {
        this.meterRegistry = Objects.requireNonNull(
            meterRegistry,
            "meterRegistry is required"
        );
        gauge("java", javaDefinitions);
        gauge("manifest", manifestDefinitions);
        gauge("inactive", inactiveManifests);
        gauge("all", totalDefinitions);
    }

    @Override
    public void recordLoad(String result, String reason) {
        counter(
            "ai.fabric.specialist.chain.manifest.load",
            result,
            reason
        ).increment();
    }

    @Override
    public void recordCompilation(String result, String reason) {
        counter(
            "ai.fabric.specialist.chain.manifest.compilation",
            result,
            reason
        ).increment();
    }

    @Override
    public void recordMapping(String result, String reason) {
        counter(
            "ai.fabric.specialist.chain.manifest.mapping",
            result,
            reason
        ).increment();
    }

    @Override
    public void recordProjection(String result, String reason) {
        counter(
            "ai.fabric.specialist.chain.manifest.projection",
            result,
            reason
        ).increment();
    }

    @Override
    public void recordRegistryCounts(
        int javaDefinitionCount,
        int manifestDefinitionCount,
        int inactiveManifestCount
    ) {
        javaDefinitions.set(requireNonNegative(
            javaDefinitionCount,
            "javaDefinitionCount"
        ));
        manifestDefinitions.set(requireNonNegative(
            manifestDefinitionCount,
            "manifestDefinitionCount"
        ));
        inactiveManifests.set(requireNonNegative(
            inactiveManifestCount,
            "inactiveManifestCount"
        ));
        totalDefinitions.set(
            javaDefinitionCount + manifestDefinitionCount
        );
    }

    private void gauge(String source, AtomicInteger value) {
        Gauge.builder(
            "ai.fabric.specialist.chain.registry.definition.count",
            value,
            AtomicInteger::get
        ).tag("source", source).register(meterRegistry);
    }

    private Counter counter(String name, String result, String reason) {
        return Counter.builder(name)
            .tag("result", bounded(result, "unknown"))
            .tag("reason", bounded(reason, "none"))
            .register(meterRegistry);
    }

    private String bounded(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.length() > 80
            ? normalized.substring(0, 80)
            : normalized;
    }

    private int requireNonNegative(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(
                field + " must not be negative"
            );
        }
        return value;
    }
}
