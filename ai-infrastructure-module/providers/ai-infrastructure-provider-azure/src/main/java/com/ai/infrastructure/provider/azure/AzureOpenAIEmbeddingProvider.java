package com.ai.infrastructure.provider.azure;

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
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import jakarta.annotation.PostConstruct;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Azure-specific embedding provider that calls Azure OpenAI deployments for embeddings.
 */
@Slf4j
@RequiredArgsConstructor
public class AzureOpenAIEmbeddingProvider implements EmbeddingProvider {

    private static final String HEADER_API_KEY = "api-key";

    private final AIProviderConfig config;
    private final HttpClient httpClient;
    private boolean available = false;
    private int embeddingDimension = 1536;
    private static final int MAX_RETRY_ATTEMPTS = 3;

    @PostConstruct
    public void initialize() {
        AIProviderConfig.AzureConfig azure = config.getAzure();
        if (!azure.isEnabled()) {
            log.info("Azure embeddings disabled via configuration");
            available = false;
            return;
        }

        String apiKey = embeddingApiKey(azure);
        String endpoint = embeddingEndpoint(azure);
        if (!hasText(apiKey) || !hasText(endpoint)) {
            log.warn("Azure embedding provider incomplete configuration. Required: api-key, endpoint");
            available = false;
            return;
        }

        // For Azure AI Services (Foundry), embedding deployment name is not required in URL
        if (!endpoint.contains("/models") && !endpoint.contains("services.ai.azure.com")) {
            // Azure OpenAI format requires deployment name
            if (!hasText(embeddingDeploymentName(azure))) {
                log.warn("Azure OpenAI embedding provider requires embedding deployment name");
                available = false;
                return;
            }
        }

        // Mark configured as available without performing external network calls by default.
        available = true;

        if (!azure.isValidateOnStartup()) {
            log.info("Azure embedding startup validation disabled (validate-on-startup=false). Skipping probe call.");
            return;
        }

        try {
            log.info("Validating Azure embedding deployment '{}'", embeddingDeploymentName(azure));
            AIEmbeddingRequest probe = AIEmbeddingRequest.builder().text("ping").build();
            AIEmbeddingResponse response = generateEmbedding(probe);
            embeddingDimension = response.getDimensions();
            available = true;
            log.info("Azure embedding provider ready (dimension={})", embeddingDimension);
        } catch (Exception ex) {
            log.warn("Azure embedding provider validation failed: {}", ex.getMessage());
            available = false;
        }
    }

    @Override
    public String getProviderName() {
        return "azure";
    }

    @Override
    public boolean isAvailable() {
        return available && httpClient != null;
    }

    @Override
    public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
        AIProviderConfig.AzureConfig azure = config.getAzure();
        ensureConfigured(azure, available);

        try {
            String url = buildEmbeddingsUrl(
                embeddingEndpoint(azure),
                embeddingDeploymentName(azure),
                embeddingApiVersion(azure)
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HEADER_API_KEY, embeddingApiKey(azure));

            Map<String, Object> body = new HashMap<>();
            body.put("input", List.of(request.getText()));

            if (log.isInfoEnabled()) {
                log.info("=== AZURE OPENAI EMBEDDING API REQUEST ===");
                log.info(
                    "Azure OpenAI embedding request: url={}, inputCount={}, textLength={}",
                    url,
                    1,
                    request.getText() != null ? request.getText().length() : 0
                );
                String text = request.getText();
                int len = text != null ? text.length() : 0;
                String snippet = text == null ? "" : text.substring(0, Math.min(300, len));
                log.info("Azure OpenAI embedding request textSnippet={}", snippet);
                log.info("=== END AZURE OPENAI EMBEDDING API REQUEST ===");
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            long start = System.currentTimeMillis();
            ResponseEntity<Map> response = exchangeWithRetry(url, HttpMethod.POST, entity, Map.class, "embeddings");
            long elapsed = System.currentTimeMillis() - start;

            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null) {
                throw new AIServiceException("Azure embedding service returned empty response");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) responseBody.get("data");
            if (data == null || data.isEmpty()) {
                throw new AIServiceException("Azure embedding response missing data");
            }

            @SuppressWarnings("unchecked")
            List<Double> embedding = (List<Double>) data.get(0).get("embedding");
            if (embedding == null) {
                throw new AIServiceException("Azure embedding vector missing");
            }

            if (log.isInfoEnabled()) {
                log.info("=== AZURE OPENAI EMBEDDING API RESPONSE ===");
                log.info(
                    "Azure OpenAI embedding response: responseTimeMs={}, dimensions={}",
                    elapsed,
                    embedding.size()
                );
                log.info("=== END AZURE OPENAI EMBEDDING API RESPONSE ===");
            }

            embeddingDimension = embedding.size();

            return AIEmbeddingResponse.builder()
                .embedding(embedding)
                .model(embeddingDeploymentName(azure))
                .dimensions(embeddingDimension)
                .processingTimeMs(elapsed)
                .requestId(UUID.randomUUID().toString())
                .build();
        } catch (RestClientException ex) {
            log.error("Azure embedding request failed", ex);
            throw wrapException("Azure embedding request failed", ex);
        }
    }

    @Override
    public List<AIEmbeddingResponse> generateEmbeddings(List<String> texts) {
        AIProviderConfig.AzureConfig azure = config.getAzure();
        ensureConfigured(azure, available);

        try {
            String url = buildEmbeddingsUrl(
                embeddingEndpoint(azure),
                embeddingDeploymentName(azure),
                embeddingApiVersion(azure)
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HEADER_API_KEY, embeddingApiKey(azure));

            Map<String, Object> body = new HashMap<>();
            body.put("input", texts);

            if (log.isInfoEnabled()) {
                log.info("=== AZURE OPENAI EMBEDDING API REQUEST ===");
                log.info(
                    "Azure OpenAI embedding batch request: url={}, inputCount={}",
                    url,
                    texts != null ? texts.size() : 0
                );
                log.info("=== END AZURE OPENAI EMBEDDING API REQUEST ===");
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            long start = System.currentTimeMillis();
            ResponseEntity<Map> response = exchangeWithRetry(url, HttpMethod.POST, entity, Map.class, "embeddings(batch)");
            long elapsed = System.currentTimeMillis() - start;

            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null) {
                throw new AIServiceException("Azure embedding service returned empty response");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) responseBody.get("data");
            if (data == null || data.isEmpty()) {
                throw new AIServiceException("Azure embedding response missing data");
            }

            if (log.isInfoEnabled()) {
                log.info("=== AZURE OPENAI EMBEDDING API RESPONSE ===");
                log.info(
                    "Azure OpenAI embedding batch response: responseTimeMs={}, embeddings={}",
                    elapsed,
                    data.size()
                );
                log.info("=== END AZURE OPENAI EMBEDDING API RESPONSE ===");
            }

            return data.stream()
                .map(item -> {
                    @SuppressWarnings("unchecked")
                    List<Double> values = (List<Double>) item.get("embedding");
                    int dimension = values != null ? values.size() : 0;
                    embeddingDimension = dimension;
                    return AIEmbeddingResponse.builder()
                        .embedding(values)
                        .model(embeddingDeploymentName(azure))
                        .dimensions(dimension)
                        .processingTimeMs(elapsed)
                        .requestId(UUID.randomUUID().toString())
                        .build();
                })
                .toList();
        } catch (RestClientException ex) {
            log.error("Azure embedding batch request failed", ex);
            throw wrapException("Azure embedding batch request failed", ex);
        }
    }

    @Override
    public int getEmbeddingDimension() {
        return embeddingDimension;
    }

    @Override
    public Map<String, Object> getStatus() {
        AIProviderConfig.AzureConfig azure = config.getAzure();
        Map<String, Object> status = new HashMap<>();
        status.put("provider", "azure");
        status.put("available", isAvailable());
        status.put("endpoint", embeddingEndpoint(azure));
        status.put("deployment", embeddingDeploymentName(azure));
        status.put("embeddingDimension", embeddingDimension);
        status.put("apiVersion", embeddingApiVersion(azure));

        if (!isAvailable()) {
            status.put("status", "unavailable");
            status.put("message", "Azure embedding provider not ready - verify configuration and credentials");
        } else {
            status.put("status", "ready");
        }
        return status;
    }

    private void ensureConfigured(AIProviderConfig.AzureConfig azure, boolean requireAvailability) {
        if (azure == null || !azure.isEnabled() || !hasText(embeddingApiKey(azure))
            || !hasText(embeddingEndpoint(azure)) || !hasText(embeddingDeploymentName(azure))) {
            throw new AIServiceException("Azure embedding provider configuration is incomplete");
        }
        if (requireAvailability && !available) {
            throw new AIServiceException("Azure embedding provider is not currently available");
        }
    }

    private String buildEmbeddingsUrl(String endpoint, String deployment, String apiVersion) {
        String normalized = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        String defaultApiVersion = apiVersion != null ? apiVersion : "2024-02-15-preview";
        
        // Check if endpoint already contains /models/embeddings (Azure AI Services/Foundry format)
        if (normalized.contains("/models/embeddings")) {
            // Azure AI Services (Foundry) format - endpoint already includes the path
            if (normalized.contains("?")) {
                return normalized; // Already has query params
            }
            return String.format("%s?api-version=%s", normalized, defaultApiVersion);
        }
        
        // Check if endpoint contains /models (Azure AI Services/Foundry base format)
        if (normalized.contains("/models")) {
            // Azure AI Services (Foundry) format - add embeddings
            return String.format("%s/embeddings?api-version=%s", normalized, defaultApiVersion);
        }
        
        // Azure OpenAI format - traditional deployment-based
        if (deployment == null || deployment.isEmpty()) {
            throw new AIServiceException("Azure OpenAI embedding deployment name is required");
        }
        return String.format("%s/openai/deployments/%s/embeddings?api-version=%s", normalized, deployment, defaultApiVersion);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String embeddingApiKey(AIProviderConfig.AzureConfig azure) {
        return hasText(azure.getEmbeddingApiKey()) ? azure.getEmbeddingApiKey() : azure.getApiKey();
    }

    private String embeddingEndpoint(AIProviderConfig.AzureConfig azure) {
        return hasText(azure.getEmbeddingEndpoint()) ? azure.getEmbeddingEndpoint() : azure.getEndpoint();
    }

    private String embeddingDeploymentName(AIProviderConfig.AzureConfig azure) {
        return hasText(azure.getEmbeddingDeploymentName()) ? azure.getEmbeddingDeploymentName() : azure.getDeploymentName();
    }

    private String embeddingApiVersion(AIProviderConfig.AzureConfig azure) {
        return hasText(azure.getEmbeddingApiVersion()) ? azure.getEmbeddingApiVersion() : azure.getApiVersion();
    }

    private AIServiceException wrapException(String message, Exception ex) {
        return ex instanceof AIServiceException serviceException
            ? serviceException
            : new AIServiceException(message + ": " + ex.getMessage(), ex);
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
                        "Azure embedding {} call failed with HTTP {} (attempt {}/{}). Retrying after {}ms.",
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
                        "Azure embedding {} call failed due to network/timeout (attempt {}/{}). Retrying after {}ms. Cause: {}",
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
            } catch (RestClientException ex) {
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    log.warn(
                        "Azure embedding {} call failed (attempt {}/{}). Retrying after {}ms. Cause: {}",
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
        throw new AIServiceException("Azure embedding " + operation + " call failed after retries");
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
}
