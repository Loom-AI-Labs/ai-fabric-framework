package ai.fabric.execution.manager;

import ai.fabric.execution.specialist.SpecialistId;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable supervised boundary. It narrows targets and grants no authority.
 */
public record ConversationManagerDefinition<I>(
    ConversationManagerId id,
    SpecialistId managerSpecialistId,
    Class<I> inputType,
    ConversationManagerInputAdapter<I> inputAdapter,
    List<ConversationManagerTarget<I, ?, ?>> targets,
    Duration maximumDuration
) {
    public ConversationManagerDefinition {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(
            managerSpecialistId,
            "managerSpecialistId is required"
        );
        Objects.requireNonNull(inputType, "inputType is required");
        Objects.requireNonNull(inputAdapter, "inputAdapter is required");
        targets = targets == null ? List.of() : List.copyOf(targets);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException(
                "targets must not be empty"
            );
        }
        if (targets.size() > ConversationManagerInput.MAX_TARGETS) {
            throw new IllegalArgumentException(
                "targets must not exceed "
                    + ConversationManagerInput.MAX_TARGETS
            );
        }
        Set<SpecialistId> unique = new HashSet<>();
        for (ConversationManagerTarget<I, ?, ?> target : targets) {
            ConversationManagerTarget<I, ?, ?> required =
                Objects.requireNonNull(target, "target is required");
            if (!unique.add(required.specialistId())) {
                throw new IllegalArgumentException(
                    "Duplicate manager target " + required.specialistId()
                );
            }
            if (managerSpecialistId.equals(required.specialistId())) {
                throw new IllegalArgumentException(
                    "A conversation manager cannot target itself"
                );
            }
        }
        if (maximumDuration == null
            || maximumDuration.isZero()
            || maximumDuration.isNegative()) {
            throw new IllegalArgumentException(
                "maximumDuration must be positive"
            );
        }
    }
}
