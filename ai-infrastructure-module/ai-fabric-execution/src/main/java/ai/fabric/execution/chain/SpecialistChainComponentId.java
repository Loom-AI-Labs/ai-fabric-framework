package ai.fabric.execution.chain;

import java.util.Objects;

/** Exact-version identity for an application-owned chain adapter. */
public record SpecialistChainComponentId(String name, String version) {

    public SpecialistChainComponentId {
        name = requirePart(name, "name");
        version = requirePart(version, "version");
    }

    public static SpecialistChainComponentId of(
        String name,
        String version
    ) {
        return new SpecialistChainComponentId(name, version);
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
