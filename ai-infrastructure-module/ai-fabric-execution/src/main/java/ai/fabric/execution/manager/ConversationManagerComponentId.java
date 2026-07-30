package ai.fabric.execution.manager;

import java.util.Objects;

/**
 * Exact-version identity for an application-owned manager mapper/projector.
 */
public record ConversationManagerComponentId(
    String name,
    String version
) {
    public ConversationManagerComponentId {
        name = requireText(name, "name");
        version = requireText(version, "version");
    }

    public static ConversationManagerComponentId of(
        String name,
        String version
    ) {
        return new ConversationManagerComponentId(name, version);
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
