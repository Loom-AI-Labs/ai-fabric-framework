package ai.fabric.execution.chain;

import ai.fabric.execution.specialist.SpecialistId;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Bounded projected worker result visible to later manager decisions. */
public record SpecialistChainResultView(
    String resultId,
    String specialist,
    String workerInvocationId,
    String summary,
    Map<String, String> facts,
    List<String> evidenceReferenceIds,
    String resultHash,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant completedAt
) {
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");
    private static final int MAX_LINEAGE_ID_CHARACTERS = 200;
    private static final int MAX_EVIDENCE_ID_CHARACTERS = 300;

    public SpecialistChainResultView {
        resultId = requireBounded(
            resultId,
            "resultId",
            MAX_LINEAGE_ID_CHARACTERS
        );
        specialist = requireText(specialist, "specialist");
        SpecialistId.parse(specialist);
        workerInvocationId = requireBounded(
            workerInvocationId,
            "workerInvocationId",
            MAX_LINEAGE_ID_CHARACTERS
        );
        summary = requireBounded(
            summary,
            "summary",
            SpecialistChainResultProjection.MAX_SUMMARY_CHARACTERS
        );
        LinkedHashMap<String, String> approvedFacts = new LinkedHashMap<>();
        if (facts != null) {
            facts.forEach((name, value) -> {
                String safeName = requireBounded(
                    name,
                    "fact name",
                    SpecialistChainResultProjection.MAX_FACT_NAME_CHARACTERS
                );
                String safeValue = requireBounded(
                    value,
                    "fact value",
                    SpecialistChainResultProjection.MAX_FACT_VALUE_CHARACTERS
                );
                if (approvedFacts.putIfAbsent(safeName, safeValue) != null) {
                    throw new IllegalArgumentException(
                        "facts must not contain duplicate names"
                    );
                }
            });
        }
        if (approvedFacts.size()
            > SpecialistChainResultProjection.MAX_FACTS) {
            throw new IllegalArgumentException("facts exceed the chain limit");
        }
        facts = approvedFacts.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(approvedFacts);
        LinkedHashSet<String> approvedEvidence = new LinkedHashSet<>();
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
                "evidenceReferenceIds exceed the chain result limit"
            );
        }
        evidenceReferenceIds = List.copyOf(approvedEvidence);
        resultHash = requireText(resultHash, "resultHash");
        if (!SHA_256.matcher(resultHash).matches()) {
            throw new IllegalArgumentException(
                "resultHash must be a lowercase SHA-256 value"
            );
        }
        Objects.requireNonNull(completedAt, "completedAt is required");
    }

    public SpecialistId specialistId() {
        return SpecialistId.parse(specialist);
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
}
