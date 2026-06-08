package com.ai.infrastructure.provider.gemini;

import com.ai.infrastructure.config.AIProviderConfig;
import com.ai.infrastructure.dto.AIEmbeddingRequest;
import com.ai.infrastructure.dto.AIEmbeddingResponse;
import com.ai.infrastructure.embedding.EmbeddingProvider;
import com.ai.infrastructure.exception.AIServiceException;
import com.ai.infrastructure.http.HttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Google Gemini Embedding Provider
 * 
 * Implementation of EmbeddingProvider using Google Gemini's embedding API.
 * Supports models like embedding-001 and text-embedding-004.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class GeminiEmbeddingProvider implements EmbeddingProvider {
    
    private final AIProviderConfig aiProviderConfig;
    private final HttpClient httpClient;
    private boolean available = false;
    private int embeddingDimension = 768; // Default for text-embedding-004 (actual dimension will be determined at runtime)
    
    private static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private static final int MAX_RETRY_ATTEMPTS = 3;
    
    @PostConstruct
    public void initialize() {
        try {
            log.info("Initializing Gemini Embedding Provider");
            
            AIProviderConfig.GeminiConfig gemini = aiProviderConfig.getGemini();
            
            if (gemini.getApiKey() == null || gemini.getApiKey().trim().isEmpty()) {
                log.warn("Gemini API key not configured. Provider will not be available.");
                available = false;
                return;
            }

            // Mark configured as available without performing external network calls by default.
            available = true;

            if (!gemini.isValidateOnStartup()) {
                log.info("Gemini embedding startup validation disabled (validate-on-startup=false). Skipping probe call.");
                return;
            }
            
            // Test connection with a small embedding call
            try {
                // Temporarily set available to true for initialization test
                available = true;
                
                AIEmbeddingRequest testRequest = AIEmbeddingRequest.builder()
                    .text("test")
                    .model(gemini.getEmbeddingModel() != null ? gemini.getEmbeddingModel() : "text-embedding-004")
                    .build();
                
                AIEmbeddingResponse testResponse = generateEmbedding(testRequest);
                if (testResponse != null && testResponse.getEmbedding() != null && !testResponse.getEmbedding().isEmpty()) {
                    available = true;
                    embeddingDimension = testResponse.getEmbedding().size();
                    log.info("Gemini Embedding Provider initialized successfully with dimension: {}", embeddingDimension);
                } else {
                    available = false;
                }
            } catch (Exception e) {
                log.warn("Gemini Embedding Provider test call failed: {}", e.getMessage());
                available = false;
            }
            
        } catch (Exception e) {
            log.error("Failed to initialize Gemini Embedding Provider", e);
            available = false;
        }
    }
    
    @Override
    public String getProviderName() {
        return "gemini";
    }
    
    @Override
    public boolean isAvailable() {
        return available && httpClient != null;
    }
    
    @Override
    public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
        if (!isAvailable()) {
            throw new AIServiceException("Gemini Embedding Provider is not available");
        }
        
        try {
            AIProviderConfig.GeminiConfig gemini = aiProviderConfig.getGemini();
            String model = request.getModel() != null ? request.getModel() : 
                          (gemini.getEmbeddingModel() != null ? gemini.getEmbeddingModel() : "text-embedding-004");
            
            log.debug("Generating embedding using Gemini for text: {}", request.getText());
            
            long startTime = System.currentTimeMillis();
            
            String url = GEMINI_BASE_URL + "/models/" + model + ":embedContent?key=" + gemini.getApiKey();
            String safeUrl = url.replaceAll("([?&]key=)[^&]+", "$1***");
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            Map<String, Object> requestBody = new HashMap<>();
            
            // Gemini embedding API structure
            Map<String, Object> content = new HashMap<>();
            Map<String, Object> part = new HashMap<>();
            part.put("text", request.getText());
            content.put("parts", List.of(part));
            requestBody.put("content", content);

            if (log.isInfoEnabled()) {
                log.info("=== GEMINI EMBEDDING API REQUEST ===");
                log.info(
                    "Gemini embedding request: url={}, model={}, textLength={}",
                    safeUrl,
                    model,
                    request.getText() != null ? request.getText().length() : 0
                );
                String text = request.getText();
                int len = text != null ? text.length() : 0;
                String snippet = text == null ? "" : text.substring(0, Math.min(300, len));
                log.info("Gemini embedding request textSnippet={}", snippet);
                log.info("=== END GEMINI EMBEDDING API REQUEST ===");
            }
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<Map> response = exchangeWithRetry(url, HttpMethod.POST, entity, Map.class, "embedContent");
            
            long processingTime = System.currentTimeMillis() - startTime;
            
            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = response.getBody();
            @SuppressWarnings("unchecked")
            Map<String, Object> embeddingData = (Map<String, Object>) responseBody.get("embedding");
            @SuppressWarnings("unchecked")
            List<Double> values = (List<Double>) embeddingData.get("values");
            
            // Keep as List<Double> for embedding
            List<Double> embedding = new ArrayList<>(values);

            if (log.isInfoEnabled()) {
                log.info("=== GEMINI EMBEDDING API RESPONSE ===");
                log.info(
                    "Gemini embedding response: responseTimeMs={}, model={}, dimensions={}",
                    processingTime,
                    model,
                    embedding != null ? embedding.size() : 0
                );
                log.info("=== END GEMINI EMBEDDING API RESPONSE ===");
            }
            
            log.debug("Successfully generated Gemini embedding with {} dimensions in {}ms", 
                     embedding.size(), processingTime);
            
            return AIEmbeddingResponse.builder()
                .embedding(embedding)
                .model(model)
                .dimensions(embedding.size())
                .processingTimeMs(processingTime)
                .requestId(UUID.randomUUID().toString())
                .build();
                
        } catch (Exception e) {
            log.error("Error generating Gemini embedding", e);
            throw new AIServiceException("Failed to generate Gemini embedding", e);
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
                        "Gemini embedding {} call failed with HTTP {} (attempt {}/{}). Retrying after {}ms.",
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
                        "Gemini embedding {} call failed due to network/timeout (attempt {}/{}). Retrying after {}ms. Cause: {}",
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
        throw new AIServiceException("Gemini embedding " + operation + " call failed after retries");
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
