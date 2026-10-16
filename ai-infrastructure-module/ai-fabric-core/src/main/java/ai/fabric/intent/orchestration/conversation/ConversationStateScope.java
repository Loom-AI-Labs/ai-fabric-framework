package ai.fabric.intent.orchestration.conversation;

import org.springframework.util.StringUtils;

/** Trusted deployment-local ownership scope for durable orchestration state. */
public record ConversationStateScope(
    String tenantId,
    String deploymentId,
    String conversationId,
    String ownerId,
    long expectedControlEpoch
) {
    public ConversationStateScope {
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
