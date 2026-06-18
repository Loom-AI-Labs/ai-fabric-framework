package ai.fabric.provider.anthropic;

import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.AIGenerationInputPart;
import ai.fabric.dto.AIChatMessage;
import ai.fabric.dto.AIChatRole;
import ai.fabric.provider.AIProvider;
import ai.fabric.provider.ProviderConfig;
import ai.fabric.provider.ProviderRequestOverrideSupport;
import ai.fabric.provider.ProviderStatus;
import ai.fabric.provider.TransientInputSupport;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.exception.AIServiceException;
import ai.fabric.http.HttpClient;
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
import java.util.Optional;
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
    private static final String DEFAULT_MODEL = "claude-3-7-sonnet-latest";
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
            String prompt = request.getPrompt();
            log.debug(
                "Generating content with Anthropic: model={}, generationType={}, prompt={}",
                request.getModel(),
                request.getGenerationType(),
                snippet(prompt, 100)
            );
            
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
            requestBody.put("model", firstNonBlank(request.getModel(), config.getDefaultModel(), DEFAULT_MODEL)
                .orElse(DEFAULT_MODEL));
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
            List<Map<String, Object>> messages = new ArrayList<>();
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
            messages.add(Map.of("role", "user", "content", prompt != null ? prompt : ""));
            requestBody.put("messages", messages);

            log.info(
                "Anthropic API request: url={}, model={}, temperature={}, maxTokens={}, hasSystem={}, messages={}, promptLength={}",
                url,
                requestBody.get("model"),
                requestBody.get("temperature"),
                requestBody.get("max_tokens"),
                requestBody.containsKey("system"),
                messages.size(),
                prompt != null ? prompt.length() : 0
            );
            log.debug("Anthropic API request promptSnippet={}", snippet(prompt, 500));
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<Map> response = exchangeWithRetry(url, HttpMethod.POST, entity, Map.class, "messages");
            
            long responseTime = System.currentTimeMillis() - startTime;
            Map<String, Object> responseBody = requireResponseBody(response, "Anthropic response body was empty");
            String generatedText = extractTextContent(responseBody, "Anthropic response content text was missing");
            updateMetrics(true, responseTime);

            int contentLength = generatedText.length();
            log.info(
                "Anthropic API response: responseTimeMs={}, model={}, contentLength={}",
                responseTime,
                responseBody.get("model"),
                contentLength
            );
            log.debug("Anthropic API response contentSnippet={}", snippet(generatedText, 500));

            log.debug("Anthropic content generation completed in {}ms", responseTime);
            
            return AIGenerationResponse.builder()
                .content(generatedText)
                .model(responseBody.get("model") instanceof String model ? model : String.valueOf(requestBody.get("model")))
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
            
            throw wrapException("Anthropic content generation failed", e);
        }
    }

    private AIGenerationResponse generateContentWithDocumentUrls(AIGenerationRequest request, long startTime) {
        List<AIGenerationInputPart> fileParts = TransientInputSupport.fileUrlInputParts(request);
        for (AIGenerationInputPart part : fileParts) {
            try {
                TransientInputSupport.validateFileUrlInput(part);
            } catch (IllegalArgumentException ex) {
                return unsupportedTransientResponse(request, startTime, ex.getMessage());
            }
            if (!isAnthropicDocumentUrlSupported(part)) {
                return unsupportedTransientResponse(
                    request,
                    startTime,
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
        requestBody.put("model", firstNonBlank(request.getModel(), config.getDefaultModel(), DEFAULT_MODEL)
            .orElse(DEFAULT_MODEL));
        requestBody.put("max_tokens", request.getMaxTokens() != null ? request.getMaxTokens() : config.getMaxTokens());
        requestBody.put("temperature", request.getTemperature() != null ? request.getTemperature() : config.getTemperature());
        if (hasText(request.getSystemPrompt())) {
            requestBody.put("system", request.getSystemPrompt());
        }

        List<Map<String, Object>> messages = new ArrayList<>();
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

        List<Map<String, Object>> contentBlocks = new ArrayList<>();
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

        log.info(
            "Anthropic document URL request: url={}, model={}, maxTokens={}, fileUrlInputs={}, promptLength={}",
            url,
            requestBody.get("model"),
            requestBody.get("max_tokens"),
            fileParts.size(),
            request.getPrompt() != null ? request.getPrompt().length() : 0
        );
        log.debug("Anthropic transientInputs={}", TransientInputSupport.redactedDescriptors(fileParts));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<Map> response = exchangeWithRetry(url, HttpMethod.POST, entity, Map.class, "messages.document-url");

        long responseTime = System.currentTimeMillis() - startTime;
        Map<String, Object> responseBody = requireResponseBody(response, "Anthropic document URL response body was empty");
        String generatedText = extractTextContent(responseBody, "Anthropic document URL response content text was missing");
        updateMetrics(true, responseTime);

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

    private AIGenerationResponse unsupportedTransientResponse(AIGenerationRequest request, long startTime, String message) {
        long responseTime = System.currentTimeMillis() - startTime;
        updateMetrics(false, responseTime);
        return TransientInputSupport.unsupportedFileUrlResponse(request, getProviderName(), message);
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
        throw new AIServiceException("Anthropic " + operation + " call failed after retries");
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

        String message = "Anthropic does not provide embedding services directly. " +
            "Configure a dedicated embedding provider such as onnx, openai, cohere, or gemini.";
        long responseTime = System.currentTimeMillis() - startTime;
        updateMetrics(false, responseTime);
        lastError.set(LocalDateTime.now());
        lastErrorMessage.set(message);
        log.warn("Anthropic embedding generation is unsupported: {}", message);
        throw new AIServiceException(message);
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

        Map<String, Object> tokenUsage = asStringKeyMap(responseBody.get("usage"));
        Optional<Number> inputTokens = numberValue(tokenUsage.get("input_tokens"));
        Optional<Number> outputTokens = numberValue(tokenUsage.get("output_tokens"));
        inputTokens.ifPresent(value -> usage.put("prompt_tokens", value));
        outputTokens.ifPresent(value -> usage.put("completion_tokens", value));
        if (inputTokens.isPresent() && outputTokens.isPresent()) {
            usage.put("total_tokens", inputTokens.get().intValue() + outputTokens.get().intValue());
        }
        
        return usage;
    }

    private Map<String, Object> requireResponseBody(ResponseEntity<Map> response, String message) {
        if (response == null || response.getBody() == null) {
            throw new AIServiceException(message);
        }
        return copyStringKeyMap(response.getBody(), message);
    }

    private String extractTextContent(Map<String, Object> responseBody, String message) {
        Object value = responseBody.get("content");
        if (!(value instanceof List<?> content) || content.isEmpty()) {
            throw new AIServiceException(message);
        }
        Object first = content.get(0);
        if (!(first instanceof Map<?, ?> firstBlock)) {
            throw new AIServiceException(message);
        }
        Object text = firstBlock.get("text");
        if (!(text instanceof String contentText)) {
            throw new AIServiceException(message);
        }
        return contentText;
    }

    private Map<String, Object> asStringKeyMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return copyStringKeyMap(map, "Anthropic response map");
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

    private Optional<Number> numberValue(Object value) {
        return value instanceof Number number ? Optional.of(number) : Optional.empty();
    }

    private Optional<String> firstNonBlank(String... values) {
        if (values == null) {
            return Optional.empty();
        }
        for (String value : values) {
            if (hasText(value)) {
                return Optional.of(value.trim());
            }
        }
        return Optional.empty();
    }

    private String snippet(String value, int maxLength) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.substring(0, Math.min(maxLength, value.length()));
    }

    private AIServiceException wrapException(String message, Exception ex) {
        return ex instanceof AIServiceException serviceException
            ? serviceException
            : new AIServiceException(message + ": " + ex.getMessage(), ex);
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
