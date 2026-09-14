package ai.fabric.execution.chain;

import ai.fabric.execution.specialist.SpecialistId;
import java.util.Objects;

/** One exact-version worker plus application-owned typed boundaries. */
public record SpecialistChainTarget<P, I, O>(
    SpecialistId specialistId,
    String description,
    SpecialistChainTargetInputMapper<P, I> inputMapper,
    SpecialistChainTargetResultProjector<P, O> resultProjector,
    boolean delegationAllowed,
    boolean parallelEligible,
    boolean handoffAllowed
) {
    public SpecialistChainTarget {
        Objects.requireNonNull(specialistId, "specialistId is required");
        description = Objects.requireNonNull(
            description,
            "description is required"
        ).trim();
        if (description.isEmpty()) {
            throw new IllegalArgumentException("description is required");
        }
        if (description.length()
            > SpecialistChainTargetView.MAX_DESCRIPTION_CHARACTERS) {
            throw new IllegalArgumentException(
                "description exceeds the chain target limit"
            );
        }
        Objects.requireNonNull(inputMapper, "inputMapper is required");
        Objects.requireNonNull(
            resultProjector,
            "resultProjector is required"
        );
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

    public SpecialistChainTargetView view() {
        return new SpecialistChainTargetView(
            specialistId.toString(),
            description,
            delegationAllowed,
            parallelEligible,
            handoffAllowed
        );
    }
}
