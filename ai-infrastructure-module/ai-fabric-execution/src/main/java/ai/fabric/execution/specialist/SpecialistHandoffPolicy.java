package ai.fabric.execution.specialist;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Exact-version specialists that may become a one-level handoff successor.
 */
public record SpecialistHandoffPolicy(Set<SpecialistId> allowedTargets) {

    public static final int MAX_TARGETS = 8;

    public SpecialistHandoffPolicy {
        if (allowedTargets == null || allowedTargets.isEmpty()) {
            allowedTargets = Set.of();
        } else {
            LinkedHashSet<SpecialistId> targets = new LinkedHashSet<>();
            for (SpecialistId target : allowedTargets) {
                targets.add(Objects.requireNonNull(
                    target,
                    "handoff target must not be null"
                ));
            }
            if (targets.size() > MAX_TARGETS) {
                throw new IllegalArgumentException(
                    "A specialist may declare at most "
                        + MAX_TARGETS + " handoff targets"
                );
            }
            allowedTargets = Set.copyOf(targets);
        }
    }

    public static SpecialistHandoffPolicy disabled() {
        return new SpecialistHandoffPolicy(Set.of());
    }

    public static SpecialistHandoffPolicy oneLevel(
        Set<SpecialistId> allowedTargets
    ) {
        return new SpecialistHandoffPolicy(allowedTargets);
    }

    public boolean enabled() {
        return !allowedTargets.isEmpty();
    }

    public boolean allows(SpecialistId target) {
        return target != null && allowedTargets.contains(target);
    }
}
