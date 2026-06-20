package ai.fabric.provider.springai;

import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.exception.AIServiceException;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.tool.AIActionToolCallbackFactory;
import ai.fabric.provider.AIProvider;
import ai.fabric.provider.ProviderConfig;
import ai.fabric.provider.ProviderStatus;
import ai.fabric.provider.TransientInputSupport;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.tool.ToolCallback;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public class SpringAiChatProvider implements AIProvider {

    private final SpringAiProviderFamily family;
    private final SpringAiModelResolver resolver;
    private final SpringAiChatClientFactory chatClientFactory;
    private final Supplier<AIActionToolCallbackFactory> actionToolCallbackFactorySupplier;
    private final ProviderMetrics metrics;

    public SpringAiChatProvider(String providerName, SpringAiModelResolver resolver) {
        this(providerName, resolver, SpringAiChatClientFactory.noOp());
    }

    public SpringAiChatProvider(String providerName,
                                SpringAiModelResolver resolver,
                                SpringAiChatClientFactory chatClientFactory) {
        this(providerName, resolver, chatClientFactory, (Supplier<AIActionToolCallbackFactory>) null);
    }

    public SpringAiChatProvider(String providerName,
                                SpringAiModelResolver resolver,
                                SpringAiChatClientFactory chatClientFactory,
                                AIActionToolCallbackFactory actionToolCallbackFactory) {
        this(providerName, resolver, chatClientFactory, () -> actionToolCallbackFactory);
    }

    public SpringAiChatProvider(String providerName,
                                SpringAiModelResolver resolver,
                                SpringAiChatClientFactory chatClientFactory,
                                Supplier<AIActionToolCallbackFactory> actionToolCallbackFactorySupplier) {
        this.family = SpringAiProviderFamily.from(providerName)
            .orElseThrow(() -> new IllegalArgumentException("Unsupported Spring AI provider: " + providerName));
        this.resolver = resolver;
        this.chatClientFactory = chatClientFactory != null ? chatClientFactory : SpringAiChatClientFactory.noOp();
        this.actionToolCallbackFactorySupplier = actionToolCallbackFactorySupplier;
        this.metrics = new ProviderMetrics(family.providerName());
    }

    @Override
    public String getProviderName() {
        return family.providerName();
    }

    @Override
    public boolean isAvailable() {
        return resolver.isChatAvailable(family.providerName());
    }

    @Override
    public AIGenerationResponse generateContent(AIGenerationRequest request) {
        long start = System.nanoTime();
        try {
            var unsupportedReason = SpringAiPromptMapper.unsupportedTransientInputReason(family, request);
            if (unsupportedReason.isPresent()) {
                AIGenerationResponse response = TransientInputSupport.unsupportedFileUrlResponse(
                    request,
                    family.providerName(),
                    unsupportedReason.get()
                );
                metrics.recordSuccess(0L);
                return response;
            }

            ensureChatAvailable(request);
            ChatModel chatModel = resolver.resolveChatModel(family.providerName(), request);
            ChatOptions options = resolver.resolveChatOptions(family.providerName(), request);
            Prompt prompt = SpringAiPromptMapper.toPrompt(family, request, options);
            List<Advisor> requestAdvisors = resolveRequestAdvisors(request);
            List<ToolCallback> actionToolCallbacks = resolveActionToolCallbacks(request);
            ChatClient.ChatClientRequestSpec requestSpec = chatClientFactory.create(chatModel).prompt(prompt);
            if (!requestAdvisors.isEmpty()) {
                requestSpec = requestSpec.advisors(requestAdvisors);
            }
            if (!actionToolCallbacks.isEmpty()) {
                requestSpec = requestSpec.tools((Object[]) actionToolCallbacks.toArray(ToolCallback[]::new));
            }
            ChatResponse springResponse = requestSpec.call().chatResponse();
            long elapsedMs = elapsedMs(start);
            metrics.recordSuccess(elapsedMs);
            return toAiFabricResponse(request, springResponse, elapsedMs, actionToolCallbacks, requestAdvisors);
        } catch (RuntimeException ex) {
            metrics.recordFailure(ex);
            throw ex;
        }
    }

    @Override
    public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
        if (!resolver.supportsEmbedding(family.providerName())) {
            throw new UnsupportedOperationException("Provider " + family.providerName() + " does not support embeddings through Spring AI.");
        }
        long start = System.nanoTime();
        try {
            String text = requireEmbeddingText(request);
            ensureEmbeddingAvailable(request);
            EmbeddingModel embeddingModel = resolver.resolveEmbeddingModel(family.providerName(), request);
            EmbeddingOptions options = resolver.resolveEmbeddingOptions(family.providerName(), request);
            EmbeddingResponse response = embeddingModel.call(new EmbeddingRequest(List.of(text), options));
            long elapsedMs = elapsedMs(start);
            metrics.recordSuccess(elapsedMs);
            return toAiFabricEmbeddingResponse(request, response, fallbackEmbeddingModel(request), elapsedMs);
        } catch (RuntimeException ex) {
            metrics.recordFailure(ex);
            throw ex;
        }
    }

    @Override
    public ProviderStatus getStatus() {
        return metrics.status(isAvailable(), "Spring AI chat provider for " + family.providerName());
    }

    @Override
    public ProviderConfig getConfig() {
        return resolver.providerConfig(family.providerName());
    }

    private AIGenerationResponse toAiFabricResponse(AIGenerationRequest request,
                                                    ChatResponse springResponse,
                                                    long elapsedMs,
                                                    List<ToolCallback> actionToolCallbacks,
                                                    List<Advisor> requestAdvisors) {
        Generation generation = springResponse != null ? springResponse.getResult() : null;
        String content = generation != null && generation.getOutput() != null ? generation.getOutput().getText() : "";
        Usage usage = springResponse != null && springResponse.getMetadata() != null
            ? springResponse.getMetadata().getUsage()
            : null;
        String model = springResponse != null && springResponse.getMetadata() != null
            ? springResponse.getMetadata().getModel()
            : null;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", family.providerName());
        metadata.put("executionLayer", "spring-ai");
        if (generation != null && generation.getMetadata() != null) {
            if (generation.getMetadata().getFinishReason() != null) {
                metadata.put("finishReason", generation.getMetadata().getFinishReason());
            }
            metadata.put("generationMetadata", immutableNonNullMap(generation.getMetadata().entrySet().stream()
                .collect(LinkedHashMap::new, (map, entry) -> map.put(String.valueOf(entry.getKey()), entry.getValue()), Map::putAll)));
        }
        if (TransientInputSupport.hasFileUrlInputs(request)) {
            metadata.put("transientInputs", TransientInputSupport.redactedDescriptors(
                TransientInputSupport.fileUrlInputParts(request)));
        }
        List<String> actionToolNames = actionToolNames(actionToolCallbacks);
        if (!actionToolNames.isEmpty()) {
            metadata.put("actionToolCallbacks", actionToolNames.size());
            metadata.put("actionToolNames", actionToolNames);
        }
        List<String> requestAdvisorNames = SpringAiRequestAdvisorSupport.advisorNames(requestAdvisors);
        if (!requestAdvisorNames.isEmpty()) {
            metadata.put("springAiRequestAdvisors", requestAdvisorNames.size());
            metadata.put("springAiRequestAdvisorNames", requestAdvisorNames);
        } else if (requestAdvisors != null && !requestAdvisors.isEmpty()) {
            metadata.put("springAiRequestAdvisors", requestAdvisors.size());
        }

        return AIGenerationResponse.builder()
            .requestId(UUID.randomUUID().toString())
            .entityId(request != null ? request.getEntityId() : null)
            .entityType(request != null ? request.getEntityType() : null)
            .generationType(request != null ? request.getGenerationType() : null)
            .content(content)
            .model(model != null ? model : request != null ? request.getModel() : null)
            .tokensUsed(usage != null ? usage.getTotalTokens() : null)
            .usage(usage != null ? usage.getNativeUsage() : null)
            .processingTimeMs(elapsedMs)
            .metadata(immutableNonNullMap(metadata))
            .generatedAt(LocalDateTime.now())
            .status("SUCCESS")
            .build();
    }

    private List<String> actionToolNames(List<ToolCallback> actionToolCallbacks) {
        if (actionToolCallbacks == null || actionToolCallbacks.isEmpty()) {
            return List.of();
        }
        return actionToolCallbacks.stream()
            .filter(callback -> callback != null && callback.getToolDefinition() != null
                && callback.getToolDefinition().name() != null
                && !callback.getToolDefinition().name().isBlank())
            .map(callback -> callback.getToolDefinition().name().trim())
            .distinct()
            .toList();
    }

    private List<ToolCallback> resolveActionToolCallbacks(AIGenerationRequest request) {
        if (request == null || !AIActionToolCallbackFactory.isActionToolBridgeEnabled(request.getParameters())) {
            return List.of();
        }
        AIActionToolCallbackFactory actionToolCallbackFactory = resolveActionToolCallbackFactory();
        if (actionToolCallbackFactory == null) {
            return List.of();
        }
        ActionContext actionContext = AIActionToolCallbackFactory.actionContextFrom(request.getParameters())
            .orElse(null);
        if (actionContext == null) {
            return List.of();
        }
        List<String> actionNames = AIActionToolCallbackFactory.actionNamesFrom(request.getParameters());
        if (actionNames.isEmpty()) {
            return actionToolCallbackFactory.createCallbacks(actionContext);
        }
        List<ToolCallback> callbacks = new ArrayList<>();
        for (String actionName : actionNames) {
            actionToolCallbackFactory.createCallback(actionName, actionContext).ifPresent(callbacks::add);
        }
        return callbacks.isEmpty() ? List.of() : List.copyOf(callbacks);
    }

    private AIActionToolCallbackFactory resolveActionToolCallbackFactory() {
        return actionToolCallbackFactorySupplier != null ? actionToolCallbackFactorySupplier.get() : null;
    }

    private List<Advisor> resolveRequestAdvisors(AIGenerationRequest request) {
        if (request == null) {
            return List.of();
        }
        return SpringAiRequestAdvisorSupport.advisorsFrom(request.getParameters());
    }

    private AIEmbeddingResponse toAiFabricEmbeddingResponse(AIEmbeddingRequest request,
                                                           EmbeddingResponse response,
                                                           String fallbackModel,
                                                           long elapsedMs) {
        Embedding result = response != null ? response.getResult() : null;
        List<Double> vector = result != null ? toDoubleList(result.getOutput()) : List.of();
        String responseModel = response != null && response.getMetadata() != null ? response.getMetadata().getModel() : null;
        return AIEmbeddingResponse.builder()
            .embedding(vector)
            .model(responseModel != null ? responseModel : fallbackModel)
            .dimensions(vector.size())
            .processingTimeMs(elapsedMs)
            .requestId(UUID.randomUUID().toString())
            .build();
    }

    private List<Double> toDoubleList(float[] values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        List<Double> converted = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) {
            converted.add((double) values[i]);
        }
        return List.copyOf(converted);
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private String requireEmbeddingText(AIEmbeddingRequest request) {
        if (request == null || request.getText() == null || request.getText().isBlank()) {
            throw new IllegalArgumentException("Embedding text cannot be blank");
        }
        if (request.getText().length() > 8000) {
            throw new IllegalArgumentException("Embedding text cannot exceed 8000 characters");
        }
        return request.getText();
    }

    private String fallbackEmbeddingModel(AIEmbeddingRequest request) {
        if (request != null && request.getModel() != null && !request.getModel().isBlank()) {
            return request.getModel();
        }
        return resolver.providerConfig(family.providerName()).getDefaultEmbeddingModel();
    }

    private void ensureChatAvailable(AIGenerationRequest request) {
        if (!resolver.isChatAvailable(family.providerName(), request)) {
            throw new AIServiceException("Spring AI provider '" + family.providerName()
                + "' is not available for chat"
                + ". Check provider enablement, credentials, endpoint/deployment configuration, and model availability.");
        }
    }

    private void ensureEmbeddingAvailable(AIEmbeddingRequest request) {
        if (!resolver.isEmbeddingAvailable(family.providerName(), request)) {
            throw new AIServiceException("Spring AI provider '" + family.providerName()
                + "' is not available for embedding"
                + ". Check provider enablement, credentials, endpoint/deployment configuration, and model availability.");
        }
    }

    private Map<String, Object> immutableNonNullMap(Map<String, Object> source) {
        Map<String, Object> compacted = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                if (key != null && value != null) {
                    compacted.put(key, value);
                }
            });
        }
        return Map.copyOf(compacted);
    }
}
