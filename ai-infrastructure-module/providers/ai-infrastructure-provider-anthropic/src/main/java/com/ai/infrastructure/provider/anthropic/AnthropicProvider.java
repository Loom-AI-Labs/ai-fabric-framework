package com.ai.infrastructure.provider.anthropic;

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
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Anthropic Provider Implementation
 * 
 * Real implementation of AI provider using Anthropic API.
 * Provides content generation and embedding services.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class AnthropicProvider implements AIProvider {
    
    private final ProviderConfig config;
    private final HttpClient httpClient;
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicReference<LocalDateTime> lastSuccess = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> lastError = new AtomicReference<>();
    private final AtomicReference<String> lastErrorMessage = new AtomicReference<>();
    private final AtomicReference<Double> averageResponseTime = new AtomicReference<>(0.0);
    
    private static final String ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1";
    private static final int MAX_RETRY_ATTEMPTS = 3;
    
    @Override
    public String getProviderName() {
        return "anthropic";
    }
    
    @Override
    public boolean isAvailable() {
        try {
            return config.isValid() && config.isEnabled() && 
                   config.getApiKey() != null && !config.getApiKey().trim().isEmpty();
        } catch (Exception e) {
            log.warn("Anthropic provider not available: {}", e.getMessage());
            return false;
        }
    }
    
    @Override
    public AIGenerationResponse generateContent(AIGenerationRequest request) {
        long startTime = System.currentTimeMillis();
        totalRequests.incrementAndGet();
        
        try {
            if (TransientInputSupport.hasFileUrlInputs(request)) {
                return generateContentWithDocumentUrls(request, startTime);
            }
            log.debug("Generating content with Anthropic: model={}, generationType={}, prompt={}", 
                     request.getModel(), request.getGenerationType(), 
                     request.getPrompt() != null ? request.getPrompt().substring(0, Math.min(100, request.getPrompt().length())) : "null");
            
            ProviderRequestOverrideSupport.LlmConnectionOverride connectionOverride =
                ProviderRequestOverrideSupport.read(request.getParameters());
            String baseUrl = hasText(connectionOverride.baseUrl()) ? connectionOverride.baseUrl() : config.getBaseUrl();
            String apiKey = hasText(connectionOverride.apiKey()) ? connectionOverride.apiKey() : config.getApiKey();
            String url = normalizeBaseUrl(baseUrl != null ? baseUrl : ANTHROPIC_BASE_URL) + "/messages";
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", request.getModel() != null ? request.getModel() : config.getDefaultModel());
            requestBody.put("max_tokens", request.getMaxTokens() != null ? request.getMaxTokens() : config.getMaxTokens());
            requestBody.put("temperature", request.getTemperature() != null ? request.getTemperature() : config.getTemperature());
            
            // Anthropic API requires system prompt as a top-level "system" parameter, not as a message role
            String systemPrompt = request.getSystemPrompt() != null ? request.getSystemPrompt() : "";
            
            // For intent extraction, enhance the system prompt to be very explicit about JSON-only responses
            if (request.getGenerationType() != null && request.getGenerationType().equals("intent_extraction")) {
                // Always prepend explicit JSON-only instruction at the beginning of system prompt
                // This ensures Anthropic understands it must return pure JSON
                String jsonInstruction = "CRITICAL JSON REQUIREMENT: You are a JSON-only API endpoint. " +
                    "You MUST respond with ONLY valid JSON. No markdown code blocks (no ```json or ```), " +
                    "no explanations, no text before or after the JSON, no comments, no additional formatting. " +
                    "Just the raw JSON object. If you include any text other than JSON, the response will fail to parse.\n\n";
                
                systemPrompt = jsonInstruction + systemPrompt;
                
                log.info("Enhanced system prompt for intent extraction with JSON-only requirement (length: {})", systemPrompt.length());
                log.debug("Enhanced system prompt preview: {}", systemPrompt.substring(0, Math.min(200, systemPrompt.length())));
            }
            
            // Set system prompt as top-level parameter (Anthropic API requirement)
            if (!systemPrompt.trim().isEmpty()) {
                requestBody.put("system", systemPrompt);
            }
            
            // Build messages list - only user messages, no system role (Anthropic doesn't allow system role in messages)
            List<Map<String, Object>> messages = new java.util.ArrayList<>();
            if (request.getMessages() != null && !request.getMessages().isEmpty()) {
                for (AIChatMessage msg : request.getMessages()) {
                    if (msg == null || msg.getRole() == null || msg.getContent() == null || msg.getContent().isBlank()) {
                        continue;
                    }
                    if (AIChatRole.SYSTEM.equals(msg.getRole())) {
                        continue;
                    }
                    messages.add(Map.of("role", msg.getRole().getApiValue(), "content", msg.getContent()));
                }
            }
            messages.add(Map.of("role", "user", "content", request.getPrompt() != null ? request.getPrompt() : ""));
            requestBody.put("messages", messages);

            if (log.isInfoEnabled()) {
                log.info("=== ANTHROPIC API REQUEST ===");
                log.info(
                    "Anthropic API request: url={}, model={}, temperature={}, maxTokens={}, hasSystem={}, messages={}",
                    url,
                    requestBody.get("model"),
                    requestBody.get("temperature"),
                    requestBody.get("max_tokens"),
                    requestBody.containsKey("system"),
                    messages.size()
                );
                String prompt = request.getPrompt();
                int len = prompt != null ? prompt.length() : 0;
                String snippet = prompt == null ? "" : prompt.substring(0, Math.min(500, len));
                log.info("Anthropic API request promptLength={}, promptSnippet={}", len, snippet);
                log.info("=== END ANTHROPIC API REQUEST ===");
            }
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<Map> response = exchangeWithRetry(url, HttpMethod.POST, entity, Map.class, "messages");
            
            long responseTime = System.currentTimeMillis() - startTime;
            updateMetrics(true, responseTime);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = response.getBody();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> content = (List<Map<String, Object>>) responseBody.get("content");
            String generatedText = (String) content.get(0).get("text");
            
            if (log.isInfoEnabled()) {
                log.info("=== ANTHROPIC API RESPONSE ===");
                int contentLength = generatedText != null ? generatedText.length() : 0;
                log.info(
                    "Anthropic API response: responseTimeMs={}, model={}, contentLength={}",
                    responseTime,
                    responseBody.get("model"),
                    contentLength
                );
                if (generatedText != null) {
                    log.info(
                        "Anthropic API response contentSnippet={}",
                        generatedText.substring(0, Math.min(500, generatedText.length()))
                    );
                }
                log.info("=== END ANTHROPIC API RESPONSE ===");
            }

            log.debug("Anthropic content generation completed in {}ms", responseTime);
            
            return AIGenerationResponse.builder()
                .content(generatedText)
                .model((String) responseBody.get("model"))
                .usage(createUsageFromResponse(responseBody))
                .processingTimeMs(responseTime)
                .requestId(java.util.UUID.randomUUID().toString())
                .build();
                
        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            updateMetrics(false, responseTime);
            
            log.error("Anthropic content generation failed", e);
            lastError.set(LocalDateTime.now());
            lastErrorMessage.set(e.getMessage());
            
            throw new RuntimeException("Anthropic content generation failed: " + e.getMessage(), e);
        }
    }

    private AIGenerationResponse generateContentWithDocumentUrls(AIGenerationRequest request, long startTime) {
        List<AIGenerationInputPart> fileParts = TransientInputSupport.fileUrlInputParts(request);
        for (AIGenerationInputPart part : fileParts) {
            try {
                TransientInputSupport.validateFileUrlInput(part);
            } catch (IllegalArgumentException ex) {
                return TransientInputSupport.unsupportedFileUrlResponse(request, getProviderName(), ex.getMessage());
            }
            if (!isAnthropicDocumentUrlSupported(part)) {
                return TransientInputSupport.unsupportedFileUrlResponse(
                    request,
                    getProviderName(),
                    "Anthropic document URL inputs are enabled only for supported document MIME types."
                );
            }
        }

        ProviderRequestOverrideSupport.LlmConnectionOverride connectionOverride =
            ProviderRequestOverrideSupport.read(request.getParameters());
        String baseUrl = hasText(connectionOverride.baseUrl()) ? connectionOverride.baseUrl() : config.getBaseUrl();
        String apiKey = hasText(connectionOverride.apiKey()) ? connectionOverride.apiKey() : config.getApiKey();
        String url = normalizeBaseUrl(baseUrl != null ? baseUrl : ANTHROPIC_BASE_URL) + "/messages";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", request.getModel() != null ? request.getModel() : config.getDefaultModel());
        requestBody.put("max_tokens", request.getMaxTokens() != null ? request.getMaxTokens() : config.getMaxTokens());
        requestBody.put("temperature", request.getTemperature() != null ? request.getTemperature() : config.getTemperature());
        if (hasText(request.getSystemPrompt())) {
            requestBody.put("system", request.getSystemPrompt());
        }

        List<Map<String, Object>> messages = new java.util.ArrayList<>();
        if (request.getMessages() != null && !request.getMessages().isEmpty()) {
            for (AIChatMessage msg : request.getMessages()) {
                if (msg == null || msg.getRole() == null || msg.getContent() == null || msg.getContent().isBlank()) {
                    continue;
                }
                if (AIChatRole.SYSTEM.equals(msg.getRole())) {
                    continue;
                }
                messages.add(Map.of("role", msg.getRole().getApiValue(), "content", msg.getContent()));
            }
        }

        List<Map<String, Object>> contentBlocks = new java.util.ArrayList<>();
        for (AIGenerationInputPart part : fileParts) {
            String contentType = TransientInputSupport.normalizeContentType(part.getContentType());
            if (TransientInputSupport.isPdfContentType(contentType)) {
                contentBlocks.add(Map.of(
                    "type", "document",
                    "source", Map.of(
                        "type", "url",
                        "url", part.getUrl()
                    )
                ));
                continue;
            }
            if (TransientInputSupport.isProviderVisionImageContentType(contentType)) {
                contentBlocks.add(Map.of(
                    "type", "image",
                    "source", Map.of(
                        "type", "url",
                        "url", part.getUrl()
                    )
                ));
            }
        }
        contentBlocks.add(Map.of("type", "text", "text", request.getPrompt() != null ? request.getPrompt() : ""));
        messages.add(Map.of("role", "user", "content", contentBlocks));
        requestBody.put("messages", messages);

        if (log.isInfoEnabled()) {
            log.info("=== ANTHROPIC DOCUMENT URL REQUEST ===");
            log.info(
                "Anthropic document URL request: url={}, model={}, maxTokens={}, fileUrlInputs={}, promptLength={}",
                url,
                requestBody.get("model"),
                requestBody.get("max_tokens"),
                fileParts.size(),
                request.getPrompt() != null ? request.getPrompt().length() : 0
            );
            log.info("Anthropic transientInputs={}", TransientInputSupport.redactedDescriptors(fileParts));
            log.info("=== END ANTHROPIC DOCUMENT URL REQUEST ===");
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<Map> response = exchangeWithRetry(url, HttpMethod.POST, entity, Map.class, "messages.document-url");

        long responseTime = System.currentTimeMillis() - startTime;
        updateMetrics(true, responseTime);

        @SuppressWarnings("unchecked")
        Map<String, Object> responseBody = response.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = responseBody != null ? (List<Map<String, Object>>) responseBody.get("content") : List.of();
        String generatedText = content != null && !content.isEmpty() ? (String) content.get(0).get("text") : "";

        return AIGenerationResponse.builder()
            .content(generatedText)
            .model(responseBody != null && responseBody.get("model") instanceof String model ? model : (String) requestBody.get("model"))
            .usage(createUsageFromResponse(responseBody != null ? responseBody : Map.of()))
            .processingTimeMs(responseTime)
            .requestId(java.util.UUID.randomUUID().toString())
            .metadata(Map.of(
                "providerRoute", "anthropic.messages.transient-url",
                "transientInputs", TransientInputSupport.redactedDescriptors(fileParts)
            ))
            .build();
    }

    private boolean isAnthropicDocumentUrlSupported(AIGenerationInputPart part) {
        if (part == null || !part.isFileUrl()) {
            return false;
        }
        return TransientInputSupport.isAnthropicUrlContentType(part.getContentType());
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
                        "Anthropic {} call failed with HTTP {} (attempt {}/{}). Retrying after {}ms.",
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
                        "Anthropic {} call failed due to network/timeout (attempt {}/{}). Retrying after {}ms. Cause: {}",
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
        throw new RuntimeException("Anthropic " + operation + " call failed after retries");
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
    public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
        long startTime = System.currentTimeMillis();
        totalRequests.incrementAndGet();
        
        try {
            log.debug("Generating embedding with Anthropic: model={}, text={}", 
                     request.getModel(), request.getText().substring(0, Math.min(100, request.getText().length())));
            
            // Note: Anthropic doesn't have a direct embedding API, so we'll use a workaround
            // or delegate to another provider. For now, we'll throw an exception.
            throw new UnsupportedOperationException("Anthropic does not provide embedding services directly. " +
                "Please use OpenAI or another provider for embeddings.");
                
        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            updateMetrics(false, responseTime);
            
            log.error("Anthropic embedding generation failed", e);
            lastError.set(LocalDateTime.now());
            lastErrorMessage.set(e.getMessage());
            
            throw new RuntimeException("Anthropic embedding generation failed: " + e.getMessage(), e);
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
        
        log.debug("Updated Anthropic metrics: success={}, responseTime={}ms, successRate={}", 
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
        Map<String, Object> inputTokens = (Map<String, Object>) responseBody.get("usage");
        if (inputTokens != null) {
            usage.put("prompt_tokens", inputTokens.get("input_tokens"));
            usage.put("completion_tokens", inputTokens.get("output_tokens"));
            usage.put("total_tokens", ((Number) inputTokens.get("input_tokens")).intValue() + 
                                     ((Number) inputTokens.get("output_tokens")).intValue());
        }
        
        return usage;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return ANTHROPIC_BASE_URL;
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
