package ai.fabric.provider.springai;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.provider.ProviderConfig;
import ai.fabric.provider.ProviderRequestOverrideSupport;
import com.anthropic.models.messages.Model;
import com.google.genai.Client;
import com.openai.azure.AzureOpenAIServiceVersion;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SpringAiModelResolver implements DisposableBean {

    static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";
    static final String DEFAULT_OPENAI_MODEL = "gpt-4o-mini";
    static final String DEFAULT_OPENAI_EMBEDDING_MODEL = "text-embedding-3-small";
    static final String DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com";
    static final String DEFAULT_ANTHROPIC_MODEL = "claude-3-7-sonnet-latest";
    static final String DEFAULT_GEMINI_MODEL = "gemini-2.5-flash";
    static final String DEFAULT_GEMINI_EMBEDDING_MODEL = "text-embedding-004";
    static final int DEFAULT_SPRING_AI_ONNX_DIMENSIONS = 384;
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_MAX_TOKENS = 1000;
    private static final double DEFAULT_TEMPERATURE = 0.3d;

    private final AIProviderConfig providerConfig;
    private final Map<ModelCacheKey, ChatModel> chatModels = new ConcurrentHashMap<>();
    private final Map<ModelCacheKey, EmbeddingModel> embeddingModels = new ConcurrentHashMap<>();

    public SpringAiModelResolver(AIProviderConfig providerConfig) {
        this.providerConfig = providerConfig;
    }

    public ChatModel resolveChatModel(String providerName, AIGenerationRequest request) {
        SpringAiProviderFamily family = requireFamily(providerName);
        ChatConnection connection = chatConnection(family, request);
        return chatModels.computeIfAbsent(connection.cacheKey(), key -> buildChatModel(connection));
    }

    public ChatOptions resolveChatOptions(String providerName, AIGenerationRequest request) {
        SpringAiProviderFamily family = requireFamily(providerName);
        return switch (family) {
            case OPENAI -> openAiChatOptions(chatModel(request), chatTemperature(request, providerConfig.getOpenai()),
                chatMaxTokens(request, providerConfig.getOpenai()));
            case AZURE -> azureChatOptions(azureDeployment(request), chatTemperature(request, null),
                request != null && request.getMaxTokens() != null ? request.getMaxTokens() : DEFAULT_MAX_TOKENS,
                azureApiVersion(request), azureNative(chatBaseUrl(family, request)));
            case ANTHROPIC -> anthropicChatOptions(
                firstText(request != null ? request.getModel() : null, providerConfig.getAnthropic().getModel(), DEFAULT_ANTHROPIC_MODEL),
                chatTemperature(request, providerConfig.getAnthropic()),
                chatMaxTokens(request, providerConfig.getAnthropic()));
            case GEMINI -> geminiChatOptions(
                firstText(request != null ? request.getModel() : null, providerConfig.getGemini().getModel(), DEFAULT_GEMINI_MODEL),
                chatTemperature(request, providerConfig.getGemini()),
                chatMaxTokens(request, providerConfig.getGemini()));
            case SPRING_AI_ONNX -> throw new UnsupportedOperationException("Spring AI ONNX is embedding-only.");
        };
    }

    public EmbeddingModel resolveEmbeddingModel(String providerName, AIEmbeddingRequest request) {
        SpringAiProviderFamily family = requireFamily(providerName);
        if (!supportsEmbedding(family)) {
            throw new UnsupportedOperationException("Provider " + providerName + " does not expose embeddings through Spring AI.");
        }
        EmbeddingConnection connection = embeddingConnection(family);
        return embeddingModels.computeIfAbsent(connection.cacheKey(), key -> buildEmbeddingModel(connection));
    }

    public EmbeddingOptions resolveEmbeddingOptions(String providerName, AIEmbeddingRequest request) {
        SpringAiProviderFamily family = requireFamily(providerName);
        return switch (family) {
            case OPENAI -> openAiEmbeddingOptions(openAiEmbeddingModel(request), providerConfig.getOpenai().getEmbeddingDimensions());
            case AZURE -> azureEmbeddingOptions(azureEmbeddingDeployment(request), azureEmbeddingApiVersion(), azureNative(embeddingBaseUrl(family)));
            case GEMINI -> geminiEmbeddingOptions(firstText(request != null ? request.getModel() : null,
                providerConfig.getGemini().getEmbeddingModel(), DEFAULT_GEMINI_EMBEDDING_MODEL));
            case ANTHROPIC -> throw new UnsupportedOperationException("Anthropic embeddings are not supported by Spring AI.");
            case SPRING_AI_ONNX -> null;
        };
    }

    public boolean isChatAvailable(String providerName) {
        return SpringAiProviderFamily.from(providerName)
            .filter(this::chatEnabled)
            .filter(family -> hasText(chatApiKey(family, null)))
            .filter(family -> !SpringAiProviderFamily.AZURE.equals(family)
                || (hasText(chatBaseUrl(family, null)) && hasText(azureDeployment(null))))
            .isPresent();
    }

    public boolean isEmbeddingAvailable(String providerName) {
        return SpringAiProviderFamily.from(providerName)
            .filter(this::embeddingEnabled)
            .filter(this::supportsEmbedding)
            .filter(family -> SpringAiProviderFamily.SPRING_AI_ONNX.equals(family) || hasText(embeddingApiKey(family)))
            .filter(family -> !SpringAiProviderFamily.AZURE.equals(family)
                || (hasText(embeddingBaseUrl(family)) && hasText(azureEmbeddingDeployment(null))))
            .isPresent();
    }

    public boolean supportsEmbedding(String providerName) {
        return SpringAiProviderFamily.from(providerName).filter(this::supportsEmbedding).isPresent();
    }

    public ProviderConfig providerConfig(String providerName) {
        SpringAiProviderFamily family = requireFamily(providerName);
        return switch (family) {
            case OPENAI -> ProviderConfig.builder()
                .providerName(family.providerName())
                .apiKey(providerConfig.getOpenai().getApiKey())
                .baseUrl(firstText(providerConfig.getOpenai().getBaseUrl(), DEFAULT_OPENAI_BASE_URL))
                .defaultModel(firstText(providerConfig.getOpenai().getModel(), DEFAULT_OPENAI_MODEL))
                .defaultEmbeddingModel(firstText(providerConfig.getOpenai().getEmbeddingModel(), DEFAULT_OPENAI_EMBEDDING_MODEL))
                .embeddingApiKey(firstText(providerConfig.getEmbeddingApiKey(), providerConfig.getOpenai().getEmbeddingApiKey()))
                .embeddingBaseUrl(firstText(providerConfig.getEmbeddingBaseUrl(), providerConfig.getOpenai().getEmbeddingBaseUrl()))
                .maxTokens(providerConfig.getOpenai().getMaxTokens())
                .temperature(providerConfig.getOpenai().getTemperature())
                .timeoutSeconds(providerConfig.getOpenai().getTimeout())
                .enabled(providerConfig.getOpenai().isEnabled())
                .priority(providerConfig.getOpenai().getPriority())
                .customConfig(Map.of("executionLayer", "spring-ai", "springAiProvider", "openai"))
                .build();
            case AZURE -> ProviderConfig.builder()
                .providerName(family.providerName())
                .apiKey(providerConfig.getAzure().getApiKey())
                .baseUrl(providerConfig.getAzure().getEndpoint())
                .defaultModel(providerConfig.getAzure().getDeploymentName())
                .defaultEmbeddingModel(providerConfig.getAzure().getEmbeddingDeploymentName())
                .embeddingApiKey(firstText(providerConfig.getEmbeddingApiKey(), providerConfig.getAzure().getEmbeddingApiKey()))
                .embeddingBaseUrl(firstText(providerConfig.getEmbeddingBaseUrl(), providerConfig.getAzure().getEmbeddingEndpoint()))
                .timeoutSeconds(providerConfig.getAzure().getTimeout())
                .enabled(providerConfig.getAzure().isEnabled())
                .priority(providerConfig.getAzure().getPriority())
                .customConfig(Map.of("executionLayer", "spring-ai", "springAiProvider", "openai-azure"))
                .build();
            case ANTHROPIC -> ProviderConfig.builder()
                .providerName(family.providerName())
                .apiKey(providerConfig.getAnthropic().getApiKey())
                .baseUrl(firstText(providerConfig.getAnthropic().getBaseUrl(), DEFAULT_ANTHROPIC_BASE_URL))
                .defaultModel(firstText(providerConfig.getAnthropic().getModel(), DEFAULT_ANTHROPIC_MODEL))
                .maxTokens(providerConfig.getAnthropic().getMaxTokens())
                .temperature(providerConfig.getAnthropic().getTemperature())
                .timeoutSeconds(providerConfig.getAnthropic().getTimeout())
                .enabled(providerConfig.getAnthropic().isEnabled())
                .priority(providerConfig.getAnthropic().getPriority())
                .customConfig(Map.of("executionLayer", "spring-ai", "springAiProvider", "anthropic"))
                .build();
            case GEMINI -> ProviderConfig.builder()
                .providerName(family.providerName())
                .apiKey(providerConfig.getGemini().getApiKey())
                .baseUrl(providerConfig.getGemini().getBaseUrl())
                .defaultModel(firstText(providerConfig.getGemini().getModel(), DEFAULT_GEMINI_MODEL))
                .defaultEmbeddingModel(firstText(providerConfig.getGemini().getEmbeddingModel(), DEFAULT_GEMINI_EMBEDDING_MODEL))
                .embeddingApiKey(firstText(providerConfig.getEmbeddingApiKey(), providerConfig.getGemini().getApiKey()))
                .maxTokens(providerConfig.getGemini().getMaxTokens())
                .temperature(providerConfig.getGemini().getTemperature())
                .timeoutSeconds(providerConfig.getGemini().getTimeout())
                .enabled(providerConfig.getGemini().isEnabled())
                .priority(providerConfig.getGemini().getPriority())
                .customConfig(Map.of("executionLayer", "spring-ai", "springAiProvider", "google-genai"))
                .build();
            case SPRING_AI_ONNX -> ProviderConfig.builder()
                .providerName(family.providerName())
                .defaultEmbeddingModel(firstText(providerConfig.getSpringAiOnnx().getModelAlias(), "all-MiniLM-L6-v2"))
                .enabled(providerConfig.getSpringAiOnnx().isEnabled())
                .customConfig(Map.of("executionLayer", "spring-ai", "springAiProvider", "transformers-onnx"))
                .build();
        };
    }

    public int embeddingDimension(String providerName) {
        SpringAiProviderFamily family = requireFamily(providerName);
        if (SpringAiProviderFamily.OPENAI.equals(family)) {
            Integer configured = providerConfig.getOpenai().getEmbeddingDimensions();
            if (configured != null && configured > 0) {
                return configured;
            }
            String model = firstText(providerConfig.getOpenai().getEmbeddingModel(), DEFAULT_OPENAI_EMBEDDING_MODEL);
            return model.contains("large") ? 3072 : 1536;
        }
        if (SpringAiProviderFamily.AZURE.equals(family)) {
            return 1536;
        }
        if (SpringAiProviderFamily.GEMINI.equals(family)) {
            return 768;
        }
        if (SpringAiProviderFamily.SPRING_AI_ONNX.equals(family)) {
            Integer configured = providerConfig.getSpringAiOnnx().getDimensions();
            return configured != null && configured > 0 ? configured : DEFAULT_SPRING_AI_ONNX_DIMENSIONS;
        }
        return 0;
    }

    @Override
    public void destroy() {
        chatModels.values().forEach(this::closeIfNecessary);
        embeddingModels.values().forEach(this::closeIfNecessary);
        chatModels.clear();
        embeddingModels.clear();
    }

    private ChatModel buildChatModel(ChatConnection connection) {
        return switch (connection.family()) {
            case OPENAI, AZURE -> OpenAiChatModel.builder()
                .options(openAiConnectionOptions(connection))
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
            case ANTHROPIC -> AnthropicChatModel.builder()
                .options(anthropicConnectionOptions(connection))
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
            case GEMINI -> GoogleGenAiChatModel.builder()
                .genAiClient(Client.builder().apiKey(connection.apiKey()).build())
                .options(GoogleGenAiChatOptions.builder().build())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
            case SPRING_AI_ONNX -> throw new UnsupportedOperationException("Spring AI ONNX is embedding-only.");
        };
    }

    private EmbeddingModel buildEmbeddingModel(EmbeddingConnection connection) {
        return switch (connection.family()) {
            case OPENAI, AZURE -> OpenAiEmbeddingModel.builder()
                .options(openAiEmbeddingConnectionOptions(connection))
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
            case GEMINI -> new GoogleGenAiTextEmbeddingModel(
                GoogleGenAiEmbeddingConnectionDetails.builder().apiKey(connection.apiKey()).build(),
                GoogleGenAiTextEmbeddingOptions.builder().build());
            case ANTHROPIC -> throw new UnsupportedOperationException("Anthropic embeddings are not supported by Spring AI.");
            case SPRING_AI_ONNX -> transformersEmbeddingModel(connection);
        };
    }

    private OpenAiChatOptions openAiConnectionOptions(ChatConnection connection) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        builder.apiKey(connection.apiKey());
        if (hasText(connection.baseUrl())) {
            builder.baseUrl(connection.baseUrl());
        }
        if (SpringAiProviderFamily.AZURE.equals(connection.family()) && connection.azureNative()) {
            builder.azure(true);
            if (hasText(connection.apiVersion())) {
                builder.azureOpenAIServiceVersion(AzureOpenAIServiceVersion.fromString(connection.apiVersion()));
            }
        }
        builder.timeout(Duration.ofSeconds(connection.timeoutSeconds()));
        return builder.build();
    }

    private AnthropicChatOptions anthropicConnectionOptions(ChatConnection connection) {
        AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder();
        builder.apiKey(connection.apiKey());
        if (hasText(connection.baseUrl())) {
            builder.baseUrl(connection.baseUrl());
        }
        builder.timeout(Duration.ofSeconds(connection.timeoutSeconds()));
        return builder.build();
    }

    private OpenAiEmbeddingOptions openAiEmbeddingConnectionOptions(EmbeddingConnection connection) {
        OpenAiEmbeddingOptions.Builder builder = OpenAiEmbeddingOptions.builder();
        builder.apiKey(connection.apiKey());
        if (hasText(connection.baseUrl())) {
            builder.baseUrl(connection.baseUrl());
        }
        if (SpringAiProviderFamily.AZURE.equals(connection.family()) && connection.azureNative()) {
            builder.azure(true);
            if (hasText(connection.apiVersion())) {
                builder.azureOpenAIServiceVersion(AzureOpenAIServiceVersion.fromString(connection.apiVersion()));
            }
        }
        builder.timeout(Duration.ofSeconds(connection.timeoutSeconds()));
        return builder.build();
    }

    private OpenAiChatOptions openAiChatOptions(String model, Double temperature, Integer maxTokens) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        builder.model(model);
        builder.temperature(temperature);
        builder.maxTokens(maxTokens);
        return builder.build();
    }

    private OpenAiChatOptions azureChatOptions(String deployment,
                                               Double temperature,
                                               Integer maxTokens,
                                               String apiVersion,
                                               boolean azureNative) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        builder.model(deployment);
        builder.deploymentName(deployment);
        builder.temperature(temperature);
        builder.maxTokens(maxTokens);
        if (azureNative) {
            builder.azure(true);
            if (hasText(apiVersion)) {
                builder.azureOpenAIServiceVersion(AzureOpenAIServiceVersion.fromString(apiVersion));
            }
        }
        return builder.build();
    }

    private AnthropicChatOptions anthropicChatOptions(String model, Double temperature, Integer maxTokens) {
        AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder();
        builder.model(Model.of(model));
        builder.temperature(temperature);
        builder.maxTokens(maxTokens);
        return builder.build();
    }

    private GoogleGenAiChatOptions geminiChatOptions(String model, Double temperature, Integer maxTokens) {
        GoogleGenAiChatOptions.Builder builder = GoogleGenAiChatOptions.builder();
        builder.model(model);
        builder.temperature(temperature);
        builder.maxTokens(maxTokens);
        builder.maxOutputTokens(maxTokens);
        return builder.build();
    }

    private OpenAiEmbeddingOptions openAiEmbeddingOptions(String model, Integer dimensions) {
        OpenAiEmbeddingOptions.Builder builder = OpenAiEmbeddingOptions.builder();
        builder.model(model);
        if (dimensions != null && dimensions > 0) {
            builder.dimensions(dimensions);
        }
        return builder.build();
    }

    private OpenAiEmbeddingOptions azureEmbeddingOptions(String deployment, String apiVersion, boolean azureNative) {
        OpenAiEmbeddingOptions.Builder builder = OpenAiEmbeddingOptions.builder();
        builder.model(deployment);
        builder.deploymentName(deployment);
        if (azureNative) {
            builder.azure(true);
            if (hasText(apiVersion)) {
                builder.azureOpenAIServiceVersion(AzureOpenAIServiceVersion.fromString(apiVersion));
            }
        }
        return builder.build();
    }

    private GoogleGenAiTextEmbeddingOptions geminiEmbeddingOptions(String model) {
        GoogleGenAiTextEmbeddingOptions.Builder builder = GoogleGenAiTextEmbeddingOptions.builder();
        builder.model(model);
        return builder.build();
    }

    private EmbeddingModel transformersEmbeddingModel(EmbeddingConnection connection) {
        TransformersEmbeddingModel model = new TransformersEmbeddingModel();

        if (hasText(connection.modelUri())) {
            model.setModelResource(connection.modelUri());
        }
        if (hasText(connection.tokenizerUri())) {
            model.setTokenizerResource(connection.tokenizerUri());
        }
        if (connection.cacheEnabled() != null) {
            model.setDisableCaching(!connection.cacheEnabled());
        }
        if (hasText(connection.cacheDirectory())) {
            model.setResourceCacheDirectory(connection.cacheDirectory());
        }
        if (connection.gpuDeviceId() >= 0) {
            model.setGpuDeviceId(connection.gpuDeviceId());
        }
        if (hasText(connection.modelOutputName())) {
            model.setModelOutputName(connection.modelOutputName());
        }

        try {
            model.afterPropertiesSet();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize Spring AI ONNX embedding model", ex);
        }
        return model;
    }

    private ChatConnection chatConnection(SpringAiProviderFamily family, AIGenerationRequest request) {
        String baseUrl = chatBaseUrl(family, request);
        return new ChatConnection(
            family,
            chatApiKey(family, request),
            baseUrl,
            SpringAiProviderFamily.AZURE.equals(family) ? azureApiVersion(request) : null,
            SpringAiProviderFamily.AZURE.equals(family) && azureNative(baseUrl),
            chatTimeout(family)
        );
    }

    private EmbeddingConnection embeddingConnection(SpringAiProviderFamily family) {
        String baseUrl = embeddingBaseUrl(family);
        AIProviderConfig.SpringAiOnnxConfig onnxConfig = providerConfig.getSpringAiOnnx();
        boolean springAiOnnx = SpringAiProviderFamily.SPRING_AI_ONNX.equals(family);
        return new EmbeddingConnection(
            family,
            embeddingApiKey(family),
            baseUrl,
            SpringAiProviderFamily.AZURE.equals(family) ? azureEmbeddingApiVersion() : null,
            SpringAiProviderFamily.AZURE.equals(family) && azureNative(baseUrl),
            embeddingTimeout(family),
            springAiOnnx ? onnxConfig.getModelUri() : null,
            springAiOnnx ? onnxConfig.getTokenizerUri() : null,
            springAiOnnx ? onnxConfig.getCacheEnabled() : null,
            springAiOnnx ? onnxConfig.getCacheDirectory() : null,
            springAiOnnx && onnxConfig.getGpuDeviceId() != null ? onnxConfig.getGpuDeviceId() : -1,
            springAiOnnx ? onnxConfig.getModelOutputName() : null
        );
    }

    private boolean chatEnabled(SpringAiProviderFamily family) {
        return switch (family) {
            case OPENAI -> providerConfig.getOpenai().isEnabled();
            case AZURE -> providerConfig.getAzure().isEnabled();
            case ANTHROPIC -> providerConfig.getAnthropic().isEnabled();
            case GEMINI -> providerConfig.getGemini().isEnabled();
            case SPRING_AI_ONNX -> false;
        };
    }

    private boolean embeddingEnabled(SpringAiProviderFamily family) {
        return switch (family) {
            case OPENAI -> providerConfig.getOpenai().isEnabled();
            case AZURE -> providerConfig.getAzure().isEnabled();
            case GEMINI -> providerConfig.getGemini().isEnabled();
            case ANTHROPIC -> false;
            case SPRING_AI_ONNX -> providerConfig.getSpringAiOnnx().isEnabled();
        };
    }

    private boolean supportsEmbedding(SpringAiProviderFamily family) {
        return SpringAiProviderFamily.OPENAI.equals(family)
            || SpringAiProviderFamily.AZURE.equals(family)
            || SpringAiProviderFamily.GEMINI.equals(family)
            || SpringAiProviderFamily.SPRING_AI_ONNX.equals(family);
    }

    private String chatApiKey(SpringAiProviderFamily family, AIGenerationRequest request) {
        ProviderRequestOverrideSupport.LlmConnectionOverride override =
            request != null ? ProviderRequestOverrideSupport.read(request.getParameters())
                : ProviderRequestOverrideSupport.LlmConnectionOverride.empty();
        if (hasText(override.apiKey())) {
            return override.apiKey();
        }
        return switch (family) {
            case OPENAI -> providerConfig.getOpenai().getApiKey();
            case AZURE -> providerConfig.getAzure().getApiKey();
            case ANTHROPIC -> providerConfig.getAnthropic().getApiKey();
            case GEMINI -> providerConfig.getGemini().getApiKey();
            case SPRING_AI_ONNX -> null;
        };
    }

    private String chatBaseUrl(SpringAiProviderFamily family, AIGenerationRequest request) {
        ProviderRequestOverrideSupport.LlmConnectionOverride override =
            request != null ? ProviderRequestOverrideSupport.read(request.getParameters())
                : ProviderRequestOverrideSupport.LlmConnectionOverride.empty();
        if (hasText(override.baseUrl())) {
            return override.baseUrl();
        }
        return switch (family) {
            case OPENAI -> firstText(providerConfig.getOpenai().getBaseUrl(), DEFAULT_OPENAI_BASE_URL);
            case AZURE -> providerConfig.getAzure().getEndpoint();
            case ANTHROPIC -> firstText(providerConfig.getAnthropic().getBaseUrl(), DEFAULT_ANTHROPIC_BASE_URL);
            case GEMINI -> providerConfig.getGemini().getBaseUrl();
            case SPRING_AI_ONNX -> null;
        };
    }

    private String embeddingApiKey(SpringAiProviderFamily family) {
        return switch (family) {
            case OPENAI -> firstText(providerConfig.getEmbeddingApiKey(),
                providerConfig.getOpenai().getEmbeddingApiKey(), providerConfig.getOpenai().getApiKey());
            case AZURE -> firstText(providerConfig.getEmbeddingApiKey(),
                providerConfig.getAzure().getEmbeddingApiKey(), providerConfig.getAzure().getApiKey());
            case GEMINI -> firstText(providerConfig.getEmbeddingApiKey(), providerConfig.getGemini().getApiKey());
            case ANTHROPIC -> null;
            case SPRING_AI_ONNX -> null;
        };
    }

    private String embeddingBaseUrl(SpringAiProviderFamily family) {
        return switch (family) {
            case OPENAI -> firstText(providerConfig.getEmbeddingBaseUrl(),
                providerConfig.getOpenai().getEmbeddingBaseUrl(), providerConfig.getOpenai().getBaseUrl(), DEFAULT_OPENAI_BASE_URL);
            case AZURE -> firstText(providerConfig.getEmbeddingBaseUrl(),
                providerConfig.getAzure().getEmbeddingEndpoint(), providerConfig.getAzure().getEndpoint());
            case GEMINI -> null;
            case ANTHROPIC -> null;
            case SPRING_AI_ONNX -> null;
        };
    }

    private String chatModel(AIGenerationRequest request) {
        return firstText(request != null ? request.getModel() : null,
            providerConfig.getOpenai().getModel(), DEFAULT_OPENAI_MODEL);
    }

    private String openAiEmbeddingModel(AIEmbeddingRequest request) {
        return firstText(request != null ? request.getModel() : null,
            providerConfig.getOpenai().getEmbeddingModel(), DEFAULT_OPENAI_EMBEDDING_MODEL);
    }

    private String azureDeployment(AIGenerationRequest request) {
        ProviderRequestOverrideSupport.LlmConnectionOverride override =
            request != null ? ProviderRequestOverrideSupport.read(request.getParameters())
                : ProviderRequestOverrideSupport.LlmConnectionOverride.empty();
        return firstText(override.deploymentName(), request != null ? request.getModel() : null,
            providerConfig.getAzure().getDeploymentName());
    }

    private String azureEmbeddingDeployment(AIEmbeddingRequest request) {
        return firstText(request != null ? request.getModel() : null,
            providerConfig.getEmbeddingDeploymentName(),
            providerConfig.getAzure().getEmbeddingDeploymentName(),
            providerConfig.getAzure().getDeploymentName());
    }

    private String azureApiVersion(AIGenerationRequest request) {
        ProviderRequestOverrideSupport.LlmConnectionOverride override =
            request != null ? ProviderRequestOverrideSupport.read(request.getParameters())
                : ProviderRequestOverrideSupport.LlmConnectionOverride.empty();
        return firstText(override.apiVersion(), providerConfig.getAzure().getApiVersion());
    }

    private String azureEmbeddingApiVersion() {
        return firstText(providerConfig.getEmbeddingApiVersion(),
            providerConfig.getAzure().getEmbeddingApiVersion(), providerConfig.getAzure().getApiVersion());
    }

    private boolean azureNative(String endpoint) {
        if (!hasText(endpoint)) {
            return true;
        }
        String normalized = endpoint.toLowerCase();
        return !normalized.contains("/models")
            && !normalized.contains("/openai/v1")
            && !normalized.contains("services.ai.azure.com");
    }

    private int chatTimeout(SpringAiProviderFamily family) {
        Integer configured = switch (family) {
            case OPENAI -> providerConfig.getOpenai().getTimeout();
            case AZURE -> providerConfig.getAzure().getTimeout();
            case ANTHROPIC -> providerConfig.getAnthropic().getTimeout();
            case GEMINI -> providerConfig.getGemini().getTimeout();
            case SPRING_AI_ONNX -> null;
        };
        return configured != null && configured > 0 ? configured : DEFAULT_TIMEOUT_SECONDS;
    }

    private int embeddingTimeout(SpringAiProviderFamily family) {
        Integer configured = switch (family) {
            case OPENAI -> providerConfig.getOpenai().getTimeout();
            case AZURE -> providerConfig.getAzure().getTimeout();
            case GEMINI -> providerConfig.getGemini().getTimeout();
            case ANTHROPIC -> null;
            case SPRING_AI_ONNX -> null;
        };
        return configured != null && configured > 0 ? configured : DEFAULT_TIMEOUT_SECONDS;
    }

    private Double chatTemperature(AIGenerationRequest request, Object config) {
        if (request != null && request.getTemperature() != null) {
            return request.getTemperature();
        }
        if (config == null) {
            return DEFAULT_TEMPERATURE;
        }
        Double configured = switch (config) {
            case AIProviderConfig.OpenAIConfig openAi -> openAi.getTemperature();
            case AIProviderConfig.AnthropicConfig anthropic -> anthropic.getTemperature();
            case AIProviderConfig.GeminiConfig gemini -> gemini.getTemperature();
            default -> null;
        };
        return configured != null ? configured : DEFAULT_TEMPERATURE;
    }

    private Integer chatMaxTokens(AIGenerationRequest request, Object config) {
        if (request != null && request.getMaxTokens() != null) {
            return request.getMaxTokens();
        }
        if (config == null) {
            return DEFAULT_MAX_TOKENS;
        }
        Integer configured = switch (config) {
            case AIProviderConfig.OpenAIConfig openAi -> openAi.getMaxTokens();
            case AIProviderConfig.AnthropicConfig anthropic -> anthropic.getMaxTokens();
            case AIProviderConfig.GeminiConfig gemini -> gemini.getMaxTokens();
            default -> null;
        };
        return configured != null && configured > 0 ? configured : DEFAULT_MAX_TOKENS;
    }

    private SpringAiProviderFamily requireFamily(String providerName) {
        return SpringAiProviderFamily.from(providerName)
            .orElseThrow(() -> new IllegalArgumentException("Unsupported Spring AI provider: " + providerName));
    }

    private void closeIfNecessary(Object model) {
        try {
            if (model instanceof DisposableBean disposableBean) {
                disposableBean.destroy();
            } else if (model instanceof AutoCloseable autoCloseable) {
                autoCloseable.close();
            }
        } catch (Exception ignored) {
            // Best-effort cleanup for cached SDK clients.
        }
    }

    private static String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return StringUtils.hasText(value);
    }

    private static String fingerprint(String secret) {
        if (!hasText(secret)) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private record ChatConnection(
        SpringAiProviderFamily family,
        String apiKey,
        String baseUrl,
        String apiVersion,
        boolean azureNative,
        int timeoutSeconds
    ) {
        ModelCacheKey cacheKey() {
            return new ModelCacheKey(family.providerName(), "chat", fingerprint(apiKey),
                baseUrl, apiVersion, azureNative, timeoutSeconds, null, null, null, null, -1, null);
        }
    }

    private record EmbeddingConnection(
        SpringAiProviderFamily family,
        String apiKey,
        String baseUrl,
        String apiVersion,
        boolean azureNative,
        int timeoutSeconds,
        String modelUri,
        String tokenizerUri,
        Boolean cacheEnabled,
        String cacheDirectory,
        int gpuDeviceId,
        String modelOutputName
    ) {
        ModelCacheKey cacheKey() {
            return new ModelCacheKey(family.providerName(), "embedding", fingerprint(apiKey),
                baseUrl, apiVersion, azureNative, timeoutSeconds, modelUri, tokenizerUri,
                cacheEnabled, cacheDirectory, gpuDeviceId, modelOutputName);
        }
    }

    private record ModelCacheKey(
        String providerName,
        String modelKind,
        String apiKeyFingerprint,
        String baseUrl,
        String apiVersion,
        boolean azureNative,
        int timeoutSeconds,
        String modelUri,
        String tokenizerUri,
        Boolean cacheEnabled,
        String cacheDirectory,
        int gpuDeviceId,
        String modelOutputName
    ) {
    }
}
