package ai.fabric.execution.specialist;

import java.util.Objects;

/**
 * Stable, versioned identity for an application-approved specialist.
 */
public record SpecialistId(String name, String version) {

    public SpecialistId {
        name = requireText(name, "name");
        version = requireText(version, "version");
    }

    public static SpecialistId of(String name, String version) {
        return new SpecialistId(name, version);
    }

    @Override
    public String toString() {
        return name + "@" + version;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field + " is required").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
