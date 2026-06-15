package com.ai.fabric.realapps.chat.config;

import ai.fabric.chat.spi.ChatSessionAccessControlPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Demo-only policy: allows chat-session operations as long as identifiers are present.
 *
 * <p>For production, replace with a policy that validates authenticated identity + ownership/tenancy.</p>
 */
@Configuration(proxyBeanMethods = false)
public class DemoChatSessionAccessPolicyConfiguration {

    @Bean
    ChatSessionAccessControlPolicy demoChatSessionAccessControlPolicy() {
        return new ChatSessionAccessControlPolicy() {
            @Override
            public boolean canCreateConversation(String ownerId) {
                return StringUtils.hasText(ownerId);
            }

            @Override
            public boolean canAccessConversation(String ownerId, String conversationId) {
                return StringUtils.hasText(ownerId) && StringUtils.hasText(conversationId);
            }

            @Override
            public boolean canRecordTurn(String ownerId, String conversationId) {
                return StringUtils.hasText(ownerId) && StringUtils.hasText(conversationId);
            }

            @Override
            public boolean canDeleteConversation(String ownerId, String conversationId) {
                return StringUtils.hasText(ownerId) && StringUtils.hasText(conversationId);
            }
        };
    }
}
