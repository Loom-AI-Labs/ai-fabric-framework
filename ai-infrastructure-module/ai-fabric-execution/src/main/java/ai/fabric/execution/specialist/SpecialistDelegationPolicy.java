package ai.fabric.execution.specialist;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Exact-version specialist targets that one validated source may delegate to.
 *
 * <p>The runtime currently enforces a fixed maximum depth of one.</p>
 */
public record SpecialistDelegationPolicy(Set<SpecialistId> allowedTargets) {

    public static final int MAX_TARGETS = 8;

    public SpecialistDelegationPolicy {
        if (allowedTargets == null || allowedTargets.isEmpty()) {
            allowedTargets = Set.of();
        } else {
            LinkedHashSet<SpecialistId> targets = new LinkedHashSet<>();
            for (SpecialistId target : allowedTargets) {
                targets.add(Objects.requireNonNull(
                    target,
                    "delegation target must not be null"
                ));
            }
            if (targets.size() > MAX_TARGETS) {
                throw new IllegalArgumentException(
                    "A specialist may declare at most "
                        + MAX_TARGETS + " delegation targets"
                );
            }
            allowedTargets = Set.copyOf(targets);
        }
    }

    public static SpecialistDelegationPolicy disabled() {
        return new SpecialistDelegationPolicy(Set.of());
    }

    public static SpecialistDelegationPolicy oneLevel(
        Set<SpecialistId> allowedTargets
    ) {
        return new SpecialistDelegationPolicy(allowedTargets);
    }

    public boolean enabled() {
        return !allowedTargets.isEmpty();
    }

    public boolean allows(SpecialistId target) {
        return target != null && allowedTargets.contains(target);
    }
}
