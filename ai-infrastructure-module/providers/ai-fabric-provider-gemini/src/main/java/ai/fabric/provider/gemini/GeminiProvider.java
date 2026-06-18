package ai.fabric.provider.gemini;

import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.AIGenerationInputPart;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AIChatMessage;
import ai.fabric.dto.AIChatRole;
import ai.fabric.exception.AIServiceException;
import ai.fabric.provider.AIProvider;
import ai.fabric.provider.ProviderConfig;
import ai.fabric.provider.ProviderRequestOverrideSupport;
import ai.fabric.provider.ProviderStatus;
import ai.fabric.provider.TransientInputSupport;
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
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Google Gemini Provider Implementation
 * 
 * Real implementation of AI provider using Google Gemini API.
 * Provides content generation and embedding services.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class GeminiProvider implements AIProvider {
    
    private final ProviderConfig config;
    private final HttpClient httpClient;
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicReference<LocalDateTime> lastSuccess = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> lastError = new AtomicReference<>();
    private final AtomicReference<String> lastErrorMessage = new AtomicReference<>();
    private final AtomicReference<Double> averageResponseTime = new AtomicReference<>(0.0);
    
    private static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private static final String DEFAULT_MODEL = "gemini-2.5-flash";
    private static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-004";
    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final int MAX_TRANSIENT_INLINE_BYTES = 20 * 1024 * 1024;
    
    @Override
    public String getProviderName() {
        return "gemini";
    }
    
    @Override
    public boolean isAvailable() {
        try {
            return config.isValid() && config.isEnabled() && 
                   config.getApiKey() != null && !config.getApiKey().trim().isEmpty();
        } catch (Exception e) {
            log.warn("Gemini provider not available: {}", e.getMessage());
            return false;
        }
    }
    
    @Override
    public AIGenerationResponse generateContent(AIGenerationRequest request) {
        long startTime = System.currentTimeMillis();
        totalRequests.incrementAndGet();
        
        try {
            log.debug(
                "Generating content with Gemini: model={}, generationType={}, prompt={}",
                request.getModel(),
                request.getGenerationType(),
                snippet(request.getPrompt(), 100)
            );

            String model = firstNonBlank(request.getModel(), config.getDefaultModel(), DEFAULT_MODEL);
            
            ProviderRequestOverrideSupport.LlmConnectionOverride connectionOverride =
                ProviderRequestOverrideSupport.read(request.getParameters());
            String baseUrl = hasText(connectionOverride.baseUrl()) ? connectionOverride.baseUrl() : config.getBaseUrl();
            String apiKey = hasText(connectionOverride.apiKey()) ? connectionOverride.apiKey() : config.getApiKey();
            String url = normalizeBaseUrl(baseUrl != null ? baseUrl : GEMINI_BASE_URL)
                + "/models/" + modelPathSegment(model) + ":generateContent?key=" + apiKey;
            String safeUrl = url.replaceAll("([?&]key=)[^&]+", "$1***");
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            Map<String, Object> requestBody = new HashMap<>();
            
            // Build contents array - Gemini uses "contents" instead of "messages"
            List<Map<String, Object>> contents = new ArrayList<>();

            Map<String, Object> requestParams = request.getParameters() != null ? request.getParameters() : Map.of();
            boolean jsonMimeTypeRequested = isJsonMimeTypeRequested(requestParams);

            // Add system instruction if present (Gemini uses systemInstruction in generationConfig)
            String systemPrompt = request.getSystemPrompt() != null ? request.getSystemPrompt() : "";

            boolean jsonOnlyTask = jsonMimeTypeRequested
                || hasOpenAIStyleJsonHint(requestParams)
                || isJsonOnlySystemPrompt(systemPrompt)
                || isJsonTaskGenerationType(request.getGenerationType());
            
            // For JSON-sensitive tasks, enhance the system prompt to be very explicit about JSON-only responses.
            // (Additionally, set generationConfig.responseMimeType to application/json when requested.)
            if (jsonOnlyTask || (request.getGenerationType() != null && request.getGenerationType().contains("intent_extraction"))) {
                String jsonInstruction = "CRITICAL JSON REQUIREMENT: You are a JSON-only API endpoint. " +
                    "You MUST respond with ONLY valid JSON. No markdown code blocks (no ```json or ```), " +
                    "no explanations, no text before or after the JSON, no comments, no additional formatting. " +
                    "Just the raw JSON object. If you include any text other than JSON, the response will fail to parse.\n\n";
                
                systemPrompt = jsonInstruction + systemPrompt;
                
                log.info("Enhanced system prompt with JSON-only requirement (length: {})", systemPrompt.length());
            }
            
            // Gemini uses "parts" array with text content
            if (request.getMessages() != null && !request.getMessages().isEmpty()) {
                for (AIChatMessage msg : request.getMessages()) {
                    if (msg == null || msg.getRole() == null || msg.getContent() == null || msg.getContent().isBlank()) {
                        continue;
                    }
                    if (AIChatRole.SYSTEM.equals(msg.getRole())) {
                        continue;
                    }
                    String role = AIChatRole.USER.equals(msg.getRole()) ? "user" : "model";
                    contents.add(Map.of(
                        "role", role,
                        "parts", List.of(Map.of("text", msg.getContent()))
                    ));
                }
            }
            List<AIGenerationInputPart> fileParts = TransientInputSupport.fileUrlInputParts(request);
            for (AIGenerationInputPart part : fileParts) {
                String contentType = TransientInputSupport.normalizeContentType(part.getContentType());
                if (!hasText(contentType) || "application/octet-stream".equals(contentType)) {
                    return unsupportedTransientResponse(
                        request,
                        startTime,
                        getProviderName(),
                        "Gemini file URL inputs require a contentType so the provider can process the file."
                    );
                }
                if (!TransientInputSupport.isGeminiInlineContentType(contentType)) {
                    return unsupportedTransientResponse(
                        request,
                        startTime,
                        getProviderName(),
                        "Gemini transient inline inputs do not support content type: " + contentType
                    );
                }
            }
            List<Map<String, Object>> userParts = new ArrayList<>();
            userParts.add(Map.of("text", request.getPrompt() != null ? request.getPrompt() : ""));
            for (AIGenerationInputPart part : fileParts) {
                TransientInputSupport.FetchedTransientFile fetchedFile;
                try {
                    fetchedFile = TransientInputSupport.fetchTransientFile(httpClient, part, MAX_TRANSIENT_INLINE_BYTES);
                } catch (IllegalArgumentException ex) {
                    return unsupportedTransientResponse(
                        request,
                        startTime,
                        getProviderName(),
                        ex.getMessage()
                    );
                }
                if (!TransientInputSupport.isGeminiInlineContentType(fetchedFile.contentType())) {
                    return unsupportedTransientResponse(
                        request,
                        startTime,
                        getProviderName(),
                        "Gemini transient fetch returned unsupported content type: " + fetchedFile.contentType()
                    );
                }
                userParts.add(Map.of(
                    "inlineData",
                    Map.of(
                        "mimeType", fetchedFile.contentType(),
                        "data", Base64.getEncoder().encodeToString(fetchedFile.bytes())
                    )
                ));
            }
            contents.add(Map.of(
                "role", "user",
                "parts", userParts
            ));
            requestBody.put("contents", contents);
            
            // Add generation config
            Map<String, Object> generationConfig = new HashMap<>();
            if (request.getMaxTokens() != null) {
                generationConfig.put("maxOutputTokens", request.getMaxTokens());
            } else if (config.getMaxTokens() != null) {
                generationConfig.put("maxOutputTokens", config.getMaxTokens());
            }
            if (request.getTemperature() != null) {
                generationConfig.put("temperature", request.getTemperature());
            } else if (config.getTemperature() != null) {
                generationConfig.put("temperature", config.getTemperature());
            }

            // Gemini supports enforcing JSON output via responseMimeType.
            // Only enable this when explicitly requested for Gemini (response_mime_type / responseMimeType).
            // OpenAI's response_format hints are provider-specific and can unintentionally constrain Gemini output.
            if (jsonMimeTypeRequested) {
                generationConfig.put("responseMimeType", "application/json");
            }
            
            // Add system instruction if present
            if (!systemPrompt.trim().isEmpty()) {
                Map<String, Object> systemInstruction = new HashMap<>();
                systemInstruction.put("parts", List.of(Map.of("text", systemPrompt)));
                requestBody.put("systemInstruction", systemInstruction);
            }
            
            if (!generationConfig.isEmpty()) {
                requestBody.put("generationConfig", generationConfig);
            }

            Object temperature = generationConfig.get("temperature");
            Object maxOutputTokens = generationConfig.get("maxOutputTokens");
            log.info(
                "Gemini API request: url={}, model={}, temperature={}, maxOutputTokens={}, hasSystemInstruction={}, promptLength={}, transientInputs={}",
                safeUrl,
                model,
                temperature,
                maxOutputTokens,
                requestBody.containsKey("systemInstruction"),
                request.getPrompt() != null ? request.getPrompt().length() : 0,
                fileParts.size()
            );
            if (!fileParts.isEmpty()) {
                log.debug("Gemini transientInputs={}", TransientInputSupport.redactedDescriptors(fileParts));
            }
            log.debug("Gemini API request promptSnippet={}", snippet(request.getPrompt(), 500));
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<Map> response = exchangeWithRetry(url, HttpMethod.POST, entity, Map.class, "generateContent");
            
            long responseTime = System.currentTimeMillis() - startTime;
            Map<String, Object> responseBody = requireResponseBody(response, "Gemini returned an empty response body");
            List<Map<String, Object>> candidates = requireMapList(responseBody.get("candidates"), "Gemini returned no candidates");
            Map<String, Object> candidate = candidates.get(0);
            String generatedText = extractGeneratedText(candidate);
            updateMetrics(true, responseTime);

            Object finishReason = candidate.get("finishReason");
            int contentLength = generatedText.length();
            log.info(
                "Gemini API response: responseTimeMs={}, model={}, finishReason={}, contentLength={}, candidates={}",
                responseTime,
                model,
                finishReason,
                contentLength,
                candidates.size()
            );
            log.debug("Gemini API response contentSnippet={}", snippet(generatedText, 500));
            
            log.debug("Gemini content generation completed in {}ms", responseTime);
            
            return AIGenerationResponse.builder()
                .content(generatedText)
                .model(model)
                .usage(createUsageFromResponse(responseBody))
                .processingTimeMs(responseTime)
                .requestId(java.util.UUID.randomUUID().toString())
                .metadata(fileParts.isEmpty()
                    ? null
                    : Map.of(
                        "providerRoute", "gemini.generateContent.inlineData",
                        "transientInputs", TransientInputSupport.redactedDescriptors(fileParts)
                    ))
                .build();
                
        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            updateMetrics(false, responseTime);
            
            log.error("Gemini content generation failed", e);
            lastError.set(LocalDateTime.now());
            lastErrorMessage.set(e.getMessage());
            
            throw wrapException("Gemini content generation failed", e);
        }
    }
    
    @Override
    public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
        long startTime = System.currentTimeMillis();
        totalRequests.incrementAndGet();
        
        try {
            String text = requireText(request.getText(), "Gemini embedding text is required");
            log.debug("Generating embedding with Gemini: model={}, text={}", request.getModel(), snippet(text, 100));

            String model = firstNonBlank(request.getModel(), config.getDefaultEmbeddingModel(), DEFAULT_EMBEDDING_MODEL);
            String baseUrl = firstNonBlank(config.getEmbeddingBaseUrl(), config.getBaseUrl(), GEMINI_BASE_URL);
            String apiKey = firstNonBlank(config.getEmbeddingApiKey(), config.getApiKey());
            if (!hasText(apiKey)) {
                throw new AIServiceException("Gemini embedding API key is required");
            }

            String url = normalizeBaseUrl(baseUrl) + "/models/" + modelPathSegment(model) + ":embedContent?key=" + apiKey;
            String safeUrl = url.replaceAll("([?&]key=)[^&]+", "$1***");
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            Map<String, Object> requestBody = new HashMap<>();
            
            // Gemini embedding API structure
            Map<String, Object> content = new HashMap<>();
            Map<String, Object> part = new HashMap<>();
            part.put("text", text);
            content.put("parts", List.of(part));
            requestBody.put("model", "models/" + model);
            requestBody.put("content", content);

            log.info("Gemini embedding request: url={}, model={}, textLength={}", safeUrl, model, text.length());
            log.debug("Gemini embedding request textSnippet={}", snippet(text, 300));
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<Map> response = exchangeWithRetry(url, HttpMethod.POST, entity, Map.class, "embedContent");
            
            long responseTime = System.currentTimeMillis() - startTime;
            Map<String, Object> responseBody = requireResponseBody(response, "Gemini embedding service returned empty response");
            List<Double> embedding = requireEmbeddingValues(responseBody, "Gemini embedding response missing embedding");
            updateMetrics(true, responseTime);

            log.info(
                "Gemini embedding response: responseTimeMs={}, model={}, dimensions={}",
                responseTime,
                model,
                embedding.size()
            );
            
            log.debug("Gemini embedding generation completed in {}ms, dimension: {}", responseTime, embedding.size());
            
            return AIEmbeddingResponse.builder()
                .embedding(embedding)
                .model(model)
                .dimensions(embedding.size())
                .processingTimeMs(responseTime)
                .requestId(java.util.UUID.randomUUID().toString())
                .build();
                
        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            updateMetrics(false, responseTime);
            
            log.error("Gemini embedding generation failed", e);
            lastError.set(LocalDateTime.now());
            lastErrorMessage.set(e.getMessage());
            
            throw wrapException("Gemini embedding generation failed", e);
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
                        "Gemini {} call failed with HTTP {} (attempt {}/{}). Retrying after {}ms.",
                        operation,
                        rawStatus,
                        attempt,
                        MAX_RETRY_ATTEMPTS,
                        backoffMs
                    );
                    sleepWithJitter(backoffMs);
                    backoffMs = Math.min(8000, backoffMs * 2);
                    continue;
                }
                throw ex;
            } catch (ResourceAccessException ex) {
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    log.warn(
                        "Gemini {} call failed due to network/timeout (attempt {}/{}). Retrying after {}ms. Cause: {}",
                        operation,
                        attempt,
                        MAX_RETRY_ATTEMPTS,
                        backoffMs,
                        ex.getMessage()
                    );
                    sleepWithJitter(backoffMs);
                    backoffMs = Math.min(8000, backoffMs * 2);
                    continue;
                }
                throw ex;
            }
        }
        throw new AIServiceException("Gemini " + operation + " call failed after retries");
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
    public ProviderStatus getStatus() {
        return ProviderStatus.builder()
            .providerName(getProviderName())
            .available(isAvailable())
            .totalRequests(totalRequests.get())
            .successfulRequests(successfulRequests.get())
            .failedRequests(failedRequests.get())
            .lastSuccess(lastSuccess.get())
            .lastError(lastError.get())
            .lastErrorMessage(lastErrorMessage.get())
            .averageResponseTime(averageResponseTime.get())
            .build();
    }
    
    @Override
    public ProviderConfig getConfig() {
        return config;
    }
    
    private void updateMetrics(boolean success, long responseTime) {
        if (success) {
            successfulRequests.incrementAndGet();
            lastSuccess.set(LocalDateTime.now());
        } else {
            failedRequests.incrementAndGet();
            lastError.set(LocalDateTime.now());
        }
        
        // Update average response time
        long total = totalRequests.get();
        double currentAvg = averageResponseTime.get();
        double newAvg = ((currentAvg * (total - 1)) + responseTime) / total;
        averageResponseTime.set(newAvg);
    }
    
    private boolean isJsonMimeTypeRequested(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return false;
        }
        Object responseMimeType = parameters.get("response_mime_type");
        if (responseMimeType == null) {
            responseMimeType = parameters.get("responseMimeType");
        }
        if (responseMimeType == null) {
            return false;
        }
        String value = String.valueOf(responseMimeType).trim().toLowerCase();
        return value.contains("json");
    }

    private boolean hasOpenAIStyleJsonHint(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return false;
        }
        Object responseFormat = parameters.get("response_format");
        if (responseFormat == null) {
            responseFormat = parameters.get("responseFormat");
        }
        if (responseFormat == null) {
            return false;
        }

        if (responseFormat instanceof Map<?, ?> map) {
            Object type = map.get("type");
            if (type == null) {
                type = map.get("Type");
            }
            return type != null && String.valueOf(type).trim().toLowerCase().contains("json");
        }

        return String.valueOf(responseFormat).trim().toLowerCase().contains("json");
    }

    private boolean isJsonOnlySystemPrompt(String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return false;
        }
        return systemPrompt.toLowerCase().contains("json");
    }

    private boolean isJsonTaskGenerationType(String generationType) {
        if (generationType == null || generationType.isBlank()) {
            return false;
        }
        String value = generationType.toLowerCase();
        return value.contains("planning")
            || value.contains("intent_extraction")
            || value.contains("intent-extraction");
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return GEMINI_BASE_URL;
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private AIGenerationResponse unsupportedTransientResponse(AIGenerationRequest request,
                                                              long startTime,
                                                              String providerName,
                                                              String message) {
        long responseTime = System.currentTimeMillis() - startTime;
        updateMetrics(false, responseTime);
        lastErrorMessage.set(message);
        return TransientInputSupport.unsupportedFileUrlResponse(request, providerName, message);
    }

    private Map<String, Object> requireResponseBody(ResponseEntity<Map> response, String message) {
        if (response == null || response.getBody() == null) {
            throw new AIServiceException(message);
        }
        return copyStringKeyMap(response.getBody(), message);
    }

    private String extractGeneratedText(Map<String, Object> candidate) {
        Map<String, Object> content = requireMap(candidate.get("content"), "Gemini response content was missing");
        List<Map<String, Object>> parts = requireMapList(content.get("parts"), "Gemini response content parts were missing");
        StringBuilder generatedText = new StringBuilder();
        for (Map<String, Object> part : parts) {
            Object text = part.get("text");
            if (text instanceof String textPart) {
                generatedText.append(textPart);
            }
        }
        if (generatedText.isEmpty()) {
            throw new AIServiceException("Gemini response content text was missing");
        }
        return generatedText.toString();
    }

    private Object createUsageFromResponse(Map<String, Object> responseBody) {
        Map<String, Object> usageMetadata = asStringKeyMap(responseBody.get("usageMetadata"));
        if (usageMetadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> usage = new HashMap<>();
        putIntIfNumber(usage, "promptTokens", usageMetadata.get("promptTokenCount"));
        putIntIfNumber(usage, "completionTokens", usageMetadata.get("candidatesTokenCount"));
        putIntIfNumber(usage, "totalTokens", usageMetadata.get("totalTokenCount"));
        return Map.copyOf(usage);
    }

    private void putIntIfNumber(Map<String, Object> target, String key, Object value) {
        if (value instanceof Number number) {
            target.put(key, number.intValue());
        }
    }

    private List<Double> requireEmbeddingValues(Map<String, Object> responseBody, String message) {
        Map<String, Object> embeddingData = requireMap(responseBody.get("embedding"), message);
        return requireDoubleList(embeddingData.get("values"), message + " values were missing");
    }

    private List<Double> requireDoubleList(Object value, String message) {
        if (!(value instanceof List<?> items) || items.isEmpty()) {
            throw new AIServiceException(message);
        }
        List<Double> values = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Number number)) {
                throw new AIServiceException(message + " contained non-numeric value");
            }
            values.add(number.doubleValue());
        }
        return List.copyOf(values);
    }

    private List<Map<String, Object>> requireMapList(Object value, String message) {
        if (!(value instanceof List<?> items) || items.isEmpty()) {
            throw new AIServiceException(message);
        }
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object item : items) {
            maps.add(requireMap(item, message));
        }
        return List.copyOf(maps);
    }

    private Map<String, Object> requireMap(Object value, String message) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new AIServiceException(message);
        }
        return copyStringKeyMap(map, message);
    }

    private Map<String, Object> asStringKeyMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return copyStringKeyMap(map, "Gemini response map");
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

    private String requireText(String text, String message) {
        if (!hasText(text)) {
            throw new AIServiceException(message);
        }
        return text;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String snippet(String value, int maxLength) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.substring(0, Math.min(maxLength, value.length()));
    }

    private String modelPathSegment(String model) {
        return model.startsWith("models/") ? model.substring("models/".length()) : model;
    }

    private AIServiceException wrapException(String message, Exception ex) {
        return ex instanceof AIServiceException serviceException
            ? serviceException
            : new AIServiceException(message + ": " + ex.getMessage(), ex);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
