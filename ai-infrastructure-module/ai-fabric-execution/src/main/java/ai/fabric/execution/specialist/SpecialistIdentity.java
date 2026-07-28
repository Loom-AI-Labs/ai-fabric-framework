package ai.fabric.execution.specialist;

import java.util.Objects;

/**
 * Public identity and purpose of a registered specialist.
 */
public record SpecialistIdentity(
    SpecialistId id,
    String displayName,
    String description
) {
    public SpecialistIdentity {
        Objects.requireNonNull(id, "id is required");
        displayName = requireText(displayName, "displayName");
        description = requireText(description, "description");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field + " is required").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
