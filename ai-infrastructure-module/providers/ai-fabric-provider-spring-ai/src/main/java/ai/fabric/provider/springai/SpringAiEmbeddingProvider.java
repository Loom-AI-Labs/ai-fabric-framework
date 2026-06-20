package ai.fabric.provider.springai;

import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.embedding.EmbeddingProvider;
import ai.fabric.exception.AIServiceException;
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
            String text = requireEmbeddingText(request);
            ensureAvailable(request);
            EmbeddingModel model = resolver.resolveEmbeddingModel(family.providerName(), request);
            EmbeddingOptions options = resolver.resolveEmbeddingOptions(family.providerName(), request);
            EmbeddingResponse response = model.call(new EmbeddingRequest(List.of(text), options));
            long elapsedMs = elapsedMs(start);
            metrics.recordSuccess(elapsedMs);
            return toResponse(request, response, fallbackEmbeddingModel(request), elapsedMs);
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
        List<String> validatedTexts = requireEmbeddingTexts(texts);
        long start = System.nanoTime();
        try {
            AIEmbeddingRequest request = AIEmbeddingRequest.builder().text(validatedTexts.getFirst()).build();
            ensureAvailable(request);
            EmbeddingModel model = resolver.resolveEmbeddingModel(family.providerName(), request);
            EmbeddingOptions options = resolver.resolveEmbeddingOptions(family.providerName(), request);
            EmbeddingResponse response = model.call(new EmbeddingRequest(validatedTexts, options));
            long elapsedMs = elapsedMs(start);
            metrics.recordSuccess(elapsedMs);
            return toBatchResponses(response, validatedTexts.size(), fallbackEmbeddingModel(request), elapsedMs);
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

    private AIEmbeddingResponse toResponse(AIEmbeddingRequest request,
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

    private List<AIEmbeddingResponse> toBatchResponses(EmbeddingResponse response,
                                                       int expectedCount,
                                                       String fallbackModel,
                                                       long elapsedMs) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            throw new IllegalStateException("Spring AI embedding response did not contain any embeddings");
        }
        if (response.getResults().size() != expectedCount) {
            throw new IllegalStateException("Spring AI embedding response count "
                + response.getResults().size() + " did not match request count " + expectedCount);
        }
        String responseModel = response.getMetadata() != null ? response.getMetadata().getModel() : null;
        String model = responseModel != null ? responseModel : fallbackModel;
        List<AIEmbeddingResponse> results = new ArrayList<>(response.getResults().size());
        for (Embedding embedding : response.getResults()) {
            List<Double> vector = embedding != null ? toDoubleList(embedding.getOutput()) : List.of();
            results.add(AIEmbeddingResponse.builder()
                .embedding(vector)
                .model(model)
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

    private List<String> requireEmbeddingTexts(List<String> texts) {
        List<String> validated = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Embedding text at index " + i + " cannot be blank");
            }
            if (text.length() > 8000) {
                throw new IllegalArgumentException("Embedding text at index " + i + " cannot exceed 8000 characters");
            }
            validated.add(text);
        }
        return List.copyOf(validated);
    }

    private String fallbackEmbeddingModel(AIEmbeddingRequest request) {
        if (request != null && request.getModel() != null && !request.getModel().isBlank()) {
            return request.getModel();
        }
        return resolver.providerConfig(family.providerName()).getDefaultEmbeddingModel();
    }

    private void ensureAvailable(AIEmbeddingRequest request) {
        if (!resolver.isEmbeddingAvailable(family.providerName(), request)) {
            throw new AIServiceException("Spring AI embedding provider '" + family.providerName()
                + "' is not available. Check provider enablement, credentials, endpoint/deployment configuration, and model availability.");
        }
    }
}
