package ai.fabric.execution.plan;

import java.util.Objects;

/**
 * Stable, exact-version identity for a deterministic plan component.
 */
public record PlanComponentId(String name, String version) {

    public PlanComponentId {
        name = requireText(name, "name");
        version = requireText(version, "version");
    }

    public static PlanComponentId of(String name, String version) {
        return new PlanComponentId(name, version);
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
