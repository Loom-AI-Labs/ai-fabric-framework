package ai.fabric.execution.chain;

import ai.fabric.execution.specialist.SpecialistId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable application-approved boundary for one bounded chain. */
public record SpecialistChainDefinition<I>(
    SpecialistChainId id,
    SpecialistId managerSpecialistId,
    Class<I> inputType,
    SpecialistChainInputAdapter<I> inputAdapter,
    List<SpecialistChainTarget<I, ?, ?>> targets,
    SpecialistChainLimits limits,
    SpecialistChainConversationPolicy conversationPolicy
) {
    public SpecialistChainDefinition {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(
            managerSpecialistId,
            "managerSpecialistId is required"
        );
        Objects.requireNonNull(inputType, "inputType is required");
        Objects.requireNonNull(inputAdapter, "inputAdapter is required");
        targets = targets == null ? List.of() : List.copyOf(targets);
        if (targets.isEmpty()
            || targets.size() > SpecialistChainManagerInput.MAX_TARGETS) {
            throw new IllegalArgumentException(
                "targets must contain between 1 and "
                    + SpecialistChainManagerInput.MAX_TARGETS + " entries"
            );
        }
        Set<SpecialistId> uniqueTargets = new HashSet<>();
        for (SpecialistChainTarget<I, ?, ?> target : targets) {
            SpecialistChainTarget<I, ?, ?> required = Objects.requireNonNull(
                target,
                "target is required"
            );
            if (!uniqueTargets.add(required.specialistId())) {
                throw new IllegalArgumentException(
                    "Duplicate chain target " + required.specialistId()
                );
            }
            if (managerSpecialistId.equals(required.specialistId())) {
                throw new IllegalArgumentException(
                    "A chain manager cannot target itself"
                );
            }
        }
        Objects.requireNonNull(limits, "limits is required");
        Objects.requireNonNull(
            conversationPolicy,
            "conversationPolicy is required"
        );
    }
}
