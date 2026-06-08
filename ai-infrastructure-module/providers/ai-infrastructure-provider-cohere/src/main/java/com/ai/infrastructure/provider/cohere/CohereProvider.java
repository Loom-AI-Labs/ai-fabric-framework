package com.ai.infrastructure.provider.cohere;

import com.ai.infrastructure.dto.AIGenerationRequest;
import com.ai.infrastructure.dto.AIGenerationResponse;
import com.ai.infrastructure.dto.AIGenerationInputPart;
import com.ai.infrastructure.dto.AIChatMessage;
import com.ai.infrastructure.dto.AIChatRole;
import com.ai.infrastructure.provider.AIProvider;
import com.ai.infrastructure.provider.ProviderConfig;
import com.ai.infrastructure.provider.ProviderRequestOverrideSupport;
import com.ai.infrastructure.provider.ProviderStatus;
import com.ai.infrastructure.provider.TransientInputSupport;
import com.ai.infrastructure.dto.AIEmbeddingRequest;
import com.ai.infrastructure.dto.AIEmbeddingResponse;
import com.ai.infrastructure.http.HttpClient;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cohere Provider Implementation
 * 
 * Real implementation of AI provider using Cohere API.
 * Provides content generation and embedding services.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class CohereProvider implements AIProvider {
    
    private final ProviderConfig config;
    private final HttpClient httpClient;
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicReference<LocalDateTime> lastSuccess = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> lastError = new AtomicReference<>();
    private final AtomicReference<String> lastErrorMessage = new AtomicReference<>();
    private final AtomicReference<Double> averageResponseTime = new AtomicReference<>(0.0);
    
    private static final String COHERE_BASE_URL = "https://api.cohere.ai/v1";
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int MAX_TRANSIENT_DOCUMENT_BYTES = 5 * 1024 * 1024;
    private static final int MAX_TRANSIENT_DOCUMENT_CHARS = 12_000;
    
    @Override
    public String getProviderName() {
        return "cohere";
    }
    
    @Override
    public boolean isAvailable() {
        try {
            return config.isValid() && config.isEnabled() && 
                   config.getApiKey() != null && !config.getApiKey().trim().isEmpty();
        } catch (Exception e) {
            log.warn("Cohere provider not available: {}", e.getMessage());
            return false;
        }
    }
    
    @Override
    public AIGenerationResponse generateContent(AIGenerationRequest request) {
        long startTime = System.currentTimeMillis();
        totalRequests.incrementAndGet();
        
        try {
            List<Map<String, String>> transientDocuments = buildTransientDocuments(request);
            log.debug("Generating content with Cohere: model={}, prompt={}", 
                     request.getModel(), request.getPrompt().substring(0, Math.min(100, request.getPrompt().length())));
            
            ProviderRequestOverrideSupport.LlmConnectionOverride connectionOverride =
                ProviderRequestOverrideSupport.read(request.getParameters());
            String baseUrl = hasText(connectionOverride.baseUrl()) ? connectionOverride.baseUrl() : config.getBaseUrl();
            String apiKey = hasText(connectionOverride.apiKey()) ? connectionOverride.apiKey() : config.getApiKey();
            String url = normalizeBaseUrl(baseUrl != null ? baseUrl : COHERE_BASE_URL) + "/chat";
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", request.getModel() != null ? request.getModel() : 
                          (config.getDefaultModel() != null ? config.getDefaultModel() : "command-r7b-12-2024"));
            
            // Add system prompt if present
            if (request.getSystemPrompt() != null && !request.getSystemPrompt().trim().isEmpty()) {
                // For intent extraction, enhance the system prompt to be very explicit about JSON-only responses
                String systemPrompt = request.getSystemPrompt();
                if (request.getGenerationType() != null && request.getGenerationType().contains("intent_extraction")) {
                    String jsonInstruction = "CRITICAL JSON REQUIREMENT: You are a JSON-only API endpoint. " +
                        "You MUST respond with ONLY valid JSON. No markdown code blocks (no ```json or ```), " +
                        "no explanations, no text before or after the JSON, no comments, no additional formatting. " +
                        "Just the raw JSON object. If you include any text other than JSON, the response will fail to parse.\n\n";
                    
                    systemPrompt = jsonInstruction + systemPrompt;
                    
                    log.info("Enhanced system prompt for intent extraction with JSON-only requirement (length: {})", systemPrompt.length());
                    log.debug("Enhanced system prompt preview: {}", systemPrompt.substring(0, Math.min(200, systemPrompt.length())));
                }
                requestBody.put("preamble", systemPrompt);
            }

            if (request.getMessages() != null && !request.getMessages().isEmpty()) {
                List<Map<String, Object>> chatHistory = new java.util.ArrayList<>();
                for (AIChatMessage msg : request.getMessages()) {
                    if (msg == null || msg.getRole() == null || msg.getContent() == null || msg.getContent().isBlank()) {
                        continue;
                    }
                    if (AIChatRole.SYSTEM.equals(msg.getRole())) {
                        continue;
                    }
                    String role = AIChatRole.USER.equals(msg.getRole()) ? "USER" : "CHATBOT";
                    chatHistory.add(Map.of("role", role, "message", msg.getContent()));
                }
                if (!chatHistory.isEmpty()) {
                    requestBody.put("chat_history", chatHistory);
                }
            }

            requestBody.put("message", request.getPrompt());
            if (!transientDocuments.isEmpty()) {
                requestBody.put("documents", transientDocuments);
                requestBody.put("prompt_truncation", "AUTO_PRESERVE_ORDER");
            }
            
            requestBody.put("max_tokens", request.getMaxTokens() != null ? request.getMaxTokens() : config.getMaxTokens());
            requestBody.put("temperature", request.getTemperature() != null ? request.getTemperature() : config.getTemperature());

            if (log.isInfoEnabled()) {
                log.info("=== COHERE API REQUEST ===");
                log.info(
                    "Cohere API request: url={}, model={}, temperature={}, maxTokens={}, hasPreamble={}, promptLength={}, transientDocuments={}",
                    url,
                    requestBody.get("model"),
                    requestBody.get("temperature"),
                    requestBody.get("max_tokens"),
                    requestBody.containsKey("preamble"),
                    request.getPrompt() != null ? request.getPrompt().length() : 0,
                    transientDocuments.size()
                );
                if (TransientInputSupport.hasFileUrlInputs(request)) {
                    log.info(
                        "Cohere API transientInputs={}",
                        TransientInputSupport.redactedDescriptors(TransientInputSupport.fileUrlInputParts(request))
                    );
                }
                String prompt = request.getPrompt();
                int len = prompt != null ? prompt.length() : 0;
                String snippet = prompt == null ? "" : prompt.substring(0, Math.min(500, len));
                log.info("Cohere API request promptSnippet={}", snippet);
                log.info("=== END COHERE API REQUEST ===");
            }
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<Map> response = exchangeWithRetry(url, HttpMethod.POST, entity, Map.class, "chat");
            
            long responseTime = System.currentTimeMillis() - startTime;
            updateMetrics(true, responseTime);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = response.getBody();
            String generatedText = (String) responseBody.get("text");

            if (log.isInfoEnabled()) {
                log.info("=== COHERE API RESPONSE ===");
                int contentLength = generatedText != null ? generatedText.length() : 0;
                log.info(
                    "Cohere API response: responseTimeMs={}, model={}, contentLength={}",
                    responseTime,
                    responseBody != null ? responseBody.get("model") : null,
                    contentLength
                );
                if (generatedText != null) {
                    log.info(
                        "Cohere API response contentSnippet={}",
                        generatedText.substring(0, Math.min(500, generatedText.length()))
                    );
                }
                log.info("=== END COHERE API RESPONSE ===");
            }
            
            log.debug("Cohere content generation completed in {}ms", responseTime);
            
            // Extract model from response or use request model
            String model = (String) responseBody.get("model");
            if (model == null) {
                model = request.getModel() != null ? request.getModel() : config.getDefaultModel();
            }
            
            return AIGenerationResponse.builder()
                .content(generatedText)
                .model(model)
                .usage(createUsageFromResponse(responseBody))
                .processingTimeMs(responseTime)
                .requestId(java.util.UUID.randomUUID().toString())
                .metadata(transientDocuments.isEmpty()
                    ? null
                    : Map.of(
                        "providerRoute", "cohere.chat.documents",
                        "transientInputs",
                        TransientInputSupport.redactedDescriptors(TransientInputSupport.fileUrlInputParts(request))
                    ))
                .build();
                
        } catch (UnsupportedTransientDocumentException e) {
            long responseTime = System.currentTimeMillis() - startTime;
            updateMetrics(false, responseTime);
            log.warn("Cohere transient document input was not used: {}", e.getMessage());
            return TransientInputSupport.unsupportedFileUrlResponse(request, getProviderName(), e.getMessage());
        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            updateMetrics(false, responseTime);
            
            log.error("Cohere content generation failed", e);
            lastError.set(LocalDateTime.now());
            lastErrorMessage.set(e.getMessage());
            
            throw new RuntimeException("Cohere content generation failed: " + e.getMessage(), e);
        }
    }

    private List<Map<String, String>> buildTransientDocuments(AIGenerationRequest request) {
        List<AIGenerationInputPart> fileParts = TransientInputSupport.fileUrlInputParts(request);
        if (fileParts.isEmpty()) {
            return List.of();
        }

        List<Map<String, String>> documents = new ArrayList<>();
        for (AIGenerationInputPart part : fileParts) {
            String contentType = TransientInputSupport.normalizeContentType(part.getContentType());
            if (!TransientInputSupport.isTextLikeContentType(contentType)
                && !TransientInputSupport.isPdfContentType(contentType)) {
                throw new UnsupportedTransientDocumentException(
                    "Cohere transient document adapter supports text-like and PDF content only; unsupported content type: " + contentType
                );
            }
            TransientInputSupport.FetchedTransientFile fetchedFile;
            try {
                fetchedFile = TransientInputSupport.fetchTransientFile(httpClient, part, MAX_TRANSIENT_DOCUMENT_BYTES);
            } catch (IllegalArgumentException ex) {
                throw new UnsupportedTransientDocumentException(ex.getMessage());
            }
            if (!TransientInputSupport.isTextLikeContentType(fetchedFile.contentType())
                && !fetchedFile.isPdf()) {
                throw new UnsupportedTransientDocumentException(
                    "Cohere transient document adapter received unsupported response content type: " + fetchedFile.contentType()
                );
            }
            String text = fetchedFile.isPdf()
                ? extractPdfText(fetchedFile)
                : TransientInputSupport.decodeUtf8Text(fetchedFile, MAX_TRANSIENT_DOCUMENT_CHARS);
            if (!hasText(text)) {
                throw new UnsupportedTransientDocumentException(
                    "Cohere transient document adapter could not extract readable text"
                );
            }

            Map<String, String> document = new HashMap<>();
            document.put("text", text);
            if (hasText(part.getDocumentId())) {
                document.put("id", part.getDocumentId().trim());
            }
            if (hasText(part.getFileName())) {
                document.put("title", part.getFileName().trim());
            }
            document.put("contentType", fetchedFile.contentType());
            documents.add(Map.copyOf(document));
        }
        return List.copyOf(documents);
    }

    private String extractPdfText(TransientInputSupport.FetchedTransientFile fetchedFile) {
        try (PDDocument document = PDDocument.load(fetchedFile.bytes())) {
            String text = new PDFTextStripper().getText(document);
            if (!hasText(text)) {
                return "";
            }
            text = text.replace('\u0000', ' ').trim();
            if (text.length() > MAX_TRANSIENT_DOCUMENT_CHARS) {
                return text.substring(0, MAX_TRANSIENT_DOCUMENT_CHARS);
            }
            return text;
        } catch (IOException ex) {
            throw new UnsupportedTransientDocumentException("Cohere transient document adapter could not read PDF text");
        }
    }
    
    @Override
    public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
        long startTime = System.currentTimeMillis();
        totalRequests.incrementAndGet();
        
        try {
            log.debug("Generating embedding with Cohere: model={}, text={}", 
                     request.getModel(), request.getText().substring(0, Math.min(100, request.getText().length())));
            
            String url = COHERE_BASE_URL + "/embed";
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + config.getApiKey());
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", request.getModel() != null ? request.getModel() : config.getDefaultEmbeddingModel());
            requestBody.put("texts", List.of(request.getText()));
            requestBody.put("input_type", "search_document");

            if (log.isInfoEnabled()) {
                log.info("=== COHERE EMBEDDING API REQUEST ===");
                log.info(
                    "Cohere embedding request: url={}, model={}, inputType={}, textLength={}",
                    url,
                    requestBody.get("model"),
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
            
            long responseTime = System.currentTimeMillis() - startTime;
            updateMetrics(true, responseTime);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = response.getBody();
            @SuppressWarnings("unchecked")
            List<List<Double>> embeddings = (List<List<Double>>) responseBody.get("embeddings");
            List<Double> embedding = embeddings.get(0);

            if (log.isInfoEnabled()) {
                log.info("=== COHERE EMBEDDING API RESPONSE ===");
                log.info(
                    "Cohere embedding response: responseTimeMs={}, model={}, dimensions={}",
                    responseTime,
                    responseBody != null ? responseBody.get("model") : null,
                    embedding != null ? embedding.size() : 0
                );
                log.info("=== END COHERE EMBEDDING API RESPONSE ===");
            }
            
            log.debug("Cohere embedding generation completed in {}ms", responseTime);
            
            return AIEmbeddingResponse.builder()
                .embedding(embedding)
                .model((String) responseBody.get("model"))
                .dimensions(embedding.size())
                .processingTimeMs(responseTime)
                .requestId(java.util.UUID.randomUUID().toString())
                .build();
                
        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            updateMetrics(false, responseTime);
            
            log.error("Cohere embedding generation failed", e);
            lastError.set(LocalDateTime.now());
            lastErrorMessage.set(e.getMessage());
            
            throw new RuntimeException("Cohere embedding generation failed: " + e.getMessage(), e);
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
                        "Cohere {} call failed with HTTP {} (attempt {}/{}). Retrying after {}ms.",
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
                        "Cohere {} call failed due to network/timeout (attempt {}/{}). Retrying after {}ms. Cause: {}",
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
        // defensive; loop always returns/throws
        throw new RuntimeException("Cohere " + operation + " call failed after retries");
    }

    private boolean isRetryableStatus(int status) {
        // Common transient failures: rate limiting and upstream 5xx from edge/network.
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
    public ProviderStatus getStatus() {
        return ProviderStatus.builder()
            .providerName(getProviderName())
            .available(isAvailable())
            .healthy(isHealthy())
            .lastSuccess(lastSuccess.get())
            .lastError(lastError.get())
            .lastErrorMessage(lastErrorMessage.get())
            .totalRequests(totalRequests.get())
            .successfulRequests(successfulRequests.get())
            .failedRequests(failedRequests.get())
            .averageResponseTime(averageResponseTime.get())
            .successRate(calculateSuccessRate())
            .lastUpdated(LocalDateTime.now())
            .build();
    }
    
    @Override
    public ProviderConfig getConfig() {
        return config;
    }
    
    /**
     * Check if provider is healthy
     * 
     * @return true if healthy
     */
    private boolean isHealthy() {
        if (!isAvailable()) {
            return false;
        }
        
        // Check if we have recent successful requests
        LocalDateTime recentSuccess = lastSuccess.get();
        if (recentSuccess == null) {
            return false;
        }
        
        // Consider healthy if last success was within last 5 minutes
        return recentSuccess.isAfter(LocalDateTime.now().minusMinutes(5));
    }
    
    /**
     * Calculate success rate
     * 
     * @return success rate
     */
    private double calculateSuccessRate() {
        long total = totalRequests.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) successfulRequests.get() / total;
    }
    
    /**
     * Update metrics
     * 
     * @param success success flag
     * @param responseTime response time in milliseconds
     */
    private void updateMetrics(boolean success, long responseTime) {
        if (success) {
            successfulRequests.incrementAndGet();
            lastSuccess.set(LocalDateTime.now());
        } else {
            failedRequests.incrementAndGet();
        }
        
        // Update average response time
        long total = totalRequests.get();
        double currentAvg = averageResponseTime.get();
        double newAvg = ((currentAvg * (total - 1)) + responseTime) / total;
        averageResponseTime.set(newAvg);
        
        log.debug("Updated Cohere metrics: success={}, responseTime={}ms, successRate={}", 
                 success, responseTime, calculateSuccessRate());
    }
    
    /**
     * Create usage object from response
     * 
     * @param responseBody response body
     * @return usage object
     */
    private Object createUsageFromResponse(Map<String, Object> responseBody) {
        Map<String, Object> usage = new HashMap<>();
        
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) responseBody.get("meta");
        if (meta != null) {
            // Cohere Chat API uses different structure
            @SuppressWarnings("unchecked")
            Map<String, Object> tokens = (Map<String, Object>) meta.get("tokens");
            if (tokens != null) {
                Object inputTokens = tokens.get("input_tokens");
                Object outputTokens = tokens.get("output_tokens");
                if (inputTokens != null && outputTokens != null) {
                    usage.put("prompt_tokens", inputTokens);
                    usage.put("completion_tokens", outputTokens);
                    int total = ((Number) inputTokens).intValue() + ((Number) outputTokens).intValue();
                    usage.put("total_tokens", total);
                }
            }
            // Also check for billed_units (newer API format)
            @SuppressWarnings("unchecked")
            Map<String, Object> billedUnits = (Map<String, Object>) meta.get("billed_units");
            if (billedUnits != null && tokens == null) {
                Object inputTokens = billedUnits.get("input_tokens");
                Object outputTokens = billedUnits.get("output_tokens");
                if (inputTokens != null) {
                    usage.put("prompt_tokens", inputTokens);
                }
                if (outputTokens != null) {
                    usage.put("completion_tokens", outputTokens);
                }
                if (inputTokens != null && outputTokens != null) {
                    int total = ((Number) inputTokens).intValue() + ((Number) outputTokens).intValue();
                    usage.put("total_tokens", total);
                }
            }
        }
        
        return usage;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return COHERE_BASE_URL;
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static final class UnsupportedTransientDocumentException extends RuntimeException {

        private UnsupportedTransientDocumentException(String message) {
            super(message);
        }
    }
}
