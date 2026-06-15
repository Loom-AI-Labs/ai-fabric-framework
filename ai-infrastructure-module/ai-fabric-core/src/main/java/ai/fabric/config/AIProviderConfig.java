
package ai.fabric.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for AI providers.
 *
 * <p>
 * The modular provider architecture separates LLM providers and embedding providers so they can
 * be independently selected and combined. This class exposes strongly typed configuration objects
 * for each supported provider along with helper methods to resolve the active provider defaults.
 * </p>
 *
 * <p>
 * All defaults are production-ready and avoid hard-coded credentials. Secrets must be supplied via
 * environment variables or externalized configuration consistent with Spring Boot best practices.
 * </p>
 */
@Data
@ConfigurationProperties(prefix = "ai.providers")
public class AIProviderConfig {

    public interface PurposeLlmConnectionConfig {
        String getLlmProvider();
        String getModel();
        Double getTemperature();
        Integer getMaxTokens();
        Integer getTimeout();
        String getEndpointProfile();
        String getManagedServiceRef();
        String getApiKey();
        String getBaseUrl();
        String getDeploymentName();
        String getApiVersion();
    }

    /**
     * Whether AI capabilities are globally enabled.
     */
    private boolean enabled = true;

    /**
     * Active LLM provider identifier (e.g. openai, anthropic, cohere, azure).
     */
    private String llmProvider = "openai";

    /**
     * Orchestration-specific LLM configuration (intent extraction, classification, planning).
     *
     * <p>When not specified, defaults to the global {@link #llmProvider} configuration.</p>
     */
    private OrchestrationLlmConfig orchestration;

    /**
     * Generation-specific LLM configuration (RAG answers, summaries, narrative).
     *
     * <p>When not specified, defaults to the global {@link #llmProvider} configuration.</p>
     */
    private GenerationLlmConfig generation;

    /**
     * Active embedding provider identifier (e.g. onnx, openai).
     */
    private String embeddingProvider = "onnx";

    /**
     * Optional named embedding endpoint-profile identifier used for diagnostics and release verification.
     */
    private String embeddingEndpointProfile;

    /**
     * Optional managed service reference used for diagnostics and release verification.
     */
    private String embeddingManagedServiceRef;

    /**
     * Optional embedding service mode hint emitted by the control plane.
     */
    private String embeddingServiceMode;

    /**
     * Optional embedding-specific base URL override.
     *
     * <p>This is primarily used when embeddings are served by a dedicated or shared OpenAI-compatible
     * service while generation continues to use a different provider endpoint.</p>
     */
    private String embeddingBaseUrl;

    /**
     * Optional embedding-specific deployment name override (mainly for Azure OpenAI).
     */
    private String embeddingDeploymentName;

    /**
     * Optional embedding-specific API version override (mainly for Azure OpenAI).
     */
    private String embeddingApiVersion;

    /**
     * Optional embedding-specific API key override.
     */
    private String embeddingApiKey;

    /**
     * Enable automatic fallback when the preferred provider fails.
     */
    private Boolean enableFallback = true;

    // Provider specific configuration blocks
    private final OpenAIConfig openai = new OpenAIConfig();
    private final AzureConfig azure = new AzureConfig();
    private final AnthropicConfig anthropic = new AnthropicConfig();
    private final CohereConfig cohere = new CohereConfig();
    private final GeminiConfig gemini = new GeminiConfig();
    private final ONNXConfig onnx = new ONNXConfig();
    private final PineconeConfig pinecone = new PineconeConfig();
    private final WeaviateConfig weaviate = new WeaviateConfig();
    private final QdrantConfig qdrant = new QdrantConfig();
    private final MilvusConfig milvus = new MilvusConfig();

    /**
     * Resolve defaults for the configured primary LLM provider.
     *
     * @return defaults for the current LLM provider
     */
    public GenerationDefaults resolveLlmDefaults() {
        String provider = normalize(llmProvider);
        return switch (provider) {
            case "anthropic" -> anthropic.toGenerationDefaults("anthropic");
            case "cohere" -> cohere.toGenerationDefaults("cohere");
            case "gemini" -> gemini.toGenerationDefaults("gemini");
            case "azure" -> azure.toGenerationDefaults("azure");
            case "openai" ->
                openai.toGenerationDefaults("openai");
            default -> openai.toGenerationDefaults("openai");
        };
    }

    /**
     * Resolve defaults for orchestration LLM requests.
     *
     * <p>If {@link #orchestration} is not configured, falls back to {@link #resolveLlmDefaults()}.</p>
     */
    public GenerationDefaults resolveOrchestrationLlmDefaults() {
        if (orchestration == null) {
            return resolveLlmDefaults();
        }
        String provider = hasText(orchestration.getLlmProvider()) ? orchestration.getLlmProvider() : llmProvider;
        if (!hasText(provider)) {
            return resolveLlmDefaults();
        }
        return buildPurposeDefaults(provider, orchestration.getModel(),
            orchestration.getMaxTokens(), orchestration.getTemperature(), orchestration.getTimeout());
    }

    /**
     * Resolve defaults for generation LLM requests.
     *
     * <p>If {@link #generation} is not configured, falls back to {@link #resolveLlmDefaults()}.</p>
     */
    public GenerationDefaults resolveGenerationLlmDefaults() {
        if (generation == null) {
            return resolveLlmDefaults();
        }
        String provider = hasText(generation.getLlmProvider()) ? generation.getLlmProvider() : llmProvider;
        if (!hasText(provider)) {
            return resolveLlmDefaults();
        }
        return buildPurposeDefaults(provider, generation.getModel(),
            generation.getMaxTokens(), generation.getTemperature(), generation.getTimeout());
    }

    /**
     * Resolve defaults for the configured primary embedding provider.
     *
     * @return defaults for the current embedding provider
     */
    public EmbeddingDefaults resolveEmbeddingDefaults() {
        String provider = normalize(embeddingProvider);
        return switch (provider) {
            case "openai" -> openai.toEmbeddingDefaults("openai");
            case "azure" -> azure.toEmbeddingDefaults("azure");
            case "gemini" -> gemini.toEmbeddingDefaults("gemini");
            case "cohere" -> cohere.toEmbeddingDefaults("cohere");
            case "onnx" ->
                onnx.toEmbeddingDefaults("onnx");
            default -> onnx.toEmbeddingDefaults("onnx");
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private GenerationDefaults buildPurposeDefaults(String providerRaw,
                                                    String modelOverride,
                                                    Integer maxTokens,
                                                    Double temperature,
                                                    Integer timeoutSeconds) {
        String provider = normalize(providerRaw);
        GenerationDefaults providerDefaults = resolveDefaultsForProvider(provider);
        String model = hasText(modelOverride) ? modelOverride : providerDefaults.model();

        return new GenerationDefaults(
            provider,
            model,
            maxTokens != null ? maxTokens : providerDefaults.maxTokens(),
            temperature != null ? temperature : providerDefaults.temperature(),
            timeoutSeconds != null ? timeoutSeconds : providerDefaults.timeoutSeconds(),
            null
        );
    }

    private GenerationDefaults resolveDefaultsForProvider(String provider) {
        return switch (normalize(provider)) {
            case "anthropic" -> anthropic.toGenerationDefaults("anthropic");
            case "cohere" -> cohere.toGenerationDefaults("cohere");
            case "gemini" -> gemini.toGenerationDefaults("gemini");
            case "azure" -> azure.toGenerationDefaults("azure");
            case "openai" -> openai.toGenerationDefaults("openai");
            default -> openai.toGenerationDefaults("openai");
        };
    }

    public boolean orchestrationHasConnectionOverride() {
        return hasPurposeConnectionOverride(orchestration);
    }

    public boolean generationHasConnectionOverride() {
        return hasPurposeConnectionOverride(generation);
    }

    public boolean embeddingHasConnectionOverride() {
        return hasText(embeddingEndpointProfile)
            || hasText(embeddingManagedServiceRef)
            || hasText(embeddingServiceMode)
            || hasText(embeddingApiKey)
            || hasText(embeddingBaseUrl)
            || hasText(embeddingDeploymentName)
            || hasText(embeddingApiVersion);
    }

    private boolean hasPurposeConnectionOverride(PurposeLlmConnectionConfig config) {
        return config != null
            && (hasText(config.getEndpointProfile())
            || hasText(config.getManagedServiceRef())
            || hasText(config.getApiKey())
            || hasText(config.getBaseUrl())
            || hasText(config.getDeploymentName())
            || hasText(config.getApiVersion()));
    }

    /**
     * Normalized generation defaults for the active LLM provider.
     *
     * @param providerName provider identifier
     * @param model model identifier for content generation
     * @param maxTokens token limit per request
     * @param temperature sampling temperature
     * @param timeoutSeconds request timeout
     * @param priority provider priority for selection (higher is preferred)
     */
    public record GenerationDefaults(
        String providerName,
        String model,
        Integer maxTokens,
        Double temperature,
        Integer timeoutSeconds,
        Integer priority
    ) {}

    /**
     * Normalized embedding defaults for the active embedding provider.
     *
     * @param providerName provider identifier
     * @param model embedding model identifier
     */
    public record EmbeddingDefaults(
        String providerName,
        String model
    ) {}

    @Data
    public static class OrchestrationLlmConfig implements PurposeLlmConnectionConfig {
        /**
         * LLM provider for orchestration tasks.
         * Leave null/blank to use {@link #llmProvider}.
         */
        private String llmProvider;

        /**
         * Optional named endpoint-profile identifier used for diagnostics and release verification.
         */
        private String endpointProfile;
        private String managedServiceRef;

        /**
         * Optional purpose-scoped API key override. Provisioning resolves this from deployment/provider secrets.
         */
        private String apiKey;

        /**
         * Optional purpose-scoped base URL override (for example OpenAI-compatible local orchestration services).
         */
        private String baseUrl;

        /**
         * Optional purpose-scoped deployment name override (mainly for Azure OpenAI deployments).
         */
        private String deploymentName;

        /**
         * Optional purpose-scoped API version override (mainly for Azure OpenAI).
         */
        private String apiVersion;

        /**
         * Model identifier for orchestration tasks (optional).
         */
        private String model;

        /**
         * Temperature for orchestration (default: 0.1 for consistency).
         */
        private Double temperature = 0.1;

        /**
         * Max tokens for orchestration requests.
         */
        private Integer maxTokens;

        /**
         * Timeout in seconds for orchestration requests.
         */
        private Integer timeout;
    }

    @Data
    public static class GenerationLlmConfig implements PurposeLlmConnectionConfig {
        private String llmProvider;
        private String endpointProfile;
        private String managedServiceRef;
        private String apiKey;
        private String baseUrl;
        private String deploymentName;
        private String apiVersion;
        private String model;
        private Double temperature = 0.3;
        private Integer maxTokens;
        private Integer timeout;
    }

    @Data
    public static class OpenAIConfig {
        private boolean enabled = true;
        /**
         * When true, performs a small network probe during provider initialization to validate credentials
         * and detect the embedding dimension.
         *
         * <p>Default is {@code false} to keep startup fast and to avoid external network calls in tests.</p>
         */
        private boolean validateOnStartup = false;
        private String apiKey;
        private String baseUrl;
        private String model;
        private Integer maxTokens;
        private Double temperature;
        private Integer timeout;
        private Integer priority;
        private String embeddingModel;
        /**
         * Optional dimension reduction for text-embedding-3 models.
         * For text-embedding-3-small: can reduce from 1536 to 512-1536
         * For text-embedding-3-large: can reduce from 3072 to 256-3072
         * If null, uses model's default dimensions.
         */
        private Integer embeddingDimensions;
        private String embeddingBaseUrl;
        private String embeddingApiKey;

        GenerationDefaults toGenerationDefaults(String providerName) {
            return new GenerationDefaults(
                providerName,
                model,
                maxTokens,
                temperature,
                timeout,
                priority
            );
        }

        EmbeddingDefaults toEmbeddingDefaults(String providerName) {
            return new EmbeddingDefaults(providerName, embeddingModel);
        }
    }

    @Data
    public static class AzureConfig {
        private boolean enabled;
        /**
         * When true, performs a small network probe during provider initialization to validate the embedding deployment.
         *
         * <p>Default is {@code false} to avoid external network calls during unit/integration tests.</p>
         */
        private boolean validateOnStartup = false;
        private String apiKey;
        private String endpoint;
        private String deploymentName;
        private String embeddingDeploymentName;
        private String embeddingEndpoint;
        private String embeddingApiKey;
        private String embeddingApiVersion;
        private String apiVersion;
        private Integer timeout;
        private Integer priority;

        GenerationDefaults toGenerationDefaults(String providerName) {
            return new GenerationDefaults(
                providerName,
                deploymentName,
                2000,
                0.3,
                timeout,
                priority
            );
        }

        EmbeddingDefaults toEmbeddingDefaults(String providerName) {
            return new EmbeddingDefaults(providerName, embeddingDeploymentName);
        }
    }

    @Data
    public static class AnthropicConfig {
        private boolean enabled;
        private String apiKey;
        private String baseUrl;
        private String model;
        private Integer maxTokens;
        private Double temperature;
        private Integer timeout;
        private Integer priority;

        GenerationDefaults toGenerationDefaults(String providerName) {
            return new GenerationDefaults(
                providerName,
                model,
                maxTokens,
                temperature,
                timeout,
                priority
            );
        }
    }

    @Data
    public static class GeminiConfig {
        private boolean enabled;
        /**
         * When true, performs a small network probe during provider initialization to validate credentials.
         *
         * <p>Default is {@code false} to avoid external network calls during unit/integration tests.</p>
         */
        private boolean validateOnStartup = false;
        private String apiKey;
        private String baseUrl;
        private String model;
        private String embeddingModel;
        private Integer maxTokens;
        private Double temperature;
        private Integer timeout;
        private Integer priority;

        GenerationDefaults toGenerationDefaults(String providerName) {
            return new GenerationDefaults(
                providerName,
                model,
                maxTokens,
                temperature,
                timeout,
                priority
            );
        }

        EmbeddingDefaults toEmbeddingDefaults(String providerName) {
            return new EmbeddingDefaults(providerName, embeddingModel);
        }
    }

    @Data
    public static class CohereConfig {
        private boolean enabled;
        /**
         * When true, performs a small network probe during provider initialization to validate credentials.
         *
         * <p>Default is {@code false} to avoid external network calls during unit/integration tests.</p>
         */
        private boolean validateOnStartup = false;
        private String apiKey;
        private String baseUrl;
        private String model;
        private Integer maxTokens;
        private Double temperature;
        private Integer timeout;
        private Integer priority;
        private String embeddingModel;

        GenerationDefaults toGenerationDefaults(String providerName) {
            return new GenerationDefaults(
                providerName,
                model,
                maxTokens,
                temperature,
                timeout,
                priority
            );
        }

        EmbeddingDefaults toEmbeddingDefaults(String providerName) {
            return new EmbeddingDefaults(providerName, embeddingModel);
        }
    }

    @Data
    public static class ONNXConfig {
        private boolean enabled = true;
        private String modelPath;
        private String tokenizerPath;
        private Integer maxSequenceLength = 512;
        private Boolean useGpu = false;
        private String modelAlias;

        EmbeddingDefaults toEmbeddingDefaults(String providerName) {
            return new EmbeddingDefaults(providerName, modelAlias);
        }
    }

    @Data
    public static class WeaviateConfig {
        private boolean enabled;
        private String scheme = "https";
        private String host;
        private Integer port = 443;
        private String apiKey;
        private Integer timeout = 30;
        private Boolean consistencyLevelStrong = false;
        private String classPrefix;
        private String tenantName;
        private Boolean nativeMultiTenancyEnabled = false;
    }

    @Data
    public static class QdrantConfig {
        private boolean enabled;
        private String host;
        private Integer port = 6333;
        private String apiKey;
        private Integer timeout = 30;
        private Integer grpcPort = 6334;
        private Boolean preferGrpc = false;
        private String collectionPrefix;
    }

    @Data
    public static class MilvusConfig {
        private boolean enabled;
        private String host;
        private Integer port = 19530;
        private String username;
        private String password;
        private String databaseName = "default";
        private Integer timeout = 30;
        private Boolean secure = false;
        /**
         * When enabled, forces a Milvus flush after every write. This makes writes immediately durable
         * but is extremely expensive (and will drastically slow down integration tests).
         */
        private Boolean flushOnWrite = false;
        private String collectionPrefix;
    }

    @Data
    public static class PineconeConfig {
        private boolean enabled;
        private String apiKey;
        private String environment;
        private String indexName;
        private Integer dimensions = 1536;
        private String projectId;
        private String apiHost;
        private String namespacePrefix;
    }
}
