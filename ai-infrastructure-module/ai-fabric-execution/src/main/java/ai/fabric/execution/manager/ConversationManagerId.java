package ai.fabric.execution.manager;

import java.util.Objects;

/**
 * Stable, exact-version identity for an application-approved manager plan.
 */
public record ConversationManagerId(String name, String version) {

    public ConversationManagerId {
        name = requireText(name, "name");
        version = requireText(version, "version");
    }

    public static ConversationManagerId of(String name, String version) {
        return new ConversationManagerId(name, version);
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
