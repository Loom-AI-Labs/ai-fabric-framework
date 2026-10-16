package ai.fabric.intent.orchestration.conversation;

import org.springframework.util.StringUtils;

/**
 * Server-owned binding that opts one orchestration turn into canonical durable state.
 *
 * <p>Applications must construct this value from their authenticated conversation-control
 * boundary. It must never be copied from a public request body.</p>
 */
public record ConversationStateBinding(
    String tenantId,
    String deploymentId,
    String conversationId,
    String ownerId,
    long expectedControlEpoch
) {
    public ConversationStateBinding {
        tenantId = required(tenantId, "tenantId");
        deploymentId = required(deploymentId, "deploymentId");
        conversationId = required(conversationId, "conversationId");
        ownerId = required(ownerId, "ownerId");
        if (expectedControlEpoch < 0) {
            throw new IllegalArgumentException("expectedControlEpoch cannot be negative");
        }
    }

    private static String required(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
