package ai.fabric.execution.gateway;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.specialist.SpecialistId;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record AIExecutionResult<O>(
    String invocationId,
    SpecialistId specialistId,
    AIExecutionStatus status,
    O output,
    List<AIEvidenceReference> evidence,
    Map<String, Object> diagnostics,
    AIExecutionFailure failure,
    Instant startedAt,
    Instant completedAt
) {
    public AIExecutionResult {
        invocationId = requireText(invocationId, "invocationId");
        Objects.requireNonNull(specialistId, "specialistId is required");
        Objects.requireNonNull(status, "status is required");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        diagnostics = diagnostics == null || diagnostics.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(diagnostics));
        Objects.requireNonNull(startedAt, "startedAt is required");
        Objects.requireNonNull(completedAt, "completedAt is required");
        if (status == AIExecutionStatus.SUCCEEDED && output == null) {
            throw new IllegalArgumentException("Successful execution requires output");
        }
        if (status != AIExecutionStatus.SUCCEEDED && failure == null) {
            throw new IllegalArgumentException("Non-success execution requires failure");
        }
    }

    public boolean succeeded() {
        return status == AIExecutionStatus.SUCCEEDED;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field + " is required").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
