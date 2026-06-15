package ai.fabric.relationship.integration;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

/**
 * Shared utilities for integration tests to keep DynamicPropertySource wiring consistent.
 */
public final class IntegrationTestSupport {

    private static final Path LUCENE_INDEX_PATH = createIndexPath();

    private IntegrationTestSupport() {
    }

    public static void registerCommonProperties(DynamicPropertyRegistry registry) {
        ensureLuceneDirectory();
        registry.add("spring.datasource.url", () ->
            "jdbc:h2:mem:relationship_query;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.H2Dialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("ai.providers.llm-provider", () -> "openai");
        registry.add("ai.providers.embedding-provider", () -> "onnx");
        registry.add("ai.providers.enable-fallback", () -> "false");
        registry.add("OPENAI_API_KEY", () ->
            Optional.ofNullable(System.getenv("OPENAI_API_KEY"))
                .orElse("sk-test-integration"));
        registry.add("ai.providers.openai.api-key", () ->
            Optional.ofNullable(System.getenv("OPENAI_API_KEY"))
                .orElse("sk-test-integration"));
        registry.add("ai.providers.openai.base-url", () -> "https://api.openai.com/v1");
        registry.add("ai.providers.openai.model", () -> "gpt-4o-mini");
        registry.add("ai.vector-db.type", () -> "lucene");
        registry.add("ai.vector-db.lucene.index-path", () -> LUCENE_INDEX_PATH.toString());
        registry.add("ai.vector-db.lucene.vector-dimension", () -> "384");
        registry.add("ai.vector-db.lucene.similarity-threshold", () -> "0.6");
        registry.add("spring.main.allow-bean-definition-overriding", () -> "true");
        registry.add("ai.infrastructure.relationship.schema.auto-discover", () -> "false");
        registry.add("ai.infrastructure.relationship.schema.refresh-on-startup", () -> "false");
    }

    public static void cleanUpLuceneIndex() throws IOException {
        if (!Files.exists(LUCENE_INDEX_PATH)) {
            return;
        }
        Files.walk(LUCENE_INDEX_PATH)
            .sorted(Comparator.reverseOrder())
            .forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
    }

    private static Path createIndexPath() {
        try {
            return Files.createTempDirectory("relationship-query-lucene");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void ensureLuceneDirectory() {
        try {
            if (Files.notExists(LUCENE_INDEX_PATH)) {
                Files.createDirectories(LUCENE_INDEX_PATH);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare Lucene index directory", e);
        }
    }
}
