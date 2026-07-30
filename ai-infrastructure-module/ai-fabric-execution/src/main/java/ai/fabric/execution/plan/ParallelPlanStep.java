package ai.fabric.execution.plan;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One bounded group of independent read-only specialist branches.
 */
public record ParallelPlanStep(
    String id,
    List<SpecialistPlanStep> branches,
    FanInPolicy fanInPolicy,
    int maximumConcurrency
) implements PlanStage {

    public ParallelPlanStep {
        id = requireText(id, "id");
        branches = branches == null ? List.of() : List.copyOf(branches);
        if (branches.size() < 2) {
            throw new IllegalArgumentException(
                "Parallel plan steps require at least two branches"
            );
        }
        Set<String> branchIds = new HashSet<>();
        for (SpecialistPlanStep branch : branches) {
            SpecialistPlanStep required = Objects.requireNonNull(
                branch,
                "parallel branch is required"
            );
            if (!branchIds.add(required.id())) {
                throw new IllegalArgumentException(
                    "Parallel plan step declares duplicate branch "
                        + required.id()
                );
            }
        }
        Objects.requireNonNull(fanInPolicy, "fanInPolicy is required");
        if (maximumConcurrency < 1) {
            throw new IllegalArgumentException(
                "maximumConcurrency must be positive"
            );
        }
        if (maximumConcurrency < branches.size()) {
            throw new IllegalArgumentException(
                "The first parallel plan runtime requires maximumConcurrency "
                    + "to cover every declared branch"
            );
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
