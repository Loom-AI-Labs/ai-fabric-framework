package ai.fabric.execution.chain;

import ai.fabric.execution.specialist.SpecialistId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Safe lineage for one worker selected by a validated manager directive. */
public record SpecialistChainWorkerTrace(
    String specialist,
    String relationship,
    String invocationId,
    String resultId,
    SpecialistChainWorkerStatus status,
    List<String> evidenceReferenceIds,
    String failureReason,
    Instant startedAt,
    Instant completedAt
) {
    private static final int MAX_LINEAGE_ID_CHARACTERS = 200;
    private static final int MAX_EVIDENCE_ID_CHARACTERS = 300;
    private static final int MAX_FAILURE_REASON_CHARACTERS = 160;

    public SpecialistChainWorkerTrace {
        specialist = requireText(specialist, "specialist");
        SpecialistId.parse(specialist);
        relationship = requireText(relationship, "relationship");
        if (!"DELEGATION".equals(relationship)
            && !"HANDOFF".equals(relationship)) {
            throw new IllegalArgumentException(
                "relationship must be DELEGATION or HANDOFF"
            );
        }
        invocationId = normalizeBoundedOptional(
            invocationId,
            "invocationId",
            MAX_LINEAGE_ID_CHARACTERS
        );
        resultId = normalizeBoundedOptional(
            resultId,
            "resultId",
            MAX_LINEAGE_ID_CHARACTERS
        );
        Objects.requireNonNull(status, "status is required");
        java.util.LinkedHashSet<String> approvedEvidence =
            new java.util.LinkedHashSet<>();
        if (evidenceReferenceIds != null) {
            for (String reference : evidenceReferenceIds) {
                String safeReference = requireBounded(
                    reference,
                    "evidence reference",
                    MAX_EVIDENCE_ID_CHARACTERS
                );
                if (!approvedEvidence.add(safeReference)) {
                    throw new IllegalArgumentException(
                        "evidenceReferenceIds must not contain duplicates"
                    );
                }
            }
        }
        if (approvedEvidence.size()
            > SpecialistChainResultProjection.MAX_EVIDENCE_REFERENCES) {
            throw new IllegalArgumentException(
                "evidenceReferenceIds exceed the chain trace limit"
            );
        }
        evidenceReferenceIds = List.copyOf(approvedEvidence);
        failureReason = normalizeBoundedOptional(
            failureReason,
            "failureReason",
            MAX_FAILURE_REASON_CHARACTERS
        );
        Objects.requireNonNull(startedAt, "startedAt is required");
        Objects.requireNonNull(completedAt, "completedAt is required");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException(
                "completedAt cannot precede startedAt"
            );
        }
        if (status == SpecialistChainWorkerStatus.SUCCEEDED) {
            if (invocationId == null || resultId == null
                || failureReason != null) {
                throw new IllegalArgumentException(
                    "Successful worker traces require invocation and result lineage"
                );
            }
        } else if (failureReason == null) {
            throw new IllegalArgumentException(
                "Failed worker traces require a safe failure reason"
            );
        }
    }

    private static String requireText(String value, String field) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String requireBounded(
        String value,
        String field,
        int maximum
    ) {
        String normalized = requireText(value, field);
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
