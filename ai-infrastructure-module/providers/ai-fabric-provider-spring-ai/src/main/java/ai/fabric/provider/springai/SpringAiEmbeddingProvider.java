package ai.fabric.provider.springai;

import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.embedding.EmbeddingProvider;
import ai.fabric.provider.ProviderStatus;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SpringAiEmbeddingProvider implements EmbeddingProvider {

    private final SpringAiProviderFamily family;
    private final SpringAiModelResolver resolver;
    private final ProviderMetrics metrics;

    public SpringAiEmbeddingProvider(String providerName, SpringAiModelResolver resolver) {
        this.family = SpringAiProviderFamily.from(providerName)
            .orElseThrow(() -> new IllegalArgumentException("Unsupported Spring AI provider: " + providerName));
        if (!resolver.supportsEmbedding(providerName)) {
            throw new IllegalArgumentException("Provider " + providerName + " does not support Spring AI embeddings.");
        }
        this.resolver = resolver;
        this.metrics = new ProviderMetrics(family.providerName() + "-embedding");
    }

    @Override
    public String getProviderName() {
        return family.providerName();
    }

    @Override
    public boolean isAvailable() {
        return resolver.isEmbeddingAvailable(family.providerName());
    }

    @Override
    public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
        long start = System.nanoTime();
        try {
            EmbeddingModel model = resolver.resolveEmbeddingModel(family.providerName(), request);
            EmbeddingOptions options = resolver.resolveEmbeddingOptions(family.providerName(), request);
            EmbeddingResponse response = model.call(new EmbeddingRequest(List.of(request.getText()), options));
            long elapsedMs = elapsedMs(start);
            metrics.recordSuccess(elapsedMs);
            return toResponse(request, response, elapsedMs);
        } catch (RuntimeException ex) {
            metrics.recordFailure(ex);
            throw ex;
        }
    }

    @Override
    public List<AIEmbeddingResponse> generateEmbeddings(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        long start = System.nanoTime();
        try {
            AIEmbeddingRequest request = AIEmbeddingRequest.builder().text(texts.getFirst()).build();
            EmbeddingModel model = resolver.resolveEmbeddingModel(family.providerName(), request);
            EmbeddingOptions options = resolver.resolveEmbeddingOptions(family.providerName(), request);
            EmbeddingResponse response = model.call(new EmbeddingRequest(List.copyOf(texts), options));
            long elapsedMs = elapsedMs(start);
            metrics.recordSuccess(elapsedMs);
            return toBatchResponses(response, elapsedMs);
        } catch (RuntimeException ex) {
            metrics.recordFailure(ex);
            throw ex;
        }
    }

    @Override
    public int getEmbeddingDimension() {
        return resolver.embeddingDimension(family.providerName());
    }

    @Override
    public Map<String, Object> getStatus() {
        ProviderStatus status = metrics.status(isAvailable(), "Spring AI embedding provider for " + family.providerName());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("provider", family.providerName());
        value.put("executionLayer", "spring-ai");
        value.put("available", status.isAvailable());
        value.put("healthy", status.isHealthy());
        value.put("totalRequests", status.getTotalRequests());
        value.put("successfulRequests", status.getSuccessfulRequests());
        value.put("failedRequests", status.getFailedRequests());
        value.put("averageResponseTime", status.getAverageResponseTime());
        value.put("dimension", getEmbeddingDimension());
        value.put("details", status.getDetails());
        return Map.copyOf(value);
    }

    private AIEmbeddingResponse toResponse(AIEmbeddingRequest request, EmbeddingResponse response, long elapsedMs) {
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

    private List<AIEmbeddingResponse> toBatchResponses(EmbeddingResponse response, long elapsedMs) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return List.of();
        }
        String responseModel = response.getMetadata() != null ? response.getMetadata().getModel() : null;
        List<AIEmbeddingResponse> results = new ArrayList<>();
        for (Embedding embedding : response.getResults()) {
            List<Double> vector = embedding != null ? toDoubleList(embedding.getOutput()) : List.of();
            results.add(AIEmbeddingResponse.builder()
                .embedding(vector)
                .model(responseModel)
                .dimensions(vector.size())
                .processingTimeMs(elapsedMs)
                .requestId(UUID.randomUUID().toString())
                .build());
        }
        return List.copyOf(results);
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
}
