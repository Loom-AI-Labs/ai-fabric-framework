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

    public static SpecialistId parse(String reference) {
        String normalized = requireText(reference, "reference");
        int separator = normalized.indexOf('@');
        if (separator <= 0
            || separator != normalized.lastIndexOf('@')
            || separator == normalized.length() - 1) {
            throw new IllegalArgumentException(
                "Specialist reference must use exact name@version syntax"
            );
        }
        return new SpecialistId(
            normalized.substring(0, separator),
            normalized.substring(separator + 1)
        );
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
