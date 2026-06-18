package ai.fabric.provider.gemini;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.embedding.EmbeddingProvider;
import ai.fabric.exception.AIServiceException;
import ai.fabric.http.HttpClient;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
    private static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-004";
    private static final int MAX_RETRY_ATTEMPTS = 3;
    
    @PostConstruct
    public void initialize() {
        try {
            log.info("Initializing Gemini Embedding Provider");
            
            AIProviderConfig.GeminiConfig gemini = aiProviderConfig.getGemini();

            if (!gemini.isEnabled()) {
                log.info("Gemini embeddings disabled via configuration");
                available = false;
                return;
            }
            
            if (!hasText(resolveApiKey(gemini))) {
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
                    .model(resolveModel(gemini, null))
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
        AIProviderConfig.GeminiConfig gemini = aiProviderConfig.getGemini();
        String model = resolveModel(gemini, request.getModel());
        return generateEmbeddingsInternal(List.of(requireText(request.getText())), model, false).get(0);
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
        AIProviderConfig.GeminiConfig gemini = aiProviderConfig.getGemini();
        List<String> checkedTexts = requireTexts(texts);
        return generateEmbeddingsInternal(checkedTexts, resolveModel(gemini, null), checkedTexts.size() > 1);
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

    private List<AIEmbeddingResponse> generateEmbeddingsInternal(List<String> texts, String model, boolean batch) {
        if (!isAvailable()) {
            throw new AIServiceException("Gemini Embedding Provider is not available");
        }

        try {
            AIProviderConfig.GeminiConfig gemini = aiProviderConfig.getGemini();
            String apiKey = resolveApiKey(gemini);
            if (!hasText(apiKey)) {
                throw new AIServiceException("Gemini embedding API key is required");
            }

            String operation = batch ? "batchEmbedContents" : "embedContent";
            String url = normalizeBaseUrl(resolveBaseUrl(gemini))
                + "/models/" + modelPathSegment(model) + ":" + operation + "?key=" + apiKey;
            String safeUrl = url.replaceAll("([?&]key=)[^&]+", "$1***");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> requestBody = batch
                ? createBatchRequestBody(texts, model)
                : createSingleRequestBody(texts.get(0), model);

            log.info(
                "Gemini embedding request: url={}, model={}, inputCount={}",
                safeUrl,
                model,
                texts.size()
            );
            log.debug("Gemini embedding firstTextSnippet={}", snippet(texts.get(0), 300));

            long startTime = System.currentTimeMillis();
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<Map> response = exchangeWithRetry(url, HttpMethod.POST, entity, Map.class, operation);
            long processingTime = System.currentTimeMillis() - startTime;

            Map<String, Object> responseBody = requireResponseBody(response, "Gemini embedding service returned empty response");
            List<List<Double>> embeddings = batch
                ? requireBatchEmbeddings(responseBody, texts.size())
                : List.of(requireEmbeddingValues(responseBody, "Gemini embedding response missing embedding"));

            log.info(
                "Gemini embedding response: responseTimeMs={}, model={}, embeddings={}, dimensions={}",
                processingTime,
                model,
                embeddings.size(),
                embeddings.get(0).size()
            );

            return embeddings.stream()
                .map(embedding -> {
                    embeddingDimension = embedding.size();
                    return AIEmbeddingResponse.builder()
                        .embedding(embedding)
                        .model(model)
                        .dimensions(embedding.size())
                        .processingTimeMs(processingTime)
                        .requestId(UUID.randomUUID().toString())
                        .build();
                })
                .toList();
        } catch (Exception e) {
            log.error("Error generating Gemini embeddings", e);
            throw e instanceof AIServiceException serviceException
                ? serviceException
                : new AIServiceException("Failed to generate Gemini embedding", e);
        }
    }

    private Map<String, Object> createSingleRequestBody(String text, String model) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelResourceName(model));
        requestBody.put("content", contentForText(text));
        return requestBody;
    }

    private Map<String, Object> createBatchRequestBody(List<String> texts, String model) {
        List<Map<String, Object>> requests = new ArrayList<>();
        for (String text : texts) {
            requests.add(Map.of(
                "model", modelResourceName(model),
                "content", contentForText(text)
            ));
        }
        return Map.of("requests", requests);
    }

    private Map<String, Object> contentForText(String text) {
        return Map.of("parts", List.of(Map.of("text", text)));
    }

    private String resolveModel(AIProviderConfig.GeminiConfig gemini, String requestModel) {
        if (hasText(requestModel)) {
            return requestModel.trim();
        }
        if (hasText(gemini.getEmbeddingModel())) {
            return gemini.getEmbeddingModel().trim();
        }
        return DEFAULT_EMBEDDING_MODEL;
    }

    private String resolveBaseUrl(AIProviderConfig.GeminiConfig gemini) {
        if (hasText(aiProviderConfig.getEmbeddingBaseUrl())) {
            return aiProviderConfig.getEmbeddingBaseUrl();
        }
        if (hasText(gemini.getBaseUrl())) {
            return gemini.getBaseUrl();
        }
        return GEMINI_BASE_URL;
    }

    private String resolveApiKey(AIProviderConfig.GeminiConfig gemini) {
        if (hasText(aiProviderConfig.getEmbeddingApiKey())) {
            return aiProviderConfig.getEmbeddingApiKey();
        }
        return gemini.getApiKey();
    }

    private String modelResourceName(String model) {
        return model.startsWith("models/") ? model : "models/" + model;
    }

    private String modelPathSegment(String model) {
        return model.startsWith("models/") ? model.substring("models/".length()) : model;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (!hasText(baseUrl)) {
            return GEMINI_BASE_URL;
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String requireText(String text) {
        if (!hasText(text)) {
            throw new AIServiceException("Gemini embedding text is required");
        }
        return text;
    }

    private List<String> requireTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new AIServiceException("Gemini embedding texts are required");
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

    private List<Double> requireEmbeddingValues(Map<String, Object> responseBody, String message) {
        Map<String, Object> embeddingData = requireMap(responseBody.get("embedding"), message);
        return requireDoubleList(embeddingData.get("values"), message + " values were missing");
    }

    private List<List<Double>> requireBatchEmbeddings(Map<String, Object> responseBody, int expectedCount) {
        Object value = responseBody.get("embeddings");
        if (!(value instanceof List<?> items) || items.isEmpty()) {
            throw new AIServiceException("Gemini batch embedding response missing embeddings");
        }
        if (items.size() != expectedCount) {
            throw new AIServiceException("Gemini batch embedding response count did not match request count");
        }
        List<List<Double>> embeddings = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> embeddingData = requireMap(item, "Gemini batch embedding vector missing");
            embeddings.add(requireDoubleList(
                embeddingData.get("values"),
                "Gemini batch embedding vector values were missing"
            ));
        }
        return List.copyOf(embeddings);
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

    private Map<String, Object> requireMap(Object value, String message) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new AIServiceException(message);
        }
        return copyStringKeyMap(map, message);
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
