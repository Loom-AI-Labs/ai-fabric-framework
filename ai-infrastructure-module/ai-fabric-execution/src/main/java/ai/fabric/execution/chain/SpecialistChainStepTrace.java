package ai.fabric.execution.chain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Safe manager-decision and worker lineage for one chain round. */
public record SpecialistChainStepTrace(
    int decisionIndex,
    String managerInvocationId,
    SpecialistChainDirectiveType directiveType,
    String reason,
    String parallelGroupId,
    List<SpecialistChainWorkerTrace> workers,
    SpecialistChainBudgetView remainingBudget,
    Instant startedAt,
    Instant completedAt
) {
    private static final int MAX_LINEAGE_ID_CHARACTERS = 200;

    public SpecialistChainStepTrace {
        if (decisionIndex < 0) {
            throw new IllegalArgumentException(
                "decisionIndex cannot be negative"
            );
        }
        managerInvocationId = requireBounded(
            managerInvocationId,
            "managerInvocationId",
            MAX_LINEAGE_ID_CHARACTERS
        );
        Objects.requireNonNull(directiveType, "directiveType is required");
        reason = requireBounded(
            reason,
            "reason",
            SpecialistChainDirective.MAX_REASON_CHARACTERS
        );
        parallelGroupId = normalizeBoundedOptional(
            parallelGroupId,
            "parallelGroupId",
            MAX_LINEAGE_ID_CHARACTERS
        );
        workers = workers == null ? List.of() : List.copyOf(workers);
        if (workers.size() > SpecialistChainDirective.MAX_TARGETS) {
            throw new IllegalArgumentException(
                "workers exceed the specialist-chain trace limit"
            );
        }
        workers.forEach(worker ->
            Objects.requireNonNull(worker, "worker trace is required")
        );
        if (directiveType == SpecialistChainDirectiveType.INVOKE_PARALLEL
            && parallelGroupId == null) {
            throw new IllegalArgumentException(
                "Parallel directives require a group ID"
            );
        }
        if (directiveType != SpecialistChainDirectiveType.INVOKE_PARALLEL
            && parallelGroupId != null) {
            throw new IllegalArgumentException(
                "Only parallel directives may have a group ID"
            );
        }
        Objects.requireNonNull(
            remainingBudget,
            "remainingBudget is required"
        );
        Objects.requireNonNull(startedAt, "startedAt is required");
        Objects.requireNonNull(completedAt, "completedAt is required");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException(
                "completedAt cannot precede startedAt"
            );
        }
    }

    private static String requireBounded(
        String value,
        String field,
        int maximum
    ) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(
                field + " must not exceed " + maximum + " characters"
            );
        }
        return normalized;
    }

    private static String normalizeBoundedOptional(
        String value,
        String field,
        int maximum
    ) {
        String normalized = normalizeOptional(value);
        if (normalized != null && normalized.length() > maximum) {
            throw new IllegalArgumentException(
                field + " must not exceed " + maximum + " characters"
            );
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
