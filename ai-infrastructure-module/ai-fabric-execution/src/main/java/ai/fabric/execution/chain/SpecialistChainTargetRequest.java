package ai.fabric.execution.chain;

import ai.fabric.execution.specialist.SpecialistId;
import java.util.Objects;

/** One non-authoritative target proposal from the chain manager. */
public record SpecialistChainTargetRequest(
    String targetSpecialist,
    String objective
) {
    public static final int MAX_OBJECTIVE_CHARACTERS = 500;

    public SpecialistChainTargetRequest {
        targetSpecialist = requireText(
            targetSpecialist,
            "targetSpecialist",
            200
        );
        SpecialistId.parse(targetSpecialist);
        objective = requireText(
            objective,
            "objective",
            MAX_OBJECTIVE_CHARACTERS
        );
    }

    public SpecialistId specialistId() {
        return SpecialistId.parse(targetSpecialist);
    }

    private static String requireText(
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
