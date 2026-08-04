package com.ai.fabric.realapps.incident.config;

import ai.fabric.chat.spi.ChatSessionAccessControlPolicy;
import com.ai.fabric.examples.smoke.health.DemoDeploymentInfoService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
public class IncidentChatSessionAccessPolicyConfiguration {

    @Bean
    DemoDeploymentInfoService incidentDeploymentInfoService(Environment environment) {
        return new DemoDeploymentInfoService(environment);
    }

    @Bean
    ChatSessionAccessControlPolicy incidentChatSessionAccessControlPolicy() {
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
