package ai.fabric.execution.plan;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record PlanExecutionResult<O>(
    String executionId,
    ExecutionPlanId planId,
    String planContentHash,
    PlanExecutionStatus status,
    String activeStepId,
    O output,
    List<PlanStepTrace> steps,
    Map<String, Object> diagnostics,
    PlanExecutionFailure failure,
    PlanNeedsUserInput needsUserInput,
    Instant startedAt,
    Instant completedAt
) {
    public PlanExecutionResult {
        executionId = requireText(executionId, "executionId");
        Objects.requireNonNull(planId, "planId is required");
        planContentHash = requireText(planContentHash, "planContentHash");
        Objects.requireNonNull(status, "status is required");
        activeStepId = normalizeOptional(activeStepId);
        steps = steps == null ? List.of() : List.copyOf(steps);
        diagnostics = diagnostics == null || diagnostics.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(diagnostics));
        Objects.requireNonNull(startedAt, "startedAt is required");
        Objects.requireNonNull(completedAt, "completedAt is required");

        if (status == PlanExecutionStatus.SUCCEEDED) {
            if (output == null) {
                throw new IllegalArgumentException(
                    "Successful plan execution requires output"
                );
            }
            if (failure != null || needsUserInput != null) {
                throw new IllegalArgumentException(
                    "Successful plan execution cannot contain failure or input request"
                );
            }
        } else if (status == PlanExecutionStatus.WAITING_FOR_INPUT) {
            if (needsUserInput == null) {
                throw new IllegalArgumentException(
                    "Waiting plan execution requires an input request"
                );
            }
            if (output != null || failure != null) {
                throw new IllegalArgumentException(
                    "Waiting plan execution cannot contain output or failure"
                );
            }
        } else if (status == PlanExecutionStatus.RUNNING) {
            if (output != null || failure != null || needsUserInput != null) {
                throw new IllegalArgumentException(
                    "Running plan execution cannot contain terminal payloads"
                );
            }
        } else {
            if (failure == null) {
                throw new IllegalArgumentException(
                    "Terminal unsuccessful plan execution requires failure"
                );
            }
            if (output != null || needsUserInput != null) {
                throw new IllegalArgumentException(
                    "Failed plan execution cannot contain output or input request"
                );
            }
        }
    }

    public boolean succeeded() {
        return status == PlanExecutionStatus.SUCCEEDED;
    }

    public boolean waitingForInput() {
        return status == PlanExecutionStatus.WAITING_FOR_INPUT;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
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
