package ai.fabric.execution.action;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;

public final class MicrometerActionProposalMetrics
    implements ActionProposalMetrics {

    private final MeterRegistry registry;

    public MicrometerActionProposalMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(
            registry,
            "registry is required"
        );
    }

    @Override
    public void record(
        String event,
        String actionName,
        ActionProposalReceiptStatus status
    ) {
        registry.counter(
            "ai.fabric.execution.action.receipts",
            "event",
            safe(event),
            "action",
            safe(actionName),
            "status",
            status != null ? status.name() : "UNAVAILABLE"
        ).increment();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }
}
