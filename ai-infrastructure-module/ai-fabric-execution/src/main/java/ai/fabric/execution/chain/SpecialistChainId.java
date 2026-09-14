package ai.fabric.execution.chain;

import java.util.Objects;

/**
 * Stable, exact-version identity for one application-approved specialist
 * chain.
 */
public record SpecialistChainId(String name, String version) {

    public SpecialistChainId {
        name = requirePart(name, "name");
        version = requirePart(version, "version");
    }

    public static SpecialistChainId of(String name, String version) {
        return new SpecialistChainId(name, version);
    }

    public static SpecialistChainId parse(String reference) {
        String normalized = Objects.requireNonNull(
            reference,
            "reference is required"
        ).trim();
        int separator = normalized.indexOf('@');
        if (separator <= 0
            || separator != normalized.lastIndexOf('@')
            || separator == normalized.length() - 1) {
            throw new IllegalArgumentException(
                "Specialist-chain reference must use exact name@version syntax"
            );
        }
        return of(
            normalized.substring(0, separator),
            normalized.substring(separator + 1)
        );
    }

    @Override
    public String toString() {
        return name + "@" + version;
    }

    private static String requirePart(String value, String field) {
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
