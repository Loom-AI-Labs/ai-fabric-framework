package ai.fabric.provider.cohere;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.embedding.EmbeddingProvider;
import ai.fabric.exception.AIServiceException;
import ai.fabric.http.HttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Cohere Embedding Provider
 * 
 * Implementation of EmbeddingProvider using Cohere's embedding API.
 * Supports models like embed-english-v3.0, embed-multilingual-v3.0, etc.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class CohereEmbeddingProvider implements EmbeddingProvider {
    
    private final AIProviderConfig aiProviderConfig;
    private final HttpClient httpClient;
    private boolean available = false;
    private int embeddingDimension = 1024; // Default for embed-english-v3.0
    
    private static final String COHERE_BASE_URL = "https://api.cohere.ai/v1";
    private static final String DEFAULT_EMBEDDING_MODEL = "embed-english-v3.0";
    private static final int MAX_RETRY_ATTEMPTS = 3;
    
    @PostConstruct
    public void initialize() {
        try {
            log.info("Initializing Cohere Embedding Provider");
            
            AIProviderConfig.CohereConfig cohere = aiProviderConfig.getCohere();

            if (!cohere.isEnabled()) {
                log.info("Cohere embeddings disabled via configuration");
                available = false;
                return;
            }
            
            if (!hasText(cohere.getApiKey())) {
                log.warn("Cohere API key not configured. Provider will not be available.");
                available = false;
                return;
            }

            // Mark configured as available without performing external network calls by default.
            available = true;

            if (!cohere.isValidateOnStartup()) {
                log.info("Cohere embedding startup validation disabled (validate-on-startup=false). Skipping probe call.");
                return;
            }
            
            // Test connection with a small embedding call
            try {
                // Temporarily set available to true for initialization test
                available = true;
                
                AIEmbeddingRequest testRequest = AIEmbeddingRequest.builder()
                    .text("test")
                    .model(resolveModel(cohere, null))
                    .build();
                
                AIEmbeddingResponse testResponse = generateEmbedding(testRequest);
                if (testResponse != null && testResponse.getEmbedding() != null && !testResponse.getEmbedding().isEmpty()) {
                    available = true;
                    embeddingDimension = testResponse.getEmbedding().size();
                    log.info("Cohere Embedding Provider initialized successfully with dimension: {}", embeddingDimension);
                } else {
                    available = false;
                }
            } catch (Exception e) {
                log.warn("Cohere Embedding Provider test call failed: {}", e.getMessage());
                available = false;
            }
            
        } catch (Exception e) {
            log.error("Failed to initialize Cohere Embedding Provider", e);
            available = false;
        }
    }
    
    @Override
    public String getProviderName() {
        return "cohere";
    }
    
    @Override
    public boolean isAvailable() {
        return available && httpClient != null;
    }
    
    @Override
    public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
        AIProviderConfig.CohereConfig cohere = aiProviderConfig.getCohere();
        String model = resolveModel(cohere, request.getModel());
        return generateEmbeddingsInternal(List.of(requireText(request.getText())), model).get(0);
    }

    @Override
    public List<AIEmbeddingResponse> generateEmbeddings(List<String> texts) {
        AIProviderConfig.CohereConfig cohere = aiProviderConfig.getCohere();
        return generateEmbeddingsInternal(requireTexts(texts), resolveModel(cohere, null));
    }

    private List<AIEmbeddingResponse> generateEmbeddingsInternal(List<String> texts, String model) {
        if (!isAvailable()) {
            throw new AIServiceException("Cohere Embedding Provider is not available");
        }
        
        try {
            AIProviderConfig.CohereConfig cohere = aiProviderConfig.getCohere();
            
            log.debug("Generating {} Cohere embedding(s) using model={}", texts.size(), model);
            
            long startTime = System.currentTimeMillis();
            
            String url = normalizeBaseUrl(cohere.getBaseUrl()) + "/embed";
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + cohere.getApiKey());
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("texts", texts);
            requestBody.put("input_type", "search_document");

            log.info(
                "Cohere embedding request: url={}, model={}, inputType={}, inputCount={}",
                url,
                model,
                requestBody.get("input_type"),
                texts.size()
            );
            log.debug("Cohere embedding firstTextSnippet={}", snippet(texts.get(0), 300));
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<Map> response = exchangeWithRetry(url, HttpMethod.POST, entity, Map.class, "embed");
            
            long processingTime = System.currentTimeMillis() - startTime;
            
            Map<String, Object> responseBody = requireResponseBody(response, "Cohere embedding service returned empty response");
            List<List<Double>> embeddings = requireEmbeddings(responseBody, texts.size());
            String responseModel = responseBody.get("model") instanceof String resolvedModel ? resolvedModel : model;

            log.info(
                "Cohere embedding response: responseTimeMs={}, model={}, embeddings={}, dimensions={}",
                processingTime,
                responseModel,
                embeddings.size(),
                embeddings.get(0).size()
            );
            
            log.debug("Successfully generated Cohere embedding with {} dimensions in {}ms", 
                     embeddings.get(0).size(), processingTime);
            
            return embeddings.stream()
                .map(embedding -> {
                    embeddingDimension = embedding.size();
                    return AIEmbeddingResponse.builder()
                        .embedding(embedding)
                        .model(responseModel)
                        .dimensions(embedding.size())
                        .processingTimeMs(processingTime)
                        .requestId(UUID.randomUUID().toString())
                        .build();
                })
                .toList();
                
        } catch (Exception e) {
            log.error("Error generating Cohere embeddings", e);
            throw e instanceof AIServiceException serviceException
                ? serviceException
                : new AIServiceException("Failed to generate Cohere embedding", e);
        }
    }

    private <T> ResponseEntity<T> exchangeWithRetry(String url,
                                                    HttpMethod method,
                                                    HttpEntity<?> entity,
                                                    Class<T> responseType,
                                                    String operation) {
        long backoffMs = 400;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                return httpClient.exchange(url, method, entity, responseType);
            } catch (HttpStatusCodeException ex) {
                HttpStatusCode statusCode = ex.getStatusCode();
                int rawStatus = statusCode != null ? statusCode.value() : ex.getRawStatusCode();
                if (attempt < MAX_RETRY_ATTEMPTS && isRetryableStatus(rawStatus)) {
                    log.warn(
                        "Cohere embedding {} call failed with HTTP {} (attempt {}/{}). Retrying after {}ms.",
                        operation,
                        rawStatus,
                        attempt,
                        MAX_RETRY_ATTEMPTS,
                        backoffMs
                    );
                    sleepWithJitter(backoffMs);
                    backoffMs = Math.min(3000, backoffMs * 2);
                    continue;
                }
                throw ex;
            } catch (ResourceAccessException ex) {
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    log.warn(
                        "Cohere embedding {} call failed due to network/timeout (attempt {}/{}). Retrying after {}ms. Cause: {}",
                        operation,
                        attempt,
                        MAX_RETRY_ATTEMPTS,
                        backoffMs,
                        ex.getMessage()
                    );
                    sleepWithJitter(backoffMs);
                    backoffMs = Math.min(3000, backoffMs * 2);
                    continue;
                }
                throw ex;
            }
        }
        throw new AIServiceException("Cohere embedding " + operation + " call failed after retries");
    }

    private boolean isRetryableStatus(int status) {
        return status == 408 || status == 425 || status == 429 || (status >= 500 && status < 600);
    }

    private void sleepWithJitter(long baseBackoffMs) {
        long jitter = ThreadLocalRandom.current().nextLong(0, 200);
        long sleepMs = baseBackoffMs + jitter;
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
    
    @Override
    public int getEmbeddingDimension() {
        return embeddingDimension;
    }
    
    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("provider", getProviderName());
        status.put("available", isAvailable());
        status.put("dimension", embeddingDimension);
        return status;
    }

    private String resolveModel(AIProviderConfig.CohereConfig cohere, String requestModel) {
        if (hasText(requestModel)) {
            return requestModel.trim();
        }
        if (hasText(cohere.getEmbeddingModel())) {
            return cohere.getEmbeddingModel().trim();
        }
        return DEFAULT_EMBEDDING_MODEL;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (!hasText(baseUrl)) {
            return COHERE_BASE_URL;
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String requireText(String text) {
        if (!hasText(text)) {
            throw new AIServiceException("Cohere embedding text is required");
        }
        return text;
    }

    private List<String> requireTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new AIServiceException("Cohere embedding texts are required");
        }
        List<String> checked = new ArrayList<>();
        for (String text : texts) {
            checked.add(requireText(text));
        }
        return List.copyOf(checked);
    }

    private Map<String, Object> requireResponseBody(ResponseEntity<Map> response, String message) {
        if (response == null || response.getBody() == null) {
            throw new AIServiceException(message);
        }
        return copyStringKeyMap(response.getBody(), message);
    }

    private List<List<Double>> requireEmbeddings(Map<String, Object> responseBody, int expectedCount) {
        Object value = responseBody.get("embeddings");
        if (!(value instanceof List<?> items) || items.isEmpty()) {
            throw new AIServiceException("Cohere embedding response missing embeddings");
        }
        if (items.size() != expectedCount) {
            throw new AIServiceException("Cohere embedding response count did not match request count");
        }
        List<List<Double>> embeddings = new ArrayList<>();
        for (Object item : items) {
            embeddings.add(requireDoubleList(item, "Cohere embedding vector missing"));
        }
        return List.copyOf(embeddings);
    }

    private List<Double> requireDoubleList(Object value, String message) {
        if (!(value instanceof List<?> items) || items.isEmpty()) {
            throw new AIServiceException(message);
        }
        List<Double> numbers = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Number number)) {
                throw new AIServiceException(message + " contained non-numeric value");
            }
            numbers.add(number.doubleValue());
        }
        return List.copyOf(numbers);
    }

    private Map<String, Object> copyStringKeyMap(Map<?, ?> source, String message) {
        Map<String, Object> copy = new HashMap<>();
        source.forEach((key, value) -> {
            if (key == null) {
                throw new AIServiceException(message + " contained a null key");
            }
            copy.put(String.valueOf(key), value);
        });
        return copy;
    }

    private String snippet(String value, int maxLength) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.substring(0, Math.min(maxLength, value.length()));
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
