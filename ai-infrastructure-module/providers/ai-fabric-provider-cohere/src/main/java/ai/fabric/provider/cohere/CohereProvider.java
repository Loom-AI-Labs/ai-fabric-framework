package ai.fabric.provider.cohere;

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
import java.util.Optional;
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
    private static final String DEFAULT_CHAT_MODEL = "command-r7b-12-2024";
    private static final String DEFAULT_EMBEDDING_MODEL = "embed-english-v3.0";
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
            String prompt = request.getPrompt();
            log.debug("Generating content with Cohere: model={}, prompt={}", request.getModel(), snippet(prompt, 100));
            
            ProviderRequestOverrideSupport.LlmConnectionOverride connectionOverride =
                ProviderRequestOverrideSupport.read(request.getParameters());
            String baseUrl = hasText(connectionOverride.baseUrl()) ? connectionOverride.baseUrl() : config.getBaseUrl();
            String apiKey = hasText(connectionOverride.apiKey()) ? connectionOverride.apiKey() : config.getApiKey();
            String url = normalizeBaseUrl(baseUrl != null ? baseUrl : COHERE_BASE_URL) + "/chat";
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", firstNonBlank(request.getModel(), config.getDefaultModel(), DEFAULT_CHAT_MODEL)
                .orElse(DEFAULT_CHAT_MODEL));
            
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

            requestBody.put("message", prompt);
            if (!transientDocuments.isEmpty()) {
                requestBody.put("documents", transientDocuments);
                requestBody.put("prompt_truncation", "AUTO_PRESERVE_ORDER");
            }
            
            requestBody.put("max_tokens", request.getMaxTokens() != null ? request.getMaxTokens() : config.getMaxTokens());
            requestBody.put("temperature", request.getTemperature() != null ? request.getTemperature() : config.getTemperature());

            log.info(
                "Cohere API request: url={}, model={}, temperature={}, maxTokens={}, hasPreamble={}, promptLength={}, transientDocuments={}",
                url,
                requestBody.get("model"),
                requestBody.get("temperature"),
                requestBody.get("max_tokens"),
                requestBody.containsKey("preamble"),
                prompt != null ? prompt.length() : 0,
                transientDocuments.size()
            );
            if (TransientInputSupport.hasFileUrlInputs(request)) {
                log.debug(
                    "Cohere API transientInputs={}",
                    TransientInputSupport.redactedDescriptors(TransientInputSupport.fileUrlInputParts(request))
                );
            }
            log.debug("Cohere API request promptSnippet={}", snippet(prompt, 500));
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<Map> response = exchangeWithRetry(url, HttpMethod.POST, entity, Map.class, "chat");
            
            long responseTime = System.currentTimeMillis() - startTime;
            Map<String, Object> responseBody = requireResponseBody(response, "Cohere response body was empty");
            String generatedText = requireString(responseBody, "text", "Cohere response text was missing");
            updateMetrics(true, responseTime);

            int contentLength = generatedText.length();
            log.info(
                "Cohere API response: responseTimeMs={}, model={}, contentLength={}",
                responseTime,
                responseBody.get("model"),
                contentLength
            );
            log.debug("Cohere API response contentSnippet={}", snippet(generatedText, 500));
            
            log.debug("Cohere content generation completed in {}ms", responseTime);
            
            // Extract model from response or use request model
            String model = responseBody.get("model") instanceof String responseModel
                ? responseModel
                : firstNonBlank(request.getModel(), config.getDefaultModel(), DEFAULT_CHAT_MODEL).orElse(DEFAULT_CHAT_MODEL);
            
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
            
            throw wrapException("Cohere content generation failed", e);
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
            String text = request.getText();
            log.debug("Generating embedding with Cohere: model={}, text={}", request.getModel(), snippet(text, 100));
            if (!hasText(text)) {
                throw new AIServiceException("Cohere embedding text is required");
            }
            
            String url = normalizeBaseUrl(config.getBaseUrl()) + "/embed";
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + config.getApiKey());
            
            Map<String, Object> requestBody = new HashMap<>();
            String model = firstNonBlank(request.getModel(), config.getDefaultEmbeddingModel(), DEFAULT_EMBEDDING_MODEL)
                .orElse(DEFAULT_EMBEDDING_MODEL);
            requestBody.put("model", model);
            requestBody.put("texts", List.of(text));
            requestBody.put("input_type", "search_document");

            log.info(
                "Cohere embedding request: url={}, model={}, inputType={}, textLength={}",
                url,
                requestBody.get("model"),
                requestBody.get("input_type"),
                text != null ? text.length() : 0
            );
            log.debug("Cohere embedding request textSnippet={}", snippet(text, 300));
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<Map> response = exchangeWithRetry(url, HttpMethod.POST, entity, Map.class, "embed");
            
            long responseTime = System.currentTimeMillis() - startTime;
            Map<String, Object> responseBody = requireResponseBody(response, "Cohere embedding response body was empty");
            List<Double> embedding = requireFirstEmbedding(responseBody, "Cohere embedding response missing embeddings");
            updateMetrics(true, responseTime);

            log.info(
                "Cohere embedding response: responseTimeMs={}, model={}, dimensions={}",
                responseTime,
                responseBody.get("model"),
                embedding.size()
            );
            
            log.debug("Cohere embedding generation completed in {}ms", responseTime);
            
            return AIEmbeddingResponse.builder()
                .embedding(embedding)
                .model(responseBody.get("model") instanceof String responseModel ? responseModel : model)
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
            
            throw wrapException("Cohere embedding generation failed", e);
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
        throw new AIServiceException("Cohere " + operation + " call failed after retries");
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
        Map<String, Object> meta = asStringKeyMap(responseBody.get("meta"));
        Map<String, Object> tokens = asStringKeyMap(meta.get("tokens"));
        if (!tokens.isEmpty()) {
            copyUsageNumbers(tokens, usage);
        }

        Map<String, Object> billedUnits = asStringKeyMap(meta.get("billed_units"));
        if (usage.isEmpty() && !billedUnits.isEmpty()) {
            copyUsageNumbers(billedUnits, usage);
        }
        
        return usage;
    }

    private void copyUsageNumbers(Map<String, Object> source, Map<String, Object> target) {
        Optional<Number> inputTokens = numberValue(source.get("input_tokens"));
        Optional<Number> outputTokens = numberValue(source.get("output_tokens"));
        inputTokens.ifPresent(value -> target.put("prompt_tokens", value));
        outputTokens.ifPresent(value -> target.put("completion_tokens", value));
        if (inputTokens.isPresent() && outputTokens.isPresent()) {
            target.put("total_tokens", inputTokens.get().intValue() + outputTokens.get().intValue());
        }
    }

    private Optional<Number> numberValue(Object value) {
        return value instanceof Number number ? Optional.of(number) : Optional.empty();
    }

    private Map<String, Object> requireResponseBody(ResponseEntity<Map> response, String message) {
        if (response == null || response.getBody() == null) {
            throw new AIServiceException(message);
        }
        return copyStringKeyMap(response.getBody(), message);
    }

    private String requireString(Map<String, Object> source, String key, String message) {
        Object value = source.get(key);
        if (!(value instanceof String text)) {
            throw new AIServiceException(message);
        }
        return text;
    }

    private List<Double> requireFirstEmbedding(Map<String, Object> responseBody, String message) {
        Object value = responseBody.get("embeddings");
        if (!(value instanceof List<?> embeddings) || embeddings.isEmpty()) {
            throw new AIServiceException(message);
        }
        return requireDoubleList(embeddings.get(0), message);
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

    private Map<String, Object> asStringKeyMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return copyStringKeyMap(map, "Cohere response map");
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
