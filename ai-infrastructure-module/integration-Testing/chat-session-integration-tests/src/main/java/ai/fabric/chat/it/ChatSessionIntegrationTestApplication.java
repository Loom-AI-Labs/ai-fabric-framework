package ai.fabric.chat.it;

import ai.fabric.access.policy.EntityAccessPolicy;
import ai.fabric.chat.spi.ChatSessionAccessControlPolicy;
import ai.fabric.compliance.policy.ComplianceCheckProvider;
import ai.fabric.compliance.policy.ComplianceCheckResult;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * Minimal Spring Boot application for chat-session integration tests.
 *
 * <p>This module intentionally keeps endpoints out of scope and validates chat-session
 * module wiring + RealAPI behavior via {@code @SpringBootTest} suites.</p>
 */
@SpringBootApplication
@EntityScan(basePackages = {
    "ai.fabric.entity",
    "ai.fabric.chat.domain"
})
public class ChatSessionIntegrationTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatSessionIntegrationTestApplication.class, args);
    }

    @Bean
    public EntityAccessPolicy testEntityAccessPolicy() {
        return (userId, entity) -> true;
    }

    @Bean
    public ComplianceCheckProvider testComplianceCheckProvider() {
        return request -> ComplianceCheckResult.builder()
            .compliant(true)
            .details("Chat session IT compliance provider approval")
            .build();
    }

    @Bean
    public DataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean
    public ChatSessionAccessControlPolicy allowAllChatSessionAccessControlPolicy() {
        return new ChatSessionAccessControlPolicy() {
            @Override
            public boolean canCreateConversation(String ownerId) {
                return true;
            }

            @Override
            public boolean canAccessConversation(String requestingUserId, String conversationId) {
                return true;
            }

            @Override
            public boolean canRecordTurn(String requestingUserId, String conversationId) {
                return true;
            }

            @Override
            public boolean canDeleteConversation(String requestingUserId, String conversationId) {
                return true;
            }
        };
    }
}
