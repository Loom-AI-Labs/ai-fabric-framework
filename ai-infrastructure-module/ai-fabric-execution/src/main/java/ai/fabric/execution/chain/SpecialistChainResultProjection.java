package ai.fabric.execution.chain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Application-approved subset of one worker result. */
public record SpecialistChainResultProjection(
    String summary,
    Map<String, String> facts,
    List<String> evidenceReferenceIds
) {
    public static final int MAX_SUMMARY_CHARACTERS = 2_000;
    public static final int MAX_FACTS = 24;
    public static final int MAX_FACT_NAME_CHARACTERS = 100;
    public static final int MAX_FACT_VALUE_CHARACTERS = 1_000;
    public static final int MAX_EVIDENCE_REFERENCES = 32;

    public SpecialistChainResultProjection {
        summary = requireBounded(
            summary,
            "summary",
            MAX_SUMMARY_CHARACTERS
        );
        LinkedHashMap<String, String> approvedFacts = new LinkedHashMap<>();
        if (facts != null) {
            facts.forEach((name, value) -> {
                String safeName = requireBounded(
                    name,
                    "fact name",
                    MAX_FACT_NAME_CHARACTERS
                );
                String safeValue = requireBounded(
                    value,
                    "fact value",
                    MAX_FACT_VALUE_CHARACTERS
                );
                if (approvedFacts.putIfAbsent(safeName, safeValue) != null) {
                    throw new IllegalArgumentException(
                        "facts must not contain duplicate names"
                    );
                }
            });
        }
        if (approvedFacts.size() > MAX_FACTS) {
            throw new IllegalArgumentException(
                "facts must not exceed " + MAX_FACTS
            );
        }
        facts = Collections.unmodifiableMap(approvedFacts);

        LinkedHashSet<String> approvedEvidence = new LinkedHashSet<>();
        if (evidenceReferenceIds != null) {
            for (String reference : evidenceReferenceIds) {
                String safeReference = requireBounded(
                    reference,
                    "evidence reference",
                    300
                );
                if (!approvedEvidence.add(safeReference)) {
                    throw new IllegalArgumentException(
                        "evidenceReferenceIds must not contain duplicates"
                    );
                }
            }
        }
        if (approvedEvidence.size() > MAX_EVIDENCE_REFERENCES) {
            throw new IllegalArgumentException(
                "evidenceReferenceIds must not exceed "
                    + MAX_EVIDENCE_REFERENCES
            );
        }
        evidenceReferenceIds = List.copyOf(approvedEvidence);
    }

    public static SpecialistChainResultProjection summary(String summary) {
        return new SpecialistChainResultProjection(
            summary,
            Map.of(),
            List.of()
        );
    }

    private static String requireBounded(
        String value,
        String field,
        int maximum
    ) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(
                field + " must not exceed " + maximum + " characters"
            );
        }
        return normalized;
    }
}
