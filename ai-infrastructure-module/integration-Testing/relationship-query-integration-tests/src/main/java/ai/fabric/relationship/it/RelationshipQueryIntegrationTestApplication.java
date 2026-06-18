package ai.fabric.relationship.it;

import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.spi.RAGProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.List;
import java.util.Map;

/**
 * Minimal Spring Boot application exposing the relationship query module through
 * HTTP endpoints for real API integration tests.
 */
@SpringBootApplication
@EntityScan(basePackages = {
    "ai.fabric.entity",
    "ai.fabric.relationship.it.entity"
})
@EnableJpaRepositories(basePackages = {
    "ai.fabric.repository",
    "ai.fabric.relationship.it.repository"
})
public class RelationshipQueryIntegrationTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(RelationshipQueryIntegrationTestApplication.class, args);
    }

    /**
     * Test RAGProvider for relationship query integration tests.
     * This module doesn't use RAG functionality but needs the bean for pipeline initialization.
     */
    @Bean
    @Primary
    public RAGProvider testRAGProvider() {
        return new RAGProvider() {
            @Override
            public RAGResponse performRag(RAGRequest request) {
                return RAGResponse.builder()
                    .context("Test RAG context")
                    .documents(List.of())
                    .success(true)
                    .build();
            }

            @Override
            public RAGResponse performRAGQuery(RAGRequest request) {
                return performRag(request);
            }

            @Override
            public void indexContent(String entityType, String entityId, String content, Map<String, Object> metadata) {
                // No-op for relationship query tests
            }

            @Override
            public void removeContent(String entityType, String entityId) {
                // No-op for relationship query tests
            }

            @Override
            public Map<String, Object> getStatistics() {
                return Map.of("provider", "test");
            }

            @Override
            public String getProviderName() {
                return "test-rag-provider";
            }
        };
    }
}
