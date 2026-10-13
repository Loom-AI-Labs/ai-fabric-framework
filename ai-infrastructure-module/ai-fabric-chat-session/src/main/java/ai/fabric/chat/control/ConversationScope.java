package ai.fabric.chat.control;

import org.springframework.util.StringUtils;

/** Trusted ownership scope for one deployment-local conversation. */
public record ConversationScope(
    String tenantId,
    String deploymentId,
    String conversationId,
    String ownerId
) {
    public ConversationScope {
        tenantId = required(tenantId, "tenantId");
        deploymentId = required(deploymentId, "deploymentId");
        conversationId = required(conversationId, "conversationId");
        ownerId = required(ownerId, "ownerId");
    }

    private static String required(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
