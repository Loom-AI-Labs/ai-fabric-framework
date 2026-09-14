package ai.fabric.execution.chain;

import ai.fabric.execution.specialist.SpecialistId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Safe terminal result of one bounded specialist chain. */
public record SpecialistChainExecutionResult(
    String executionId,
    SpecialistChainId chainId,
    String chainContentHash,
    SpecialistChainExecutionStatus status,
    String message,
    SpecialistId handoffTarget,
    List<SpecialistChainResultView> projectedResults,
    List<SpecialistChainStepTrace> steps,
    String conversationSnapshotRevision,
    long conversationSourceTurnCount,
    SpecialistChainFailure failure,
    boolean replayed,
    boolean durable,
    Instant startedAt,
    Instant completedAt
) {
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");

    public SpecialistChainExecutionResult {
        executionId = requireText(executionId, "executionId");
        Objects.requireNonNull(chainId, "chainId is required");
        chainContentHash = requireText(
            chainContentHash,
            "chainContentHash"
        );
        if (!SHA_256.matcher(chainContentHash).matches()) {
            throw new IllegalArgumentException(
                "chainContentHash must be a lowercase SHA-256 value"
            );
        }
        Objects.requireNonNull(status, "status is required");
        if (!status.terminal()) {
            throw new IllegalArgumentException(
                "Execution results require a terminal status"
            );
        }
        message = normalizeBoundedOptional(
            message,
            "message",
            SpecialistChainDirective.MAX_MESSAGE_CHARACTERS
        );
        projectedResults = projectedResults == null
            ? List.of()
            : List.copyOf(projectedResults);
        projectedResults.forEach(result -> Objects.requireNonNull(
            result,
            "projected result is required"
        ));
        steps = steps == null ? List.of() : List.copyOf(steps);
        steps.forEach(step -> Objects.requireNonNull(
            step,
            "chain step is required"
        ));
        if (conversationSourceTurnCount < 0) {
            throw new IllegalArgumentException(
                "conversationSourceTurnCount cannot be negative"
            );
        }
        conversationSnapshotRevision = normalizeOptional(
            conversationSnapshotRevision
        );
        Objects.requireNonNull(startedAt, "startedAt is required");
        Objects.requireNonNull(completedAt, "completedAt is required");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException(
                "completedAt cannot precede startedAt"
            );
        }
        if (status.succeeded()) {
            if (message == null || failure != null) {
                throw new IllegalArgumentException(
                    "Successful chains require a message and no failure"
                );
            }
            if ((status == SpecialistChainExecutionStatus.HANDED_OFF)
                != (handoffTarget != null)) {
                throw new IllegalArgumentException(
                    "Only handed-off chains contain a handoff target"
                );
            }
        } else {
            if (failure == null || message != null || handoffTarget != null) {
                throw new IllegalArgumentException(
                    "Failed chains require only a safe failure"
                );
            }
        }
    }

    public boolean succeeded() {
        return status.succeeded();
    }

    public SpecialistChainExecutionResult asReplayed() {
        if (replayed) {
            return this;
        }
        return new SpecialistChainExecutionResult(
            executionId,
            chainId,
            chainContentHash,
            status,
            message,
            handoffTarget,
            projectedResults,
            steps,
            conversationSnapshotRevision,
            conversationSourceTurnCount,
            failure,
            true,
            durable,
            startedAt,
            completedAt
        );
    }

    private static String requireText(String value, String field) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
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
}
