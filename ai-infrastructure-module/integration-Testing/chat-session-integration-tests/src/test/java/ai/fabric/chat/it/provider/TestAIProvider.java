package ai.fabric.chat.it.provider;

import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.provider.AIProvider;
import ai.fabric.provider.ProviderConfig;
import ai.fabric.provider.ProviderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

/**
 * Deterministic in-memory AI provider for integration testing.
 *
 * <p>This keeps the chat-session integration tests runnable without external credentials while still
 * exercising the provider-selection and orchestration wiring.</p>
 */
@Component
@Order(0)
@ConditionalOnProperty(name = "test.enable-mock-provider", havingValue = "true", matchIfMissing = true)
public class TestAIProvider implements AIProvider {

    private static final Logger log = LoggerFactory.getLogger(TestAIProvider.class);

    private static final String PROVIDER_NAME = "test-provider";
    private static final int EMBEDDING_DIMENSION = 8;

    private final ProviderConfig config = ProviderConfig.builder()
        .providerName(PROVIDER_NAME)
        .apiKey("local-test-key")
        .baseUrl("http://localhost/test-ai-provider")
        .defaultModel("test-generation-model")
        .defaultEmbeddingModel("test-embedding-model")
        .maxTokens(2048)
        .temperature(0.0)
        .timeoutSeconds(5)
        .maxRetries(0)
        .retryDelayMs(0L)
        .rateLimitPerMinute(1000)
        .rateLimitPerDay(100000)
        .enabled(true)
        .priority(0)
        .build();

    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong successfulRequests = new AtomicLong();
    private final AtomicLong failedRequests = new AtomicLong();
    private final AtomicLong totalResponseTimeMs = new AtomicLong();

    private final AtomicReference<LocalDateTime> lastSuccess = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> lastError = new AtomicReference<>();
    private final AtomicReference<String> lastErrorMessage = new AtomicReference<>();

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public AIGenerationResponse generateContent(AIGenerationRequest request) {
        long start = System.currentTimeMillis();
        totalRequests.incrementAndGet();

        try {
            String prompt = request != null ? request.getPrompt() : "";
            String content = "[TEST CONTENT] " + (prompt != null ? prompt : "");

            AIGenerationResponse response = AIGenerationResponse.builder()
                .id(UUID.randomUUID().toString())
                .requestId(UUID.randomUUID().toString())
                .entityId(request != null ? request.getEntityId() : null)
                .entityType(request != null ? request.getEntityType() : null)
                .generationType(request != null ? request.getGenerationType() : "generic")
                .content(content)
                .model(config.getDefaultModel())
                .tokensUsed(content.length())
                .confidence(0.95)
                .processingTimeMs(System.currentTimeMillis() - start)
                .generatedAt(LocalDateTime.now())
                .status("SUCCESS")
                .build();

            recordSuccess(response.getProcessingTimeMs());
            return response;
        } catch (Exception ex) {
            recordFailure(ex);
            throw ex;
        }
    }

    @Override
    public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
        long start = System.currentTimeMillis();
        totalRequests.incrementAndGet();

        try {
            double seed = (request != null && request.getText() != null)
                ? Math.abs(request.getText().hashCode()) % 1000 / 1000.0
                : 0.5;

            List<Double> embedding = IntStream.range(0, EMBEDDING_DIMENSION)
                .mapToObj(idx -> Math.sin(seed + idx) * 0.5 + 0.5)
                .toList();

            AIEmbeddingResponse response = AIEmbeddingResponse.builder()
                .embedding(embedding)
                .model(config.getDefaultEmbeddingModel())
                .dimensions(EMBEDDING_DIMENSION)
                .processingTimeMs(System.currentTimeMillis() - start)
                .requestId(UUID.randomUUID().toString())
                .build();

            recordSuccess(response.getProcessingTimeMs());
            return response;
        } catch (Exception ex) {
            recordFailure(ex);
            throw ex;
        }
    }

    @Override
    public ProviderStatus getStatus() {
        long total = totalRequests.get();
        long successes = successfulRequests.get();
        long failures = failedRequests.get();
        double avgResponseTime = total > 0 ? (double) totalResponseTimeMs.get() / total : 0.0;
        double successRate = total > 0 ? (double) successes / total : 1.0;

        return ProviderStatus.builder()
            .providerName(PROVIDER_NAME)
            .available(true)
            .healthy(true)
            .lastSuccess(lastSuccess.get())
            .lastError(lastError.get())
            .lastErrorMessage(lastErrorMessage.get())
            .totalRequests(total)
            .successfulRequests(successes)
            .failedRequests(failures)
            .averageResponseTime(avgResponseTime)
            .successRate(successRate)
            .rateLimitRemaining(999)
            .rateLimitReset(LocalDateTime.now().plusMinutes(5))
            .details("Test provider delivering deterministic responses")
            .lastUpdated(LocalDateTime.now())
            .build();
    }

    @Override
    public ProviderConfig getConfig() {
        return config;
    }

    private void recordSuccess(long processingTimeMs) {
        successfulRequests.incrementAndGet();
        totalResponseTimeMs.addAndGet(processingTimeMs);
        lastSuccess.set(LocalDateTime.now());
    }

    private void recordFailure(Exception ex) {
        failedRequests.incrementAndGet();
        lastError.set(LocalDateTime.now());
        lastErrorMessage.set(ex.getMessage());
        log.warn("Test AI provider failure", ex);
    }
}
