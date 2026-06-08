package com.ai.infrastructure.provider.openai;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI Provider Implementation
 * 
 * Real implementation of AI provider using OpenAI API.
 * Provides content generation and embedding services.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class OpenAIProvider implements AIProvider {
    
    private final ProviderConfig config;
    private final HttpClient httpClient;
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicReference<LocalDateTime> lastSuccess = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> lastError = new AtomicReference<>();
    private final AtomicReference<String> lastErrorMessage = new AtomicReference<>();
    private final AtomicReference<Double> averageResponseTime = new AtomicReference<>(0.0);
    
    private static final String PATH_CHAT_COMPLETIONS = "/chat/completions";
    private static final String PATH_RESPONSES = "/responses";
    private static final String PATH_EMBEDDINGS = "/embeddings";
    private static final int MAX_RETRY_ATTEMPTS = 3;
    
    @Override
    public String getProviderName() {
        return "openai";
    }
    
    @Override
    public boolean isAvailable() {
        try {
            return config.isValid() && config.isEnabled() && 
                   config.getApiKey() != null && !config.getApiKey().trim().isEmpty();
        } catch (Exception e) {
            log.warn("OpenAI provider not available: {}", e.getMessage());
            return false;
        }
    }
    
    @Override
    public AIGenerationResponse generateContent(AIGenerationRequest request) {
        long startTime = System.currentTimeMillis();
        totalRequests.incrementAndGet();
        
        try {
            if (TransientInputSupport.hasFileUrlInputs(request)) {
                return generateResponsesContent(request, startTime);
            }
            log.debug("Generating content with OpenAI: model={}, prompt={}", 
                     request.getModel(), request.getPrompt().substring(0, Math.min(100, request.getPrompt().length())));
            ProviderRequestOverrideSupport.LlmConnectionOverride connectionOverride =
                ProviderRequestOverrideSupport.read(request.getParameters());
            String baseUrl = hasText(connectionOverride.baseUrl()) ? connectionOverride.baseUrl() : config.getBaseUrl();
            String apiKey = hasText(connectionOverride.apiKey()) ? connectionOverride.apiKey() : config.getApiKey();
            String url = normalizeBaseUrl(baseUrl) + PATH_CHAT_COMPLETIONS;
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);
            
            // Build messages with system and user roles for better prompt control
            List<Map<String, String>> messages = new ArrayList<>();
            
            // Add system prompt if provided
            if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
                messages.add(Map.of(
                    "role", "system",
                    "content", request.getSystemPrompt()
                ));
            }

            // Add structured history messages (provider-native multi-message prompting)
            if (request.getMessages() != null && !request.getMessages().isEmpty()) {
                for (AIChatMessage msg : request.getMessages()) {
                    if (msg == null || msg.getRole() == null || msg.getContent() == null || msg.getContent().isBlank()) {
                        continue;
                    }
                    String role = msg.getRole().getApiValue();
                    if (role == null || role.isBlank()) {
                        continue;
                    }
                    // Avoid duplicating system prompt when systemPrompt is already provided.
                    if (AIChatRole.SYSTEM.equals(msg.getRole()) && request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
                        continue;
                    }
                    messages.add(Map.of("role", role, "content", msg.getContent()));
                }
            }
            
            // Add user prompt
            messages.add(Map.of(
                "role", "user",
                "content", request.getPrompt() != null ? request.getPrompt() : ""
            ));
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", request.getModel() != null ? request.getModel() : config.getDefaultModel());
            requestBody.put("messages", messages);
            putIfNotNull(requestBody, "max_completion_tokens", request.getMaxTokens() != null ? request.getMaxTokens() : config.getMaxTokens());
            putIfNotNull(requestBody, "temperature", request.getTemperature() != null ? request.getTemperature() : config.getTemperature());
            requestBody.put("top_p", 0.1);  // Lower top_p for more deterministic responses

            applyResponseFormat(requestBody, request.getParameters());
            
            // IMPORTANT: Never use System.out for provider logging; it bypasses log levels and makes CI noisy.
            // Emit request/response summaries + payload snippets at INFO (visible at "normal").
            if (log.isInfoEnabled()) {
                log.info("=== OPENAI API REQUEST ===");
                log.info(
                    "OpenAI API request: url={}, model={}, temperature={}, topP={}, maxCompletionTokens={}, messages={}",
                    url,
                    requestBody.get("model"),
                    requestBody.get("temperature"),
                    requestBody.get("top_p"),
                    requestBody.get("max_completion_tokens"),
                    messages.size()
                );
                for (int i = 0; i < messages.size(); i++) {
                    Map<String, String> msg = messages.get(i);
                    String role = msg.get("role");
                    String content = msg.get("content");
                    int len = content != null ? content.length() : 0;
                    String snippet = content == null ? "" : content.substring(0, Math.min(500, len));
                    log.info(
                        "OpenAI API request message[{}]: role={}, contentLength={}, contentSnippet={}",
                        i,
                        role,
                        len,
                        snippet
                    );
                }
                log.info("=== END OPENAI API REQUEST ===");
            }
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<Map> response = exchangeWithRetry(url, HttpMethod.POST, entity, Map.class, "chat.completions");
            
            long responseTime = System.currentTimeMillis() - startTime;
            updateMetrics(true, responseTime);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = response.getBody();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
            @SuppressWarnings("unchecked")
            Map<String, String> message = (Map<String, String>) choices.get(0).get("message");
            String content = message.get("content");
            
            if (log.isInfoEnabled()) {
                log.info("=== OPENAI API RESPONSE ===");
                Object finishReason = choices.get(0).get("finish_reason");
                int contentLength = content != null ? content.length() : 0;
                log.info(
                    "OpenAI API response: responseTimeMs={}, model={}, finishReason={}, contentLength={}",
                    responseTime,
                    responseBody.get("model"),
                    finishReason,
                    contentLength
                );
                if (content != null) {
                    log.info(
                        "OpenAI API response contentSnippet={}",
                        content.substring(0, Math.min(500, content.length()))
                    );
                }
                log.info("=== END OPENAI API RESPONSE ===");
            }
            
            log.debug("OpenAI content generation completed in {}ms", responseTime);
            
            return AIGenerationResponse.builder()
                .content(content)
                .model((String) responseBody.get("model"))
                .usage(createUsageFromResponse(responseBody))
                .processingTimeMs(responseTime)
                .requestId(java.util.UUID.randomUUID().toString())
                .build();
                
        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            updateMetrics(false, responseTime);
            
            log.error("OpenAI content generation failed", e);
            lastError.set(LocalDateTime.now());
            lastErrorMessage.set(e.getMessage());
            
            throw new RuntimeException("OpenAI content generation failed: " + e.getMessage(), e);
        }
    }

    private AIGenerationResponse generateResponsesContent(AIGenerationRequest request, long startTime) {
        ProviderRequestOverrideSupport.LlmConnectionOverride connectionOverride =
            ProviderRequestOverrideSupport.read(request.getParameters());
        String baseUrl = hasText(connectionOverride.baseUrl()) ? connectionOverride.baseUrl() : config.getBaseUrl();
        String apiKey = hasText(connectionOverride.apiKey()) ? connectionOverride.apiKey() : config.getApiKey();
        String url = normalizeBaseUrl(baseUrl) + PATH_RESPONSES;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        List<AIGenerationInputPart> fileParts = TransientInputSupport.fileUrlInputParts(request);
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of(
            "type", "input_text",
            "text", request.getPrompt() != null ? request.getPrompt() : ""
        ));
        for (AIGenerationInputPart part : fileParts) {
            try {
                TransientInputSupport.validateFileUrlInput(part);
            } catch (IllegalArgumentException ex) {
                long responseTime = System.currentTimeMillis() - startTime;
                updateMetrics(false, responseTime);
                return TransientInputSupport.unsupportedFileUrlResponse(
                    request,
                    getProviderName(),
                    ex.getMessage()
                );
            }
            String contentType = TransientInputSupport.normalizeContentType(part.getContentType());
            if (TransientInputSupport.isOpenAiResponsesImageUrlContentType(contentType)) {
                Map<String, Object> imageInput = new HashMap<>();
                imageInput.put("type", "input_image");
                imageInput.put("image_url", part.getUrl());
                content.add(imageInput);
                continue;
            }
            if (TransientInputSupport.isOpenAiResponsesFileUrlContentType(contentType)) {
                Map<String, Object> fileInput = new HashMap<>();
                fileInput.put("type", "input_file");
                fileInput.put("file_url", part.getUrl());
                content.add(fileInput);
                continue;
            }
            long responseTime = System.currentTimeMillis() - startTime;
            updateMetrics(false, responseTime);
            return TransientInputSupport.unsupportedFileUrlResponse(
                request,
                getProviderName(),
                "OpenAI Responses transient file inputs do not support content type: " + contentType
            );
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", request.getModel() != null ? request.getModel() : config.getDefaultModel());
        requestBody.put("input", List.of(Map.of("role", "user", "content", content)));
        if (hasText(request.getSystemPrompt())) {
            requestBody.put("instructions", request.getSystemPrompt());
        }
        putIfNotNull(requestBody, "max_output_tokens", request.getMaxTokens() != null ? request.getMaxTokens() : config.getMaxTokens());
        putIfNotNull(requestBody, "temperature", request.getTemperature() != null ? request.getTemperature() : config.getTemperature());

        if (log.isInfoEnabled()) {
            log.info("=== OPENAI RESPONSES API REQUEST ===");
            log.info(
                "OpenAI Responses request: url={}, model={}, temperature={}, maxOutputTokens={}, fileUrlInputs={}, promptLength={}",
                url,
                requestBody.get("model"),
                requestBody.get("temperature"),
                requestBody.get("max_output_tokens"),
                fileParts.size(),
                request.getPrompt() != null ? request.getPrompt().length() : 0
            );
            log.info("OpenAI Responses transientInputs={}", TransientInputSupport.redactedDescriptors(fileParts));
            log.info("=== END OPENAI RESPONSES API REQUEST ===");
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<Map> response = exchangeWithRetry(url, HttpMethod.POST, entity, Map.class, "responses");

        long responseTime = System.currentTimeMillis() - startTime;
        updateMetrics(true, responseTime);

        @SuppressWarnings("unchecked")
        Map<String, Object> responseBody = response.getBody();
        String contentText = extractResponsesText(responseBody);

        if (log.isInfoEnabled()) {
            int contentLength = contentText != null ? contentText.length() : 0;
            log.info("=== OPENAI RESPONSES API RESPONSE ===");
            log.info(
                "OpenAI Responses response: responseTimeMs={}, model={}, contentLength={}",
                responseTime,
                responseBody != null ? responseBody.get("model") : null,
                contentLength
            );
            if (contentText != null) {
                log.info(
                    "OpenAI Responses response contentSnippet={}",
                    contentText.substring(0, Math.min(500, contentText.length()))
                );
            }
            log.info("=== END OPENAI RESPONSES API RESPONSE ===");
        }

        return AIGenerationResponse.builder()
            .content(contentText)
            .model(responseBody != null && responseBody.get("model") instanceof String model ? model : (String) requestBody.get("model"))
            .usage(createUsageFromResponse(responseBody != null ? responseBody : Map.of()))
            .processingTimeMs(responseTime)
            .requestId(java.util.UUID.randomUUID().toString())
            .metadata(Map.of(
                "providerRoute", "openai.responses",
                "transientInputs", TransientInputSupport.redactedDescriptors(fileParts)
            ))
            .build();
    }

    private String extractResponsesText(Map<String, Object> responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return "";
        }
        Object outputText = responseBody.get("output_text");
        if (outputText instanceof String text && !text.isBlank()) {
            return text;
        }
        Object output = responseBody.get("output");
        if (!(output instanceof List<?> items)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> itemMap)) {
                continue;
            }
            Object content = itemMap.get("content");
            if (!(content instanceof List<?> contentItems)) {
                continue;
            }
            for (Object contentItem : contentItems) {
                if (!(contentItem instanceof Map<?, ?> contentMap)) {
                    continue;
                }
                Object text = contentMap.get("text");
                if (text == null) {
                    text = contentMap.get("output_text");
                }
                if (text instanceof String s && !s.isBlank()) {
                    sb.append(s);
                }
            }
        }
        return sb.toString();
    }
    
    @Override
    public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
        long startTime = System.currentTimeMillis();
        totalRequests.incrementAndGet();
        
        try {
            log.debug("Generating embedding with OpenAI: model={}, text={}", 
                     request.getModel(), request.getText().substring(0, Math.min(100, request.getText().length())));
            
            String url = normalizeBaseUrl(firstNonBlank(config.getEmbeddingBaseUrl(), config.getBaseUrl())) + PATH_EMBEDDINGS;
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + firstNonBlank(config.getEmbeddingApiKey(), config.getApiKey()));
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", request.getModel() != null ? request.getModel() : config.getDefaultEmbeddingModel());
            requestBody.put("input", request.getText());
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<Map> response = exchangeWithRetry(url, HttpMethod.POST, entity, Map.class, "embeddings");
            
            long responseTime = System.currentTimeMillis() - startTime;
            updateMetrics(true, responseTime);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = response.getBody();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) responseBody.get("data");
            @SuppressWarnings("unchecked")
            List<Double> embeddingValues = (List<Double>) data.get(0).get("embedding");
            
            log.debug("OpenAI embedding generation completed in {}ms", responseTime);
            
            return AIEmbeddingResponse.builder()
                .embedding(embeddingValues)
                .model((String) responseBody.get("model"))
                .dimensions(embeddingValues.size())
                .processingTimeMs(responseTime)
                .requestId(java.util.UUID.randomUUID().toString())
                .build();
                
        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            updateMetrics(false, responseTime);
            
            log.error("OpenAI embedding generation failed", e);
            lastError.set(LocalDateTime.now());
            lastErrorMessage.set(e.getMessage());
            
            throw new RuntimeException("OpenAI embedding generation failed: " + e.getMessage(), e);
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
                        "OpenAI {} call failed with HTTP {} (attempt {}/{}). Retrying after {}ms.",
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
                        "OpenAI {} call failed due to network/timeout (attempt {}/{}). Retrying after {}ms. Cause: {}",
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
        throw new RuntimeException("OpenAI " + operation + " call failed after retries");
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
        
        log.debug("Updated OpenAI metrics: success={}, responseTime={}ms, successRate={}", 
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
        Map<String, Object> usageData = (Map<String, Object>) responseBody.get("usage");
        if (usageData != null) {
            Object promptTokens = usageData.get("prompt_tokens");
            if (promptTokens == null) {
                promptTokens = usageData.get("input_tokens");
            }
            Object completionTokens = usageData.get("completion_tokens");
            if (completionTokens == null) {
                completionTokens = usageData.get("output_tokens");
            }
            usage.put("prompt_tokens", promptTokens);
            usage.put("completion_tokens", completionTokens);
            usage.put("total_tokens", usageData.get("total_tokens"));
        }
        
        return usage;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String firstNonBlank(String primary, String fallback) {
        return hasText(primary) ? primary : fallback;
    }

    private void applyResponseFormat(Map<String, Object> requestBody, Map<String, Object> parameters) {
        if (requestBody == null || parameters == null || parameters.isEmpty()) {
            return;
        }
        Object responseFormat = parameters.get("response_format");
        if (responseFormat == null) {
            responseFormat = parameters.get("responseFormat");
        }
        Object normalized = normalizeResponseFormat(responseFormat);
        if (normalized != null) {
            requestBody.put("response_format", normalized);
        }
    }

    private Object normalizeResponseFormat(Object responseFormat) {
        if (responseFormat == null) {
            return null;
        }
        if (responseFormat instanceof Map<?, ?>) {
            return responseFormat;
        }
        if (!(responseFormat instanceof String)) {
            return null;
        }
        String value = ((String) responseFormat).trim().toLowerCase();
        if (value.isEmpty()) {
            return null;
        }
        if (value.equals("json") || value.equals("json_object") || value.equals("json-object") || value.equals("jsonobject")) {
            return Map.of("type", "json_object");
        }
        if (value.equals("text")) {
            return Map.of("type", "text");
        }
        return null;
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (target == null || key == null || value == null) {
            return;
        }
        target.put(key, value);
    }
}
