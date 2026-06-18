package ai.fabric.it.realapi;

import ai.fabric.it.IndexingStrategyIntegrationTest;
import ai.fabric.it.RealAPIActionErrorRecoveryIntegrationTest;
import ai.fabric.it.RealAPIActionPostActionGenerationIntegrationTest;
import ai.fabric.it.RealAPIActionFlowIntegrationTest;
import ai.fabric.it.RealAPIHybridRetrievalToggleIntegrationTest;
import ai.fabric.it.RealAPIIntegrationTest;
import ai.fabric.it.RealAPIIntegrationTestV2;
import ai.fabric.it.RealAPIIntentHistoryAggregationIntegrationTest;
import ai.fabric.it.RealAPIIntentGenerationRoutingIntegrationTest;
import ai.fabric.it.RealAPIONNXFallbackIntegrationTest;
import ai.fabric.it.RealAPIPIIEdgeSpectrumIntegrationTest;
import ai.fabric.it.RealAPISmartSuggestionsIntegrationTest;
import ai.fabric.it.RealAPISmartValidationIntegrationTest;
import ai.fabric.it.RealAPIVectorLifecycleIntegrationTest;
import ai.fabric.it.RealAPILlmPurposePropagationIntegrationTest;
import ai.fabric.it.RealAPIProgressiveExtractionDiagnosticsIntegrationTest;
import ai.fabric.it.RealAPIProgressiveMultiStepComplexScenariosIntegrationTest;
import ai.fabric.it.RealAPIVectorSpaceClarificationPolicyIntegrationTest;
import ai.fabric.it.support.RealAPITestSupport;
import ai.fabric.it.RealAPIMultiProviderFailoverIntegrationTest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Executes the Real API provider matrix suite, iterating over every
 * discovered LLM/Embedding(/Vector DB) combination.
 *
 * Supports test chunking via system property: ai.providers.real-api.test-chunk
 * Available chunks: core, vector, intent-actions, advanced, all (default)
 */
public class RealAPIProviderMatrixIntegrationTest extends AbstractProviderMatrixIntegrationTest {

    // Test chunks for parallel execution
    private static final Class<?>[] CHUNK_CORE = {
        RealAPIIntegrationTest.class,
        RealAPIIntegrationTestV2.class,
        RealAPIONNXFallbackIntegrationTest.class,
        RealAPILlmPurposePropagationIntegrationTest.class,
        RealAPISmartValidationIntegrationTest.class,
        RealAPIProgressiveExtractionDiagnosticsIntegrationTest.class
    };

    private static final Class<?>[] CHUNK_VECTOR = {
        RealAPIVectorLifecycleIntegrationTest.class,
        RealAPIHybridRetrievalToggleIntegrationTest.class,
        IndexingStrategyIntegrationTest.class
    };

    private static final Class<?>[] CHUNK_INTENT_ACTIONS = {
        RealAPIIntentHistoryAggregationIntegrationTest.class,
        RealAPIActionErrorRecoveryIntegrationTest.class,
        RealAPIActionPostActionGenerationIntegrationTest.class,
        RealAPIActionFlowIntegrationTest.class,
        RealAPIIntentGenerationRoutingIntegrationTest.class,
        RealAPIVectorSpaceClarificationPolicyIntegrationTest.class
    };

    private static final Class<?>[] CHUNK_ADVANCED = {
        RealAPIMultiProviderFailoverIntegrationTest.class,
        RealAPISmartSuggestionsIntegrationTest.class,
        RealAPIPIIEdgeSpectrumIntegrationTest.class,
        RealAPIProgressiveMultiStepComplexScenariosIntegrationTest.class
    };

    private static final Class<?>[] REAL_API_TEST_CLASSES = {
        RealAPIIntegrationTest.class,
        RealAPIIntegrationTestV2.class,
        RealAPIONNXFallbackIntegrationTest.class,
        RealAPILlmPurposePropagationIntegrationTest.class,
        RealAPIProgressiveExtractionDiagnosticsIntegrationTest.class,
        RealAPIProgressiveMultiStepComplexScenariosIntegrationTest.class,
        RealAPISmartValidationIntegrationTest.class,
        RealAPIVectorLifecycleIntegrationTest.class,
        RealAPIHybridRetrievalToggleIntegrationTest.class,
        RealAPIIntentHistoryAggregationIntegrationTest.class,
        RealAPIActionErrorRecoveryIntegrationTest.class,
        RealAPIActionPostActionGenerationIntegrationTest.class,
        RealAPIActionFlowIntegrationTest.class,
        RealAPIIntentGenerationRoutingIntegrationTest.class,
        RealAPIVectorSpaceClarificationPolicyIntegrationTest.class,
        RealAPIMultiProviderFailoverIntegrationTest.class,
        RealAPISmartSuggestionsIntegrationTest.class,
        RealAPIPIIEdgeSpectrumIntegrationTest.class,
        IndexingStrategyIntegrationTest.class,
    };

    @Override
    public Stream<DynamicTest> providerMatrix() {
        // Enhanced provider key check with detailed messages
        if (!hasProviderKey()) {
            StringBuilder message = new StringBuilder();
            message.append("No provider API key configured. Please set one of the following:\n");
            message.append("  - OPENAI_API_KEY (for OpenAI)\n");
            message.append("  - ANTHROPIC_API_KEY (for Anthropic)\n");
            message.append("  - GEMINI_API_KEY (for Gemini)\n");
            message.append("  - COHERE_API_KEY (for Cohere)\n");
            message.append("  - AZURE_API_KEY + AZURE_ENDPOINT (for Azure)\n");
            message.append("\nSkipping Real API provider matrix.");
            Assumptions.assumeTrue(false, message.toString());
        }
        return super.providerMatrix();
    }

    @Override
    protected boolean shouldIncludeCombination(ProviderCombination combo) {
        if (!super.shouldIncludeCombination(combo)) {
            return false;
        }

        String vectorDb = combo.vectorDbProvider();
        if (!StringUtils.hasText(vectorDb)) {
            return true;
        }

        if ("pinecone".equalsIgnoreCase(vectorDb)) {
            boolean configured = isPineconeConfiguredForTests();
            if (!configured) {
                log.warn("Skipping combination {} because Pinecone is selected but not configured. " +
                        "Set PINECONE_API_KEY and either PINECONE_INDEX_NAME or PINECONE_API_HOST (or their AI_PROVIDERS_* equivalents).",
                    combo.displayName());
            }
            return configured;
        }

        return true;
    }

    @Override
    protected void beforeMatrixExecution() {
        RealAPITestSupport.ensureProviderConfigured();

        // Enhanced provider key check
        if (!hasProviderKey()) {
            StringBuilder message = new StringBuilder();
            message.append("No provider API key configured. Available providers:\n");

            try {
                ai.fabric.provider.registry.ProviderRegistryService registry =
                    ai.fabric.provider.registry.ProviderRegistryService.getInstance();

                List<ai.fabric.provider.registry.ProviderDefinition> availableLLM =
                    registry.getAvailableLLMProviders();
                List<ai.fabric.provider.registry.ProviderDefinition> availableEmbedding =
                    registry.getAvailableEmbeddingProviders();

                if (!availableLLM.isEmpty()) {
                    message.append("  Available LLM providers:\n");
                    availableLLM.forEach(p ->
                        message.append("    - ").append(p.getDisplayName())
                            .append(" (requires: ").append(p.getApiKeyEnvVar() != null ? p.getApiKeyEnvVar() : "none")
                            .append(")\n"));
                }

                if (!availableEmbedding.isEmpty()) {
                    message.append("  Available Embedding providers:\n");
                    availableEmbedding.forEach(p ->
                        message.append("    - ").append(p.getDisplayName())
                            .append(" (requires: ").append(p.getApiKeyEnvVar() != null ? p.getApiKeyEnvVar() : "none")
                            .append(")\n"));
                }
            } catch (Exception e) {
                // Fallback if registry not available
                message.append("  Set one of: OPENAI_API_KEY, ANTHROPIC_API_KEY, GEMINI_API_KEY, COHERE_API_KEY, or AZURE_API_KEY\n");
            }

            message.append("\nSkipping Real API provider matrix.");
            Assumptions.assumeTrue(false, message.toString());
        }
    }

    @Override
    protected double minimumSuccessRate() {
        String raw = System.getProperty("ai.providers.real-api.minimum-success-rate");
        if (!StringUtils.hasText(raw)) {
            raw = System.getenv("AI_PROVIDERS_REAL_API_MINIMUM_SUCCESS_RATE");
        }
        if (StringUtils.hasText(raw)) {
            try {
                return Double.parseDouble(raw.trim());
            } catch (Exception ignored) {
                // fall through to default
            }
        }
        return 0.85d;
    }

    @Override
    protected int minimumConsideredTests() {
        // Avoid treating tiny suites / assumption-heavy runs as "85% passed".
        String raw = System.getProperty("ai.providers.real-api.minimum-considered-tests");
        if (!StringUtils.hasText(raw)) {
            raw = System.getenv("AI_PROVIDERS_REAL_API_MINIMUM_CONSIDERED_TESTS");
        }
        if (StringUtils.hasText(raw)) {
            try {
                return Math.max(1, Integer.parseInt(raw.trim()));
            } catch (Exception ignored) {
                // fall through to default
            }
        }
        return 20;
    }

    private boolean isPineconeConfiguredForTests() {
        // Vector DB selection is handled separately via ai.vector-db.type, but if pinecone is selected
        // we need enough configuration to build the Pinecone client during Spring context startup.
        String enabled = firstNonBlank(
            System.getProperty("ai.providers.pinecone.enabled"),
            System.getenv("AI_PROVIDERS_PINECONE_ENABLED")
        );
        if (StringUtils.hasText(enabled) && !"true".equalsIgnoreCase(enabled.trim())) {
            return false;
        }

        String apiKey = firstNonBlank(
            System.getProperty("ai.providers.pinecone.api-key"),
            System.getProperty("ai.providers.pinecone.apiKey"),
            System.getenv("AI_PROVIDERS_PINECONE_API_KEY"),
            System.getenv("PINECONE_API_KEY")
        );

        // indexName can be derived from apiHost; accept either.
        String indexNameOrHost = firstNonBlank(
            System.getProperty("ai.providers.pinecone.index-name"),
            System.getProperty("ai.providers.pinecone.indexName"),
            System.getProperty("ai.providers.pinecone.api-host"),
            System.getProperty("ai.providers.pinecone.apiHost"),
            System.getenv("AI_PROVIDERS_PINECONE_INDEX_NAME"),
            System.getenv("PINECONE_INDEX_NAME"),
            System.getenv("AI_PROVIDERS_PINECONE_API_HOST"),
            System.getenv("PINECONE_API_HOST")
        );

        return StringUtils.hasText(apiKey) && StringUtils.hasText(indexNameOrHost);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    @Override
    protected Class<?>[] suiteTestClasses() {
        String chunk = System.getProperty("ai.providers.real-api.test-chunk");
        if (!StringUtils.hasText(chunk)) {
            chunk = System.getenv("AI_PROVIDERS_REAL_API_TEST_CHUNK");
        }

        Class<?>[] selectedClasses;
        if (!StringUtils.hasText(chunk) || "all".equalsIgnoreCase(chunk)) {
            selectedClasses = REAL_API_TEST_CLASSES;
            log.info("Using all test classes ({} total)", selectedClasses.length);
        } else {
            selectedClasses = switch (chunk.toLowerCase()) {
                case "core" -> {
                    log.info("Using core test chunk ({} classes)", CHUNK_CORE.length);
                    yield CHUNK_CORE;
                }
                case "vector" -> {
                    log.info("Using vector test chunk ({} classes)", CHUNK_VECTOR.length);
                    yield CHUNK_VECTOR;
                }
                case "intent-actions", "intent_actions" -> {
                    log.info("Using intent-actions test chunk ({} classes)", CHUNK_INTENT_ACTIONS.length);
                    yield CHUNK_INTENT_ACTIONS;
                }
                case "advanced" -> {
                    log.info("Using advanced test chunk ({} classes)", CHUNK_ADVANCED.length);
                    yield CHUNK_ADVANCED;
                }
                default -> {
                    // Support comma-separated chunk names
                    String[] chunks = chunk.split(",");
                    Class<?>[] selected = Arrays.stream(chunks)
                        .map(String::trim)
                        .flatMap(c -> switch (c.toLowerCase()) {
                            case "core" -> Arrays.stream(CHUNK_CORE);
                            case "vector" -> Arrays.stream(CHUNK_VECTOR);
                            case "intent-actions", "intent_actions" -> Arrays.stream(CHUNK_INTENT_ACTIONS);
                            case "advanced" -> Arrays.stream(CHUNK_ADVANCED);
                            default -> Arrays.stream(new Class<?>[0]);
                        })
                        .distinct()
                        .collect(Collectors.toList())
                        .toArray(new Class<?>[0]);
                    if (selected.length > 0) {
                        log.info("Using custom test chunks: {} ({} classes)", chunk, selected.length);
                        yield selected;
                    } else {
                        log.warn("Unknown test chunk '{}', falling back to all test classes", chunk);
                        yield REAL_API_TEST_CLASSES;
                    }
                }
            };
        }

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("Selected Test Classes for Execution: " + selectedClasses.length);
        System.out.println("═══════════════════════════════════════════════════════════════");
        for (int i = 0; i < selectedClasses.length; i++) {
            System.out.println(String.format("  [%d/%d] %s", i + 1, selectedClasses.length, selectedClasses[i].getSimpleName()));
        }
        System.out.println("═══════════════════════════════════════════════════════════════");

        log.info("Selected test classes for execution:");
        for (int i = 0; i < selectedClasses.length; i++) {
            log.info("  [{}/{}] {}", i + 1, selectedClasses.length, selectedClasses[i].getSimpleName());
        }

        return selectedClasses;
    }

    @Override
    protected Map<String, Object> additionalDiscoveryProperties() {
        String indexPath = "data/test-lucene-index/realapi-" + System.nanoTime();
        return Map.of(
            "spring.liquibase.enabled", "false",
            "ai.config.default-file", "ai-entity-config-realapi.yml",
            "ai.vector-db.lucene.index-path", indexPath
        );
    }

    @Override
    protected List<ProviderCombination> resolveProviderMatrix() {
        List<ProviderCombination> available = availableProviderCombinations();

        String matrixSpec = System.getProperty(matrixPropertyKey());
        if (!StringUtils.hasText(matrixSpec)) {
            matrixSpec = System.getenv(matrixEnvVariable());
        }
        if (!StringUtils.hasText(matrixSpec)) {
            return available;
        }

        List<ProviderCombination> requested = parseMatrixSpec(matrixSpec);

        LinkedHashSet<ProviderCombination> availableSet = new LinkedHashSet<>(available);
        List<ProviderCombination> accepted = requested.stream()
            .filter(availableSet::contains)
            .toList();

        // If requested combinations are not in available set, still use them
        // This allows testing providers that may not be available during discovery
        // but are configured via system properties/environment variables
        if (accepted.isEmpty() && !requested.isEmpty()) {
            System.out.println("WARNING: Requested provider combinations not found in available set, but will attempt to use them anyway: " +
                     requested.stream().map(ProviderCombination::displayName).collect(java.util.stream.Collectors.joining(", ")));
            return requested;
        }

        return accepted.isEmpty() ? available : accepted;
    }

    private List<ProviderCombination> parseMatrixSpec(String matrixSpec) {
        return Arrays.stream(matrixSpec.split(","))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .map(entry -> {
                String[] parts = entry.split(":");
                if (parts.length < 2 || parts.length > 4) {
                    throw new IllegalArgumentException(
                        "Invalid provider matrix entry: '" + entry + "'. Expected llm:embedding[:vectordb] (optional deprecated ':storageStrategy' is ignored)");
                }

                String llm = parts[0].trim();
                String embedding = parts[1].trim();
                String vectorDb = parts.length >= 3 ? parts[2].trim() : null;
                if (parts.length == 4) {
                    String ignoredStorage = parts[3].trim();
                    if (StringUtils.hasText(ignoredStorage)) {
                        // Backward compatibility for older scripts/workflows that included storage strategy.
                        // Storage strategy is no longer used after AISearchableEntity removal.
                        log.warn("Ignoring deprecated provider-matrix storage strategy segment '{}' in entry '{}'", ignoredStorage, entry);
                    }
                }

                return new ProviderCombination(llm, embedding, vectorDb);
            })
            .collect(Collectors.toCollection(ArrayList::new));
    }

    private boolean hasOpenAIKey() {
        String apiKey = System.getProperty("OPENAI_API_KEY");
        if (!StringUtils.hasText(apiKey)) {
            apiKey = System.getenv("OPENAI_API_KEY");
        }
        return StringUtils.hasText(apiKey);
    }

    private boolean hasProviderKey() {
        return hasOpenAIKey() ||
               StringUtils.hasText(System.getProperty("ANTHROPIC_API_KEY")) ||
               StringUtils.hasText(System.getenv("ANTHROPIC_API_KEY")) ||
               StringUtils.hasText(System.getProperty("GEMINI_API_KEY")) ||
               StringUtils.hasText(System.getenv("GEMINI_API_KEY")) ||
               StringUtils.hasText(System.getProperty("COHERE_API_KEY")) ||
               StringUtils.hasText(System.getenv("COHERE_API_KEY")) ||
               StringUtils.hasText(System.getProperty("AZURE_API_KEY")) ||
               StringUtils.hasText(System.getenv("AZURE_API_KEY"));
    }
}
