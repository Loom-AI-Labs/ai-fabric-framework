package ai.fabric.chat.it.realapi;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.config.AIInfrastructureAutoConfiguration;
import ai.fabric.chat.config.ChatSessionAutoConfiguration;
import ai.fabric.http.AIHttpClientFactory;
import ai.fabric.http.AIHttpClientProperties;
import ai.fabric.http.DefaultAIHttpClientFactory;
import ai.fabric.http.HttpClient;
import ai.fabric.provider.AIProviderManager;
import ai.fabric.testing.RealApiConnectivityVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Explicit Real API connectivity check.
 *
 * <p>This test is intentionally disabled by default and is meant to be invoked
 * explicitly from CI (or locally) before running long realapi suites.</p>
 */
@EnabledIfSystemProperty(named = "ai.realapi.connectivity.check", matches = "true")
@SpringBootTest(
    classes = RealApiConnectivityVerificationTest.ConnectivityApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("realapi")
class RealApiConnectivityVerificationTest {

    @DynamicPropertySource
    static void enableProvidersWhenKeysPresent(DynamicPropertyRegistry registry) {
        // CI may provide API keys without explicitly setting *_ENABLED flags.
        // This connectivity check should reflect "if a key is configured, the provider can be enabled".
        if (hasText(getPropertyOrEnv("OPENAI_API_KEY"))) {
            registry.add("OPENAI_ENABLED", () -> "true");
            registry.add("AI_INFRASTRUCTURE_OPENAI_ENABLED", () -> "true");
        }
        if (hasText(getPropertyOrEnv("ANTHROPIC_API_KEY"))) {
            registry.add("ANTHROPIC_ENABLED", () -> "true");
            registry.add("AI_INFRASTRUCTURE_ANTHROPIC_ENABLED", () -> "true");
        }
        if (hasText(getPropertyOrEnv("GEMINI_API_KEY"))) {
            registry.add("GEMINI_ENABLED", () -> "true");
            registry.add("AI_INFRASTRUCTURE_GEMINI_ENABLED", () -> "true");
        }
        if (hasText(getPropertyOrEnv("COHERE_API_KEY"))) {
            registry.add("COHERE_ENABLED", () -> "true");
            registry.add("AI_INFRASTRUCTURE_COHERE_ENABLED", () -> "true");
        }
        if (hasText(getPropertyOrEnv("AZURE_API_KEY")) && hasText(getPropertyOrEnv("AZURE_ENDPOINT"))) {
            registry.add("AZURE_ENABLED", () -> "true");
            registry.add("AI_INFRASTRUCTURE_AZURE_ENABLED", () -> "true");
        }
    }

    @Autowired
    private AIProviderManager providerManager;

    @Autowired
    private AIProviderConfig providerConfig;

    @Test
    void shouldReachConfiguredLlmProvider() {
        RealApiConnectivityVerifier.verifyLlmOrThrow(providerManager, providerConfig);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        AIInfrastructureAutoConfiguration.class,
        ChatSessionAutoConfiguration.class
    })
    @EnableConfigurationProperties({AIProviderConfig.class, AIHttpClientProperties.class})
    @ComponentScan(basePackageClasses = {AIProviderManager.class})
    @Import(ConnectivityHttpClientConfiguration.class)
    static class ConnectivityApp {
    }

    @Configuration(proxyBeanMethods = false)
    static class ConnectivityHttpClientConfiguration {

        @Bean
        AIHttpClientFactory aiHttpClientFactory(RestTemplateBuilder restTemplateBuilder,
                                                AIHttpClientProperties properties) {
            return new DefaultAIHttpClientFactory(restTemplateBuilder, properties);
        }

        @Bean
        HttpClient httpClient(AIHttpClientFactory factory) {
            return factory.create();
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String getPropertyOrEnv(String key) {
        String prop = System.getProperty(key);
        if (hasText(prop)) {
            return prop;
        }
        return System.getenv(key);
    }
}
