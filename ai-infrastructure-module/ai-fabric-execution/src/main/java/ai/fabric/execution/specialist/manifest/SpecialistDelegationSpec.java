package ai.fabric.execution.specialist.manifest;

import java.util.List;

/**
 * Exact specialist references available to one-level delegation.
 */
public record SpecialistDelegationSpec(List<String> targets) {

    public SpecialistDelegationSpec {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }

    public static SpecialistDelegationSpec disabled() {
        return new SpecialistDelegationSpec(List.of());
    }
}
