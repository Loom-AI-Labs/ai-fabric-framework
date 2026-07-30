package ai.fabric.execution.manager;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Startup-validated manager definition and deterministic content fingerprint.
 */
public record RegisteredConversationManager(
    ConversationManagerDefinition<?> definition,
    String contentHash
) {
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");

    public RegisteredConversationManager {
        Objects.requireNonNull(definition, "definition is required");
        contentHash = Objects.requireNonNull(
            contentHash,
            "contentHash is required"
        ).trim();
        if (!SHA_256.matcher(contentHash).matches()) {
            throw new IllegalArgumentException(
                "contentHash must be a lowercase SHA-256 value"
            );
        }
    }

    public ConversationManagerId id() {
        return definition.id();
    }
}
