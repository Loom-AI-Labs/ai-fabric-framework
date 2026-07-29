package ai.fabric.execution.plan;

import java.util.Objects;

/**
 * Stable, exact-version identity for an application-approved execution plan.
 */
public record ExecutionPlanId(String name, String version) {

    public ExecutionPlanId {
        name = requireText(name, "name");
        version = requireText(version, "version");
    }

    public static ExecutionPlanId of(String name, String version) {
        return new ExecutionPlanId(name, version);
    }

    @Override
    public String toString() {
        return name + "@" + version;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (normalized.contains("@")) {
            throw new IllegalArgumentException(
                field + " must not contain '@'"
            );
        }
        return normalized;
    }
}
