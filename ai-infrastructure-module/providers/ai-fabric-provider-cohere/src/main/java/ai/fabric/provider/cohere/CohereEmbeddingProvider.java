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
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

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
    private static final int MAX_RETRY_ATTEMPTS = 3;
    
    @PostConstruct
    public void initialize() {
        try {
            log.info("Initializing Cohere Embedding Provider");
            
            AIProviderConfig.CohereConfig cohere = aiProviderConfig.getCohere();
            
            if (cohere.getApiKey() == null || cohere.getApiKey().trim().isEmpty()) {
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
                    .model(cohere.getEmbeddingModel() != null ? cohere.getEmbeddingModel() : "embed-english-v3.0")
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
        if (!isAvailable()) {
            throw new AIServiceException("Cohere Embedding Provider is not available");
        }
        
        try {
            AIProviderConfig.CohereConfig cohere = aiProviderConfig.getCohere();
            String model = request.getModel() != null ? request.getModel() : 
                          (cohere.getEmbeddingModel() != null ? cohere.getEmbeddingModel() : "embed-english-v3.0");
            
            log.debug("Generating embedding using Cohere for text: {}", request.getText());
            
            long startTime = System.currentTimeMillis();
            
            String url = COHERE_BASE_URL + "/embed";
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + cohere.getApiKey());
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("texts", List.of(request.getText()));
            requestBody.put("input_type", "search_document");

            if (log.isInfoEnabled()) {
                log.info("=== COHERE EMBEDDING API REQUEST ===");
                log.info(
                    "Cohere embedding request: url={}, model={}, inputType={}, textLength={}",
                    url,
                    model,
                    requestBody.get("input_type"),
                    request.getText() != null ? request.getText().length() : 0
                );
                String text = request.getText();
                int len = text != null ? text.length() : 0;
                String snippet = text == null ? "" : text.substring(0, Math.min(300, len));
                log.info("Cohere embedding request textSnippet={}", snippet);
                log.info("=== END COHERE EMBEDDING API REQUEST ===");
            }
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<Map> response = exchangeWithRetry(url, HttpMethod.POST, entity, Map.class, "embed");
            
            long processingTime = System.currentTimeMillis() - startTime;
            
            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = response.getBody();
            @SuppressWarnings("unchecked")
            List<List<Double>> embeddings = (List<List<Double>>) responseBody.get("embeddings");
            List<Double> embedding = embeddings.get(0);

            if (log.isInfoEnabled()) {
                log.info("=== COHERE EMBEDDING API RESPONSE ===");
                log.info(
                    "Cohere embedding response: responseTimeMs={}, model={}, dimensions={}",
                    processingTime,
                    responseBody != null ? responseBody.get("model") : null,
                    embedding != null ? embedding.size() : 0
                );
                log.info("=== END COHERE EMBEDDING API RESPONSE ===");
            }
            
            log.debug("Successfully generated Cohere embedding with {} dimensions in {}ms", 
                     embedding.size(), processingTime);
            
            return AIEmbeddingResponse.builder()
                .embedding(embedding)
                .model(model)
                .dimensions(embedding.size())
                .processingTimeMs(processingTime)
                .requestId(UUID.randomUUID().toString())
                .build();
                
        } catch (Exception e) {
            log.error("Error generating Cohere embedding", e);
            throw new AIServiceException("Failed to generate Cohere embedding", e);
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
    public List<AIEmbeddingResponse> generateEmbeddings(List<String> texts) {
        return texts.stream()
            .map(text -> AIEmbeddingRequest.builder().text(text).build())
            .map(this::generateEmbedding)
            .collect(Collectors.toList());
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
}
