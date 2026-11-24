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
    SpecialistWritePolicy writePolicy,
    ai.fabric.intent.orchestration.workflow.WorkflowPolicy workflowPolicy
) {
    public SpecialistExecutionProfile(String mode, RequestedCapabilityProfile requestedCapabilities,
        ExecutionStrategy strategy, SpecialistWritePolicy writePolicy) {
        this(mode, requestedCapabilities, strategy, writePolicy, ai.fabric.intent.orchestration.workflow.WorkflowPolicy.disabled());
    }
    public SpecialistExecutionProfile {
        mode = requireText(mode, "mode");
        Objects.requireNonNull(requestedCapabilities, "requestedCapabilities is required");
        Objects.requireNonNull(strategy, "strategy is required");
        Objects.requireNonNull(writePolicy, "writePolicy is required");
        workflowPolicy = workflowPolicy == null ? ai.fabric.intent.orchestration.workflow.WorkflowPolicy.disabled() : workflowPolicy;
        if (workflowPolicy.enabled() && strategy != ExecutionStrategy.BOUNDED_ITERATIVE)
            throw new IllegalArgumentException("Workflow policy requires BOUNDED_ITERATIVE execution");
        if (!writePolicy.permitsProposals()
            && !requestedCapabilities.proposableWriteActions().isEmpty()) {
            throw new IllegalArgumentException(
                "A specialist with writePolicy=DISABLED cannot declare proposable WRITE actions"
            );
        }
    }

    public boolean writeEnabled() {
        return writePolicy.permitsProposals()
            && !requestedCapabilities.proposableWriteActions().isEmpty();
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field + " is required").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
