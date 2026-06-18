package ai.fabric.it.support;

import ai.fabric.service.VectorManagementService;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Utility methods shared across Real API integration tests.
 *
 * Resolves provider credentials strictly from environment variables and/or JVM system properties.
 *
 * NOTE: This test support intentionally does not read .env/env.dev files from disk. CI and local
 * development should provide credentials via environment variables or JVM -D properties.
 */
public final class RealAPITestSupport {

    private static final String OPENAI_KEY_PROPERTY = "OPENAI_API_KEY";

    private static volatile boolean configured = false;

    private RealAPITestSupport() {
    }

    /**
     * Ensure a provider API key is available via {@link System#getProperty(String)}.
     * This method is provider-agnostic and configures whichever provider is available.
     *
     * @deprecated Use ensureProviderConfigured() for provider-agnostic configuration.
     * This method is kept for backward compatibility but now supports all providers.
     */
    @Deprecated
    public static synchronized void ensureOpenAIConfigured() {
        ensureProviderConfigured();
    }

    /**
     * Ensure a provider API key is available via {@link System#getProperty(String)}.
     * Configures whichever provider is available (OpenAI, Anthropic, Gemini, Cohere, or Azure).
     */
    public static synchronized void ensureProviderConfigured() {
        if (configured) {
            return;
        }

        // Check for OpenAI
        String apiKey = System.getProperty(OPENAI_KEY_PROPERTY);
        if (!StringUtils.hasText(apiKey)) {
            apiKey = System.getenv(OPENAI_KEY_PROPERTY);
        }
        if (StringUtils.hasText(apiKey)) {
            System.setProperty(OPENAI_KEY_PROPERTY, apiKey);
            System.setProperty("ai.providers.openai.api-key", apiKey);
            System.setProperty("OPENAI_ENABLED", "true");
            System.setProperty("ai.providers.openai.enabled", "true");
            System.setProperty("ai.providers.openai.base-url",
                System.getProperty("ai.providers.openai.base-url", "https://api.openai.com/v1"));
            configured = true;
            return;
        }

        // Check for Anthropic
        String anthropicKey = System.getProperty("ANTHROPIC_API_KEY");
        if (!StringUtils.hasText(anthropicKey)) {
            anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        }
        if (StringUtils.hasText(anthropicKey)) {
            System.setProperty("ANTHROPIC_API_KEY", anthropicKey);
            System.setProperty("ai.providers.anthropic.api-key", anthropicKey);
            System.setProperty("ANTHROPIC_ENABLED", "true");
            System.setProperty("ai.providers.anthropic.enabled", "true");
            configured = true;
            return;
        }

        // Check for Gemini
        String geminiKey = System.getProperty("GEMINI_API_KEY");
        if (!StringUtils.hasText(geminiKey)) {
            geminiKey = System.getenv("GEMINI_API_KEY");
        }
        if (StringUtils.hasText(geminiKey)) {
            System.setProperty("GEMINI_API_KEY", geminiKey);
            System.setProperty("ai.providers.gemini.api-key", geminiKey);
            System.setProperty("GEMINI_ENABLED", "true");
            System.setProperty("ai.providers.gemini.enabled", "true");
            configured = true;
            return;
        }

        // Check for Cohere
        String cohereKey = System.getProperty("COHERE_API_KEY");
        if (!StringUtils.hasText(cohereKey)) {
            cohereKey = System.getenv("COHERE_API_KEY");
        }
        if (StringUtils.hasText(cohereKey)) {
            System.setProperty("COHERE_API_KEY", cohereKey);
            System.setProperty("ai.providers.cohere.api-key", cohereKey);
            System.setProperty("COHERE_ENABLED", "true");
            System.setProperty("ai.providers.cohere.enabled", "true");
            configured = true;
            return;
        }

        // Check for Azure
        String azureKey = System.getProperty("AZURE_API_KEY");
        if (!StringUtils.hasText(azureKey)) {
            azureKey = System.getenv("AZURE_API_KEY");
        }
        String azureEndpoint = System.getProperty("AZURE_ENDPOINT");
        if (!StringUtils.hasText(azureEndpoint)) {
            azureEndpoint = System.getenv("AZURE_ENDPOINT");
        }
        if (StringUtils.hasText(azureKey) && StringUtils.hasText(azureEndpoint)) {
            System.setProperty("AZURE_API_KEY", azureKey);
            System.setProperty("ai.providers.azure.api-key", azureKey);
            System.setProperty("AZURE_ENDPOINT", azureEndpoint);
            System.setProperty("ai.providers.azure.endpoint", azureEndpoint);
            System.setProperty("AZURE_ENABLED", "true");
            System.setProperty("ai.providers.azure.enabled", "true");
            configured = true;
            return;
        }

        // No provider configured - this is OK for ONNX-only local embedding runs
        configured = true;
    }

    /**
     * Set LLM_PROVIDER system property based on available API keys.
     * Only sets if not already configured.
     */
    public static synchronized void ensureLLMProviderSet() {
        // Don't override if already set
        if (System.getProperty("LLM_PROVIDER") != null || System.getenv("LLM_PROVIDER") != null) {
            String llmProvider = System.getProperty("LLM_PROVIDER");
            if (llmProvider == null) {
                llmProvider = System.getenv("LLM_PROVIDER");
            }
            if (llmProvider != null) {
                System.setProperty("LLM_PROVIDER", llmProvider);
                System.setProperty("ai.providers.llm-provider", llmProvider);
            }
            return;
        }

        // Auto-detect from available API keys
        if (StringUtils.hasText(System.getProperty("OPENAI_API_KEY")) ||
            StringUtils.hasText(System.getenv("OPENAI_API_KEY"))) {
            System.setProperty("LLM_PROVIDER", "openai");
            System.setProperty("ai.providers.llm-provider", "openai");
        } else if (StringUtils.hasText(System.getProperty("ANTHROPIC_API_KEY")) ||
                   StringUtils.hasText(System.getenv("ANTHROPIC_API_KEY"))) {
            System.setProperty("LLM_PROVIDER", "anthropic");
            System.setProperty("ai.providers.llm-provider", "anthropic");
        } else if (StringUtils.hasText(System.getProperty("GEMINI_API_KEY")) ||
                   StringUtils.hasText(System.getenv("GEMINI_API_KEY"))) {
            System.setProperty("LLM_PROVIDER", "gemini");
            System.setProperty("ai.providers.llm-provider", "gemini");
        } else if (StringUtils.hasText(System.getProperty("COHERE_API_KEY")) ||
                   StringUtils.hasText(System.getenv("COHERE_API_KEY"))) {
            System.setProperty("LLM_PROVIDER", "cohere");
            System.setProperty("ai.providers.llm-provider", "cohere");
        } else if (StringUtils.hasText(System.getProperty("AZURE_API_KEY")) ||
                   StringUtils.hasText(System.getenv("AZURE_API_KEY"))) {
            System.setProperty("LLM_PROVIDER", "azure");
            System.setProperty("ai.providers.llm-provider", "azure");
        }
        // If no provider found, leave unset (ONNX/REST don't need LLM provider)
    }

    public static void awaitVectorExists(VectorManagementService vectorManagementService,
                                         String entityType,
                                         String entityId,
                                         Duration timeout) {
        awaitVectorState(vectorManagementService, entityType, entityId, true, 3, timeout);
    }

    public static void awaitVectorMissing(VectorManagementService vectorManagementService,
                                          String entityType,
                                          String entityId,
                                          Duration timeout) {
        awaitVectorState(vectorManagementService, entityType, entityId, false, 5, timeout);
    }

    private static void awaitVectorState(VectorManagementService vectorManagementService,
                                         String entityType,
                                         String entityId,
                                         boolean expectedExists,
                                         int requiredConsecutiveReads,
                                         Duration timeout) {
        if (vectorManagementService == null) {
            return;
        }

        String description = "vectorExists(" + entityType + ", " + entityId + ") == " + expectedExists;
        awaitStableCondition(description,
            () -> vectorManagementService.vectorExists(entityType, entityId) == expectedExists,
            requiredConsecutiveReads,
            timeout);
    }

    /**
     * Await a condition that must be satisfied consistently for {@code requiredConsecutiveReads}
     * to avoid false positives caused by transient backend errors (e.g., vector DB eventual consistency
     * or HTTP timeouts) being interpreted as "vector missing".
     */
    private static void awaitStableCondition(String description,
                                             Supplier<Boolean> condition,
                                             int requiredConsecutiveReads,
                                             Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean last = false;
        int consecutiveSuccesses = 0;
        int required = Math.max(1, requiredConsecutiveReads);
        while (System.nanoTime() < deadline) {
            try {
                last = Boolean.TRUE.equals(condition.get());
            } catch (Exception ignored) {
                last = false;
            }

            if (last) {
                consecutiveSuccesses++;
                if (consecutiveSuccesses >= required) {
                    return;
                }
            } else {
                consecutiveSuccesses = 0;
            }

            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (!last) {
            throw new AssertionError("Timed out waiting for condition: " + description);
        }
    }
}
