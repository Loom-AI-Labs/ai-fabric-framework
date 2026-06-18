package ai.fabric.it.realapi;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.it.TestApplication;
import ai.fabric.provider.AIProviderManager;
import ai.fabric.testing.RealApiConnectivityVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Explicit Real API connectivity check.
 *
 * <p>This test is intentionally disabled by default and is meant to be invoked
 * explicitly from CI (or locally) before running long real-api suites.</p>
 */
@EnabledIfSystemProperty(named = "ai.realapi.connectivity.check", matches = "true")
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("real-api-test")
class RealApiConnectivityVerificationTest {

    @DynamicPropertySource
    static void enableProvidersWhenKeysPresent(DynamicPropertyRegistry registry) {
        // CI may provide API keys without explicitly setting *_ENABLED flags.
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
