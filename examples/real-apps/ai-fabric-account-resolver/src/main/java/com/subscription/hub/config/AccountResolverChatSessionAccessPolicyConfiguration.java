package com.subscription.hub.config;

import ai.fabric.chat.spi.ChatSessionAccessControlPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Demo policy for AI Fabric chat-session ownership checks.
 */
@Configuration(proxyBeanMethods = false)
public class AccountResolverChatSessionAccessPolicyConfiguration {

    @Bean
    ChatSessionAccessControlPolicy accountResolverChatSessionAccessControlPolicy() {
        return new ChatSessionAccessControlPolicy() {
            @Override
            public boolean canCreateConversation(String ownerId) {
                return StringUtils.hasText(ownerId);
            }

            @Override
            public boolean canAccessConversation(String ownerId, String conversationId) {
                return hasOwnerAndConversation(ownerId, conversationId);
            }

            @Override
            public boolean canRecordTurn(String ownerId, String conversationId) {
                return hasOwnerAndConversation(ownerId, conversationId);
            }

            @Override
            public boolean canDeleteConversation(String ownerId, String conversationId) {
                return hasOwnerAndConversation(ownerId, conversationId);
            }

            private boolean hasOwnerAndConversation(String ownerId, String conversationId) {
                return StringUtils.hasText(ownerId) && StringUtils.hasText(conversationId);
            }
        };
    }
}
