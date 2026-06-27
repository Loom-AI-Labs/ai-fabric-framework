package ai.fabric.behavior.it;

import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.spi.RAGProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.List;
import java.util.Map;

@SpringBootApplication(scanBasePackages = {
    "ai.fabric",
    "ai.fabric.behavior"
})
@Import({
    ai.fabric.config.AIInfrastructureAutoConfiguration.class,
    ai.fabric.behavior.config.BehaviorAIAutoConfiguration.class
})
@EntityScan(basePackages = {
    "ai.fabric.entity",
    "ai.fabric.behavior.entity"
})
@EnableJpaRepositories(basePackages = {
    "ai.fabric.repository",
    "ai.fabric.behavior.repository"
})
public class BehaviorIntegrationTestApp {
    public static void main(String[] args) {
        SpringApplication.run(BehaviorIntegrationTestApp.class, args);
    }

    /**
     * Test RAGProvider for behavior integration tests.
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
                // No-op for tests
            }

            @Override
            public void removeContent(String entityType, String entityId) {
                // No-op for tests
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
