package com.ai.fabric.runtime.config;

import com.ai.infrastructure.chat.spi.ChatSessionAccessControlPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Runtime product default chat-session access control policy.
 *
 * <p>This policy intentionally does not encode "ownership" rules. Ownership is enforced internally by the
 * chat-session module by verifying {@code ChatSession.ownerId} matches the requesting user on every load/write/delete.
 * This hook exists to support customer-specific conversation rules (support access, sharing, etc.) without
 * requiring runtime forks.</p>
 */
@Configuration(proxyBeanMethods = false)
class RuntimeChatSessionAccessControlConfiguration {

    @Bean
    @ConditionalOnMissingBean(ChatSessionAccessControlPolicy.class)
    ChatSessionAccessControlPolicy runtimeDefaultChatSessionAccessControlPolicy() {
        return new ChatSessionAccessControlPolicy() {
            @Override
            public boolean canCreateConversation(String ownerId) {
                return StringUtils.hasText(ownerId);
            }

            @Override
            public boolean canAccessConversation(String requestingUserId, String conversationId) {
                return StringUtils.hasText(requestingUserId) && StringUtils.hasText(conversationId);
            }

            @Override
            public boolean canRecordTurn(String requestingUserId, String conversationId) {
                return StringUtils.hasText(requestingUserId) && StringUtils.hasText(conversationId);
            }

            @Override
            public boolean canDeleteConversation(String requestingUserId, String conversationId) {
                return StringUtils.hasText(requestingUserId) && StringUtils.hasText(conversationId);
            }
        };
    }
}

