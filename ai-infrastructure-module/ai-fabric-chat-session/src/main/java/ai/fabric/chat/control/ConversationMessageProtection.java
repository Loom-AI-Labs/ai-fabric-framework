package ai.fabric.chat.control;

import java.util.Map;

/** Optional host-owned protection for persisted message bodies, not conversation authority. */
public interface ConversationMessageProtection {
    record Body(String content, Map<String, Object> metadata) {
        public Body {
            java.util.Objects.requireNonNull(content);
            metadata = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata));
        }
    }
    Body protect(ConversationScope scope, String messageId, Body body);
    Body unprotect(ConversationScope scope, String messageId, Body stored);
    static ConversationMessageProtection plaintext() {
        return new ConversationMessageProtection() {
            public Body protect(ConversationScope scope, String messageId, Body body) { return body; }
            public Body unprotect(ConversationScope scope, String messageId, Body body) { return body; }
        };
    }
}
