package ai.fabric.execution.specialist.manifest;

import ai.fabric.execution.specialist.SpecialistDefinitionSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public final class MicrometerSpecialistManifestMetrics
    implements SpecialistManifestMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger javaDefinitions = new AtomicInteger();
    private final AtomicInteger manifestDefinitions = new AtomicInteger();
    private final AtomicInteger totalDefinitions = new AtomicInteger();

    public MicrometerSpecialistManifestMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(
            meterRegistry,
            "meterRegistry is required"
        );
        Gauge.builder(
            "ai.fabric.specialist.registry.definition.count",
            javaDefinitions,
            AtomicInteger::get
        ).tag("source", "java").register(meterRegistry);
        Gauge.builder(
            "ai.fabric.specialist.registry.definition.count",
            manifestDefinitions,
            AtomicInteger::get
        ).tag("source", "manifest").register(meterRegistry);
        Gauge.builder(
            "ai.fabric.specialist.registry.definition.count",
            totalDefinitions,
            AtomicInteger::get
        ).tag("source", "all").register(meterRegistry);
    }

    @Override
    public void recordLoad(String result, String reason) {
        counter(
            "ai.fabric.specialist.manifest.load",
            result,
            reason
        ).increment();
    }

    @Override
    public void recordValidation(String result, String reason) {
        counter(
            "ai.fabric.specialist.manifest.validation",
            result,
            reason
        ).increment();
    }

    @Override
    public void recordRegistryCounts(
        int javaDefinitionCount,
        int manifestDefinitionCount
    ) {
        javaDefinitions.set(javaDefinitionCount);
        manifestDefinitions.set(manifestDefinitionCount);
        totalDefinitions.set(javaDefinitionCount + manifestDefinitionCount);
    }

    @Override
    public void recordExecution(
        SpecialistDefinitionSource source,
        String result
    ) {
        Counter.builder("ai.fabric.specialist.execution.by.source")
            .tag(
                "source",
                source == null
                    ? "unknown"
                    : source.name().toLowerCase(java.util.Locale.ROOT)
            )
            .tag("result", bounded(result, "unknown"))
            .register(meterRegistry)
            .increment();
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
        String normalized = value.trim().toLowerCase(
            java.util.Locale.ROOT
        );
        return normalized.length() > 80
            ? normalized.substring(0, 80)
            : normalized;
    }
}
