package ai.fabric.execution.specialist;

import ai.fabric.intent.orchestration.capability.RequestedCapabilityProfile;
import java.util.Objects;

/**
 * Existing Mode plus the capabilities a specialist is allowed to request.
 */
public record SpecialistExecutionProfile(
    String mode,
    RequestedCapabilityProfile requestedCapabilities,
    ExecutionStrategy strategy,
    boolean writeEnabled
) {
    public SpecialistExecutionProfile {
        mode = requireText(mode, "mode");
        Objects.requireNonNull(requestedCapabilities, "requestedCapabilities is required");
        Objects.requireNonNull(strategy, "strategy is required");
        if (!writeEnabled && !requestedCapabilities.proposableWriteActions().isEmpty()) {
            throw new IllegalArgumentException(
                "READ-only specialist cannot declare proposable WRITE actions"
            );
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field + " is required").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
