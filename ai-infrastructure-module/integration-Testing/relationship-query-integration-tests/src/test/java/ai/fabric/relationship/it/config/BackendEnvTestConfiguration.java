package ai.fabric.relationship.it.config;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.relationship.config.RelationshipQueryProperties;
import ai.fabric.relationship.service.EntityRelationshipMapper;
import ai.fabric.relationship.service.JpaRelationshipTraversalService;
import ai.fabric.relationship.service.RelationshipSchemaProvider;
import ai.fabric.relationship.service.RelationshipTraversalService;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Nullable;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.io.IOException;

/**
 * Test configuration that mirrors real application behavior by loading the backend
 * environment variables so provider credentials used in production are available during
 * the integration tests.
 *
 * NOTE: This test configuration intentionally does not read backend {@code .env} files
 * from disk. CI and local development should provide credentials via environment variables
 * or JVM -D properties. When keys are provided via env vars like {@code OPENAI_API_KEY},
 * we mirror them into the {@code ai.providers.*} property space so provider auto-config
 * can activate consistently across modules.
 *
 * It also ensures Jackson can deserialize offset timestamps that
 * appear in {@link ai.fabric.dto.RAGResponse}.
 */
@Slf4j
@TestConfiguration
public class BackendEnvTestConfiguration {

    @Value("${relationship-test.backend-env-path:../../backend/.env}")
    private String backendEnvPath;

    @PostConstruct
    void loadBackendEnv() {
        // Intentionally does not load .env files from disk: rely on environment variables / JVM system properties.
        mirrorProviderCredentialsIntoSystemProperties();
        if (backendEnvPath != null && !backendEnvPath.isBlank()) {
            log.debug("Skipping backend .env loading from disk (configured path was '{}')", backendEnvPath);
        }
    }

    private void mirrorProviderCredentialsIntoSystemProperties() {
        mirrorOpenAI();
        mirrorAnthropic();
        mirrorGemini();
        mirrorCohere();
        mirrorAzure();
    }

    private void mirrorOpenAI() {
        String apiKey = firstNonBlank(System.getProperty("ai.providers.openai.api-key"),
            System.getProperty("OPENAI_API_KEY"),
            System.getenv("ai.providers.openai.api-key"),
            System.getenv("OPENAI_API_KEY"));
        if (!StringUtils.hasText(apiKey)) {
            return;
        }
        System.setProperty("OPENAI_API_KEY", apiKey);
        System.setProperty("ai.providers.openai.api-key", apiKey);
        System.setProperty("OPENAI_ENABLED", System.getProperty("OPENAI_ENABLED", "true"));
        System.setProperty("ai.providers.openai.enabled", System.getProperty("ai.providers.openai.enabled", "true"));
        System.setProperty("ai.providers.openai.base-url",
            System.getProperty("ai.providers.openai.base-url", "https://api.openai.com/v1"));
    }

    private void mirrorAnthropic() {
        String apiKey = firstNonBlank(System.getProperty("ai.providers.anthropic.api-key"),
            System.getProperty("ANTHROPIC_API_KEY"),
            System.getenv("ai.providers.anthropic.api-key"),
            System.getenv("ANTHROPIC_API_KEY"));
        if (!StringUtils.hasText(apiKey)) {
            return;
        }
        System.setProperty("ANTHROPIC_API_KEY", apiKey);
        System.setProperty("ai.providers.anthropic.api-key", apiKey);
        System.setProperty("ANTHROPIC_ENABLED", System.getProperty("ANTHROPIC_ENABLED", "true"));
        System.setProperty("ai.providers.anthropic.enabled", System.getProperty("ai.providers.anthropic.enabled", "true"));
    }

    private void mirrorGemini() {
        String apiKey = firstNonBlank(System.getProperty("ai.providers.gemini.api-key"),
            System.getProperty("GEMINI_API_KEY"),
            System.getenv("ai.providers.gemini.api-key"),
            System.getenv("GEMINI_API_KEY"));
        if (!StringUtils.hasText(apiKey)) {
            return;
        }
        System.setProperty("GEMINI_API_KEY", apiKey);
        System.setProperty("ai.providers.gemini.api-key", apiKey);
        System.setProperty("GEMINI_ENABLED", System.getProperty("GEMINI_ENABLED", "true"));
        System.setProperty("ai.providers.gemini.enabled", System.getProperty("ai.providers.gemini.enabled", "true"));
    }

    private void mirrorCohere() {
        String apiKey = firstNonBlank(System.getProperty("ai.providers.cohere.api-key"),
            System.getProperty("COHERE_API_KEY"),
            System.getenv("ai.providers.cohere.api-key"),
            System.getenv("COHERE_API_KEY"));
        if (!StringUtils.hasText(apiKey)) {
            return;
        }
        System.setProperty("COHERE_API_KEY", apiKey);
        System.setProperty("ai.providers.cohere.api-key", apiKey);
        System.setProperty("COHERE_ENABLED", System.getProperty("COHERE_ENABLED", "true"));
        System.setProperty("ai.providers.cohere.enabled", System.getProperty("ai.providers.cohere.enabled", "true"));
    }

    private void mirrorAzure() {
        String apiKey = firstNonBlank(System.getProperty("ai.providers.azure.api-key"),
            System.getProperty("AZURE_API_KEY"),
            System.getenv("ai.providers.azure.api-key"),
            System.getenv("AZURE_API_KEY"));
        String endpoint = firstNonBlank(System.getProperty("ai.providers.azure.endpoint"),
            System.getProperty("AZURE_ENDPOINT"),
            System.getenv("ai.providers.azure.endpoint"),
            System.getenv("AZURE_ENDPOINT"));
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(endpoint)) {
            return;
        }
        System.setProperty("AZURE_API_KEY", apiKey);
        System.setProperty("ai.providers.azure.api-key", apiKey);
        System.setProperty("AZURE_ENDPOINT", endpoint);
        System.setProperty("ai.providers.azure.endpoint", endpoint);
        System.setProperty("AZURE_ENABLED", System.getProperty("AZURE_ENABLED", "true"));
        System.setProperty("ai.providers.azure.enabled", System.getProperty("ai.providers.azure.enabled", "true"));
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    @Bean
    @DependsOn("relationshipEntityInitializer")
    RelationshipSchemaProvider testRelationshipSchemaProvider(EntityManagerFactory entityManagerFactory,
                                                              @Nullable AIEntityConfigurationLoader configurationLoader,
                                                              RelationshipQueryProperties properties,
                                                              EntityRelationshipMapper mapper) {
        return new RelationshipSchemaProvider(entityManagerFactory.createEntityManager(), configurationLoader, properties, mapper);
    }

    @Bean(name = "jpaRelationshipTraversalService")
    RelationshipTraversalService testJpaRelationshipTraversalService(EntityManagerFactory entityManagerFactory) {
        return new JpaRelationshipTraversalService(entityManagerFactory.createEntityManager());
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer relationshipTestJacksonCustomizer() {
        return builder -> {
            JavaTimeModule module = new JavaTimeModule();
            // RAGResponse.timestamp is LocalDateTime, but some responses may serialize as either:
            // - ISO_LOCAL_DATE_TIME: 2026-01-10T02:49:47.518077778
            // - ISO_OFFSET_DATE_TIME: 2026-01-10T02:49:47.518077778Z
            // Accept both to avoid RestTemplate deserialization failures in real-api integration tests.
            module.addDeserializer(LocalDateTime.class, new LenientLocalDateTimeDeserializer());
            builder.modulesToInstall(module);
        };
    }

    static class LenientLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String value = p.getValueAsString();
            if (value == null || value.isBlank()) {
                return null;
            }
            String text = value.trim();
            try {
                return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException ignored) {
                // fall through
            }
            try {
                return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDateTime();
            } catch (DateTimeParseException ex) {
                throw ex;
            }
        }
    }

    @Bean
    RestTemplateCustomizer relationshipTestRestTemplateCustomizer() {
        return restTemplate -> {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout((int) Duration.ofSeconds(30).toMillis());
            requestFactory.setReadTimeout((int) Duration.ofSeconds(120).toMillis());
            restTemplate.setRequestFactory(requestFactory);
        };
    }
}
