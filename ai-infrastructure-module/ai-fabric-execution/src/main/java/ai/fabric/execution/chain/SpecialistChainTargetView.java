package ai.fabric.execution.chain;

import ai.fabric.execution.specialist.SpecialistId;
import java.util.Objects;

/** Safe target catalog entry visible to the chain manager. */
public record SpecialistChainTargetView(
    String specialist,
    String description,
    boolean delegationAllowed,
    boolean parallelEligible,
    boolean handoffAllowed
) {
    public static final int MAX_DESCRIPTION_CHARACTERS = 500;

    public SpecialistChainTargetView {
        specialist = Objects.requireNonNull(
            specialist,
            "specialist is required"
        ).trim();
        SpecialistId.parse(specialist);
        description = Objects.requireNonNull(
            description,
            "description is required"
        ).trim();
        if (description.isEmpty()) {
            throw new IllegalArgumentException("description is required");
        }
        if (description.length() > MAX_DESCRIPTION_CHARACTERS) {
            throw new IllegalArgumentException(
                "description must not exceed "
                    + MAX_DESCRIPTION_CHARACTERS + " characters"
            );
        }
        if (!delegationAllowed && !handoffAllowed) {
            throw new IllegalArgumentException(
                "A target must allow delegation or handoff"
            );
        }
        if (parallelEligible && !delegationAllowed) {
            throw new IllegalArgumentException(
                "Only delegation targets may be parallel eligible"
            );
        }
    }
}
