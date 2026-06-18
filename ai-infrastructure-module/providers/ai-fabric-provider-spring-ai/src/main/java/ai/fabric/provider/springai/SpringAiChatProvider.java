package ai.fabric.provider.springai;

import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.provider.AIProvider;
import ai.fabric.provider.ProviderConfig;
import ai.fabric.provider.ProviderStatus;
import ai.fabric.provider.TransientInputSupport;
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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SpringAiChatProvider implements AIProvider {

    private final SpringAiProviderFamily family;
    private final SpringAiModelResolver resolver;
    private final ProviderMetrics metrics;

    public SpringAiChatProvider(String providerName, SpringAiModelResolver resolver) {
        this.family = SpringAiProviderFamily.from(providerName)
            .orElseThrow(() -> new IllegalArgumentException("Unsupported Spring AI provider: " + providerName));
        this.resolver = resolver;
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

            ChatModel chatModel = resolver.resolveChatModel(family.providerName(), request);
            ChatOptions options = resolver.resolveChatOptions(family.providerName(), request);
            Prompt prompt = SpringAiPromptMapper.toPrompt(family, request, options);
            ChatResponse springResponse = chatModel.call(prompt);
            long elapsedMs = elapsedMs(start);
            metrics.recordSuccess(elapsedMs);
            return toAiFabricResponse(request, springResponse, elapsedMs);
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
            EmbeddingModel embeddingModel = resolver.resolveEmbeddingModel(family.providerName(), request);
            EmbeddingOptions options = resolver.resolveEmbeddingOptions(family.providerName(), request);
            EmbeddingResponse response = embeddingModel.call(new EmbeddingRequest(List.of(request.getText()), options));
            long elapsedMs = elapsedMs(start);
            metrics.recordSuccess(elapsedMs);
            return toAiFabricEmbeddingResponse(request, response, elapsedMs);
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
                                                    long elapsedMs) {
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

    private AIEmbeddingResponse toAiFabricEmbeddingResponse(AIEmbeddingRequest request,
                                                           EmbeddingResponse response,
                                                           long elapsedMs) {
        Embedding result = response != null ? response.getResult() : null;
        List<Double> vector = result != null ? toDoubleList(result.getOutput()) : List.of();
        String responseModel = response != null && response.getMetadata() != null ? response.getMetadata().getModel() : null;
        return AIEmbeddingResponse.builder()
            .embedding(vector)
            .model(responseModel != null ? responseModel : request.getModel())
            .dimensions(vector.size())
            .processingTimeMs(elapsedMs)
            .requestId(UUID.randomUUID().toString())
            .build();
    }

    private List<Double> toDoubleList(float[] values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        Double[] converted = new Double[values.length];
        for (int i = 0; i < values.length; i++) {
            converted[i] = (double) values[i];
        }
        return List.of(converted);
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
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
