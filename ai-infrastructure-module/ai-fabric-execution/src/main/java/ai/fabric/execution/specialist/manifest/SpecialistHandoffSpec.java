package ai.fabric.execution.specialist.manifest;

import java.util.List;

/**
 * Exact specialist references available to one-level handoff.
 */
public record SpecialistHandoffSpec(List<String> targets) {

    public SpecialistHandoffSpec {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }

    public static SpecialistHandoffSpec disabled() {
        return new SpecialistHandoffSpec(List.of());
    }
}
