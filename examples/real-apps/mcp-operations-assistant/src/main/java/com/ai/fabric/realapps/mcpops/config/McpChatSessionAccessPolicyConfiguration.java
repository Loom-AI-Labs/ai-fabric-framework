package com.ai.fabric.realapps.mcpops.config;

import ai.fabric.chat.spi.ChatSessionAccessControlPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
public class McpChatSessionAccessPolicyConfiguration {

    @Bean
    ChatSessionAccessControlPolicy mcpChatSessionAccessControlPolicy() {
        return new ChatSessionAccessControlPolicy() {
            @Override
            public boolean canCreateConversation(String ownerId) {
                return StringUtils.hasText(ownerId);
            }

            @Override
            public boolean canAccessConversation(
                String ownerId,
                String conversationId
            ) {
                return valid(ownerId, conversationId);
            }

            @Override
            public boolean canRecordTurn(String ownerId, String conversationId) {
                return valid(ownerId, conversationId);
            }

            @Override
            public boolean canDeleteConversation(
                String ownerId,
                String conversationId
            ) {
                return valid(ownerId, conversationId);
            }

            private boolean valid(String ownerId, String conversationId) {
                return StringUtils.hasText(ownerId)
                    && StringUtils.hasText(conversationId);
            }
        };
    }
}
