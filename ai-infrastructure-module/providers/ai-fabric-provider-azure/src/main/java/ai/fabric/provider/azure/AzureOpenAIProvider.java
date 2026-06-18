package ai.fabric.provider.azure;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AIChatMessage;
import ai.fabric.dto.AIChatRole;
import ai.fabric.dto.AIGenerationInputPart;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.exception.AIServiceException;
import ai.fabric.http.HttpClient;
import ai.fabric.provider.AIProvider;
import ai.fabric.provider.ProviderConfig;
import ai.fabric.provider.ProviderRequestOverrideSupport;
import ai.fabric.provider.ProviderStatus;
import ai.fabric.provider.TransientInputSupport;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Azure OpenAI Provider implementation supporting both LLM and embedding APIs.
 */
@Slf4j
public class AzureOpenAIProvider implements AIProvider {

    private static final String HEADER_API_KEY = "api-key";

    private final ProviderConfig config;
    private final AIProviderConfig.AzureConfig azureConfig;
    private final HttpClient httpClient;

    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicReference<LocalDateTime> lastSuccess = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> lastError = new AtomicReference<>();
    private final AtomicReference<String> lastErrorMessage = new AtomicReference<>();
    private final AtomicReference<Double> averageResponseTime = new AtomicReference<>(0.0);
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int MAX_TRANSIENT_PDF_BYTES = 20 * 1024 * 1024;

    public AzureOpenAIProvider(ProviderConfig config,
                               AIProviderConfig.AzureConfig azureConfig,
                               HttpClient httpClient) {
        this.config = Objects.requireNonNull(config, "ProviderConfig must not be null");
        this.azureConfig = Objects.requireNonNull(azureConfig, "Azure configuration must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    }

    @Override
    public String getProviderName() {
        return "azure";
    }

    @Override
    public boolean isAvailable() {
        try {
            if (!config.isValid() || !azureConfig.isEnabled() || !hasText(config.getApiKey()) || !hasText(azureConfig.getEndpoint())) {
                return false;
            }
            
            // For Azure AI Services (Foundry) or OpenAI-compatible format, deployment name is not required in URL
            // Check if endpoint contains /models or /openai/v1 (Foundry/OpenAI-compatible format)
            String endpoint = azureConfig.getEndpoint();
            if (endpoint.contains("/models") || endpoint.contains("/openai/v1") || endpoint.contains("services.ai.azure.com")) {
                // Deployment name is still needed for the model field in request body
                return hasText(azureConfig.getDeploymentName()) || hasText(config.getDefaultModel());
            }
            
            // For Azure OpenAI, deployment name is required
            return hasText(azureConfig.getDeploymentName());
        } catch (Exception ex) {
            log.warn("Azure provider validation failed: {}", ex.getMessage());
            return false;
        }
    }

    @Override
    public AIGenerationResponse generateContent(AIGenerationRequest request) {
        ensureAvailability();
        long startTime = System.currentTimeMillis();
        totalRequests.incrementAndGet();

        try {
            if (TransientInputSupport.hasFileUrlInputs(request)) {
                return generateResponsesContent(request, startTime);
            }
            ProviderRequestOverrideSupport.LlmConnectionOverride connectionOverride =
                ProviderRequestOverrideSupport.read(request.getParameters());
            String endpoint = hasText(connectionOverride.baseUrl()) ? connectionOverride.baseUrl() : azureConfig.getEndpoint();
            String apiKey = hasText(connectionOverride.apiKey()) ? connectionOverride.apiKey() : config.getApiKey();
            String deploymentName = hasText(connectionOverride.deploymentName())
                ? connectionOverride.deploymentName()
                : azureConfig.getDeploymentName();
            String apiVersion = hasText(connectionOverride.apiVersion())
                ? connectionOverride.apiVersion()
                : azureConfig.getApiVersion();
            String url = buildChatCompletionsUrl(endpoint, deploymentName, apiVersion);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HEADER_API_KEY, apiKey);

            List<Map<String, String>> messages = new ArrayList<>();
            
            // Handle system prompt and enhance for intent extraction
            String systemPrompt = request.getSystemPrompt();
            if (hasText(systemPrompt)) {
                // For intent extraction, enhance the system prompt to be very explicit about JSON-only responses
                if (request.getGenerationType() != null && request.getGenerationType().equals("intent_extraction")) {
                    String jsonInstruction = "CRITICAL JSON REQUIREMENT: You are a JSON-only API endpoint. " +
                        "You MUST respond with ONLY valid JSON. No markdown code blocks (no ```json or ```), " +
                        "no explanations, no text before or after the JSON, no comments, no additional formatting. " +
                        "Just the raw JSON object. If you include any text other than JSON, the response will fail to parse.\n\n";
                    
                    systemPrompt = jsonInstruction + systemPrompt;
                    
                    log.info("Enhanced system prompt for intent extraction with JSON-only requirement (length: {})", systemPrompt.length());
                    log.debug("Enhanced system prompt preview: {}", systemPrompt.substring(0, Math.min(200, systemPrompt.length())));
                }
                messages.add(Map.of("role", "system", "content", systemPrompt));
            }

            // Add structured history messages (provider-native multi-message prompting)
            if (request.getMessages() != null && !request.getMessages().isEmpty()) {
                for (AIChatMessage msg : request.getMessages()) {
                    if (msg == null || msg.getRole() == null || !hasText(msg.getContent())) {
                        continue;
                    }
                    String role = msg.getRole().getApiValue();
                    if (!hasText(role)) {
                        continue;
                    }
                    // Avoid duplicating system prompt when systemPrompt is already provided.
                    if (AIChatRole.SYSTEM.equals(msg.getRole()) && hasText(systemPrompt)) {
                        continue;
                    }
                    messages.add(Map.of("role", role, "content", msg.getContent()));
                }
            }

            if (hasText(request.getPrompt())) {
                messages.add(Map.of("role", "user", "content", request.getPrompt()));
            }

            Map<String, Object> body = new HashMap<>();
            body.put("messages", messages);
            body.put("temperature", Optional.ofNullable(request.getTemperature()).orElse(config.getTemperature()));
            body.put("max_tokens", Optional.ofNullable(request.getMaxTokens()).orElse(config.getMaxTokens()));

            applyResponseFormat(body, request.getParameters());
            
            // For OpenAI-compatible format (/openai/v1), include model in request body
            String currentEndpoint = endpoint;
            if (currentEndpoint != null && currentEndpoint.contains("/openai/v1")) {
                String deployment = config.getDefaultModel();
                if (deployment == null || deployment.isEmpty()) {
                    deployment = deploymentName;
                }
                if (deployment != null && !deployment.isEmpty()) {
                    body.put("model", deployment);
                }
            }

            String prompt = request.getPrompt();
            int promptLength = prompt != null ? prompt.length() : 0;
            log.info(
                "Azure OpenAI request: url={}, messages={}, temperature={}, maxTokens={}, hasModelField={}, promptLength={}",
                url,
                messages.size(),
                body.get("temperature"),
                body.get("max_tokens"),
                body.containsKey("model"),
                promptLength
            );
            log.debug("Azure OpenAI request promptSnippet={}", snippet(prompt, 500));

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = exchangeWithRetry(url, HttpMethod.POST, entity, Map.class, "chat.completions");

            long responseTime = System.currentTimeMillis() - startTime;
            Map<String, Object> responseBody = requireResponseBody(response, "Azure response body was empty");
            List<Map<String, Object>> choices = requireMapList(
                responseBody,
                "choices",
                "Azure response did not contain choices"
            );
            Map<String, Object> firstChoice = choices.get(0);
            Map<String, Object> message = requireMap(
                firstChoice.get("message"),
                "Azure response choice message was not an object"
            );
            String content = requireString(message, "content", "Azure response message content was missing");
            updateMetrics(true, responseTime);

            Object model = responseBody.get("model");
            Object finishReason = firstChoice.get("finish_reason");
            int contentLength = content.length();
            log.info(
                "Azure OpenAI response: responseTimeMs={}, model={}, finishReason={}, contentLength={}",
                responseTime,
                model,
                finishReason,
                contentLength
            );
            log.debug("Azure OpenAI response contentSnippet={}", snippet(content, 500));

            return AIGenerationResponse.builder()
                .content(content)
                .model(config.getDefaultModel())
                .processingTimeMs(responseTime)
                .requestId(UUID.randomUUID().toString())
                .usage(createUsageFromResponse(responseBody))
                .build();
        } catch (Exception ex) {
            long responseTime = System.currentTimeMillis() - startTime;
            updateMetrics(false, responseTime);
            log.error("Azure content generation failed", ex);
            lastError.set(LocalDateTime.now());
            lastErrorMessage.set(ex.getMessage());
            throw wrapException("Azure content generation failed", ex);
        }
    }

    private AIGenerationResponse generateResponsesContent(AIGenerationRequest request, long startTime) {
        ProviderRequestOverrideSupport.LlmConnectionOverride connectionOverride =
            ProviderRequestOverrideSupport.read(request.getParameters());
        String endpoint = hasText(connectionOverride.baseUrl()) ? connectionOverride.baseUrl() : azureConfig.getEndpoint();
        String apiKey = hasText(connectionOverride.apiKey()) ? connectionOverride.apiKey() : config.getApiKey();
        String deploymentName = hasText(connectionOverride.deploymentName())
            ? connectionOverride.deploymentName()
            : azureConfig.getDeploymentName();
        String apiVersion = hasText(connectionOverride.apiVersion())
            ? connectionOverride.apiVersion()
            : azureConfig.getApiVersion();
        String url = buildResponsesUrl(endpoint, apiVersion);

        List<AIGenerationInputPart> fileParts = TransientInputSupport.fileUrlInputParts(request);
        List<Map<String, Object>> content;
        try {
            content = buildResponsesInputContent(request, fileParts);
        } catch (UnsupportedTransientDocumentException ex) {
            long responseTime = System.currentTimeMillis() - startTime;
            updateMetrics(false, responseTime);
            log.warn("Azure transient document input was not used: {}", ex.getMessage());
            return TransientInputSupport.unsupportedFileUrlResponse(request, getProviderName(), ex.getMessage());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HEADER_API_KEY, apiKey);

        Map<String, Object> body = new HashMap<>();
        String requestModel = firstNonBlank(request.getModel(), config.getDefaultModel(), deploymentName).orElse("");
        body.put("model", requestModel);
        body.put("input", List.of(Map.of("role", "user", "content", content)));
        if (hasText(request.getSystemPrompt())) {
            body.put("instructions", request.getSystemPrompt());
        }
        putIfNotNull(body, "max_output_tokens", Optional.ofNullable(request.getMaxTokens()).orElse(config.getMaxTokens()));
        putIfNotNull(body, "temperature", Optional.ofNullable(request.getTemperature()).orElse(config.getTemperature()));

        log.info(
            "Azure OpenAI Responses request: url={}, model={}, temperature={}, maxOutputTokens={}, fileUrlInputs={}, promptLength={}",
            url,
            body.get("model"),
            body.get("temperature"),
            body.get("max_output_tokens"),
            fileParts.size(),
            request.getPrompt() != null ? request.getPrompt().length() : 0
        );
        log.debug("Azure OpenAI Responses transientInputs={}", TransientInputSupport.redactedDescriptors(fileParts));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = exchangeWithRetry(url, HttpMethod.POST, entity, Map.class, "responses");

        long responseTime = System.currentTimeMillis() - startTime;
        Map<String, Object> responseBody = requireResponseBody(response, "Azure Responses response body was empty");
        String contentText = extractResponsesText(responseBody);
        updateMetrics(true, responseTime);

        int contentLength = contentText != null ? contentText.length() : 0;
        log.info(
            "Azure OpenAI Responses response: responseTimeMs={}, model={}, contentLength={}",
            responseTime,
            responseBody.get("model"),
            contentLength
        );
        log.debug("Azure OpenAI Responses response contentSnippet={}", snippet(contentText, 500));

        return AIGenerationResponse.builder()
            .content(contentText)
            .model(responseBody.get("model") instanceof String model ? model : requestModel)
            .processingTimeMs(responseTime)
            .requestId(UUID.randomUUID().toString())
            .usage(createUsageFromResponse(responseBody))
            .metadata(Map.of(
                "providerRoute", "azure.responses",
                "transientInputs", TransientInputSupport.redactedDescriptors(fileParts)
            ))
            .build();
    }

    private List<Map<String, Object>> buildResponsesInputContent(AIGenerationRequest request,
                                                                 List<AIGenerationInputPart> fileParts) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of(
            "type", "input_text",
            "text", request.getPrompt() != null ? request.getPrompt() : ""
        ));
        for (AIGenerationInputPart part : fileParts) {
            String contentType = TransientInputSupport.normalizeContentType(part.getContentType());
            if (TransientInputSupport.isAzureResponsesImageUrlContentType(contentType)) {
                validateTransientFileUrl(part);
                Map<String, Object> imageInput = new HashMap<>();
                imageInput.put("type", "input_image");
                imageInput.put("image_url", part.getUrl());
                content.add(imageInput);
                continue;
            }
            if (!TransientInputSupport.isPdfContentType(contentType)) {
                throw new UnsupportedTransientDocumentException(
                    "Azure Responses transient file inputs support PDFs and images only; unsupported content type: " + contentType
                );
            }
            TransientInputSupport.FetchedTransientFile fetchedFile =
                fetchTransientFile(part);
            if (!fetchedFile.isPdf()) {
                throw new UnsupportedTransientDocumentException(
                    "Azure Responses transient file fetch returned unsupported content type: " + fetchedFile.contentType()
                );
            }
            Map<String, Object> fileInput = new HashMap<>();
            fileInput.put("type", "input_file");
            if (hasText(part.getFileName())) {
                fileInput.put("filename", part.getFileName().trim());
            }
            fileInput.put("file_data", TransientInputSupport.dataUri("application/pdf", fetchedFile.bytes()));
            content.add(fileInput);
        }
        return List.copyOf(content);
    }

    private void validateTransientFileUrl(AIGenerationInputPart part) {
        try {
            TransientInputSupport.validateFileUrlInput(part);
        } catch (IllegalArgumentException ex) {
            throw new UnsupportedTransientDocumentException(ex.getMessage());
        }
    }

    private TransientInputSupport.FetchedTransientFile fetchTransientFile(AIGenerationInputPart part) {
        try {
            return TransientInputSupport.fetchTransientFile(httpClient, part, MAX_TRANSIENT_PDF_BYTES);
        } catch (IllegalArgumentException ex) {
            throw new UnsupportedTransientDocumentException(ex.getMessage());
        }
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
        ensureAvailability();
        long startTime = System.currentTimeMillis();
        totalRequests.incrementAndGet();

        try {
            String deployment = azureConfig.getEmbeddingDeploymentName();
            // For Azure AI Services (Foundry), deployment name is not required in URL
            String endpoint = azureConfig.getEndpoint();
            if ((endpoint.contains("/models") || endpoint.contains("services.ai.azure.com")) && !hasText(deployment)) {
                // Foundry format - deployment name not needed in URL
                deployment = null;
            } else if (!hasText(deployment)) {
                throw new AIServiceException("Azure embedding deployment name is not configured");
            }

            String url = buildEmbeddingsUrl(deployment);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HEADER_API_KEY, config.getApiKey());

            Map<String, Object> body = new HashMap<>();
            body.put("input", List.of(request.getText()));

            String text = request.getText();
            log.info(
                "Azure OpenAI embedding request: url={}, inputCount={}, textLength={}",
                url,
                1,
                text != null ? text.length() : 0
            );
            log.debug("Azure OpenAI embedding request textSnippet={}", snippet(text, 300));

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = exchangeWithRetry(url, HttpMethod.POST, entity, Map.class, "embeddings");

            long processingTime = System.currentTimeMillis() - startTime;
            Map<String, Object> responseBody = requireResponseBody(response, "Azure embedding response body was empty");
            List<Map<String, Object>> data = requireMapList(
                responseBody,
                "data",
                "Azure embedding response did not contain data"
            );
            List<Double> embedding = requireDoubleList(
                data.get(0).get("embedding"),
                "Azure embedding data missing"
            );
            updateMetrics(true, processingTime);

            log.info(
                "Azure OpenAI embedding response: responseTimeMs={}, dimensions={}",
                processingTime,
                embedding.size()
            );

            return AIEmbeddingResponse.builder()
                .embedding(embedding)
                .model(deployment)
                .dimensions(embedding.size())
                .processingTimeMs(processingTime)
                .requestId(UUID.randomUUID().toString())
                .build();
        } catch (Exception ex) {
            long processingTime = System.currentTimeMillis() - startTime;
            updateMetrics(false, processingTime);
            log.error("Azure embedding generation failed", ex);
            lastError.set(LocalDateTime.now());
            lastErrorMessage.set(ex.getMessage());
            throw wrapException("Azure embedding generation failed", ex);
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
            .details("Endpoint=" + azureConfig.getEndpoint())
            .lastUpdated(LocalDateTime.now())
            .build();
    }

    @Override
    public ProviderConfig getConfig() {
        return config;
    }

    private void ensureAvailability() {
        if (!isAvailable()) {
            throw new AIServiceException("Azure OpenAI provider is not available. Check API key and endpoint configuration.");
        }
    }

    private String buildChatCompletionsUrl() {
        return buildChatCompletionsUrl(
            azureConfig.getEndpoint(),
            azureConfig.getDeploymentName(),
            azureConfig.getApiVersion()
        );
    }

    private String buildChatCompletionsUrl(String endpointRaw, String deploymentOverride, String apiVersionOverride) {
        String endpoint = normalizeEndpoint(endpointRaw);
        String apiVersion = apiVersionOverride != null ? apiVersionOverride : "2024-02-15-preview";
        
        // Check if endpoint already contains /models/chat/completions (Azure AI Services/Foundry format)
        if (endpoint.contains("/models/chat/completions")) {
            // Azure AI Services (Foundry) format - endpoint already includes the path
            if (endpoint.contains("?")) {
                return endpoint; // Already has query params
            }
            return String.format("%s?api-version=%s", endpoint, apiVersion);
        }
        
        // Check if endpoint contains /openai/v1 (OpenAI-compatible format on Azure AI Services)
        if (endpoint.contains("/openai/v1")) {
            // OpenAI-compatible format - use /chat/completions endpoint
            String deployment = config.getDefaultModel();
            if (deployment == null || deployment.isEmpty()) {
                deployment = deploymentOverride;
            }
            // For OpenAI-compatible format, model is passed in the request body, not URL
            return String.format("%s/chat/completions", endpoint);
        }
        
        // Check if endpoint contains /models (Azure AI Services/Foundry base format)
        if (endpoint.contains("/models")) {
            // Azure AI Services (Foundry) format - add chat/completions
            return String.format("%s/chat/completions?api-version=%s", endpoint, apiVersion);
        }
        
        // Azure OpenAI format - traditional deployment-based
        String deployment = config.getDefaultModel();
        if (deployment == null || deployment.isEmpty()) {
            deployment = deploymentOverride;
        }
        return String.format("%s/openai/deployments/%s/chat/completions?api-version=%s",
            endpoint, deployment, apiVersion);
    }

    private String buildEmbeddingsUrl(String deployment) {
        String endpoint = normalizeEndpoint(azureConfig.getEndpoint());
        String apiVersion = azureConfig.getApiVersion() != null ? azureConfig.getApiVersion() : "2024-02-15-preview";
        
        // Check if endpoint already contains /models/embeddings (Azure AI Services/Foundry format)
        if (endpoint.contains("/models/embeddings")) {
            // Azure AI Services (Foundry) format - endpoint already includes the path
            if (endpoint.contains("?")) {
                return endpoint; // Already has query params
            }
            return String.format("%s?api-version=%s", endpoint, apiVersion);
        }
        
        // Check if endpoint contains /models (Azure AI Services/Foundry base format)
        if (endpoint.contains("/models")) {
            // Azure AI Services (Foundry) format - add embeddings
            return String.format("%s/embeddings?api-version=%s", endpoint, apiVersion);
        }
        
        // Azure OpenAI format - traditional deployment-based
        if (deployment == null || deployment.isEmpty()) {
            deployment = azureConfig.getEmbeddingDeploymentName();
        }
        return String.format("%s/openai/deployments/%s/embeddings?api-version=%s",
            endpoint, deployment, apiVersion);
    }

    private String buildResponsesUrl(String endpointRaw, String apiVersionOverride) {
        String endpoint = normalizeEndpoint(endpointRaw);
        String apiVersion = hasText(apiVersionOverride) ? apiVersionOverride : "2025-04-01-preview";
        if (endpoint.contains("/responses")) {
            return endpoint.contains("?") ? endpoint : endpoint + "?api-version=" + apiVersion;
        }
        if (endpoint.contains("/openai/v1")) {
            return endpoint + "/responses";
        }
        if (endpoint.contains("/models")) {
            return endpoint + "/responses?api-version=" + apiVersion;
        }
        return endpoint + "/openai/v1/responses";
    }

    private String normalizeEndpoint(String endpoint) {
        if (endpoint == null) {
            return "";
        }
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }

    private void applyResponseFormat(Map<String, Object> body, Map<String, Object> parameters) {
        if (body == null || parameters == null || parameters.isEmpty()) {
            return;
        }
        Object responseFormat = parameters.get("response_format");
        if (responseFormat == null) {
            responseFormat = parameters.get("responseFormat");
        }
        normalizeResponseFormat(responseFormat).ifPresent(normalized -> body.put("response_format", normalized));
    }

    private Optional<Object> normalizeResponseFormat(Object responseFormat) {
        if (responseFormat == null) {
            return Optional.empty();
        }
        if (responseFormat instanceof Map<?, ?>) {
            return Optional.of(responseFormat);
        }
        if (!(responseFormat instanceof String)) {
            return Optional.empty();
        }
        String value = ((String) responseFormat).trim().toLowerCase();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        if (value.equals("json") || value.equals("json_object") || value.equals("json-object") || value.equals("jsonobject")) {
            return Optional.of(Map.of("type", "json_object"));
        }
        if (value.equals("text")) {
            return Optional.of(Map.of("type", "text"));
        }
        return Optional.empty();
    }

    private void updateMetrics(boolean success, long responseTime) {
        if (success) {
            successfulRequests.incrementAndGet();
            lastSuccess.set(LocalDateTime.now());
        } else {
            failedRequests.incrementAndGet();
        }

        long total = totalRequests.get();
        double currentAvg = averageResponseTime.get();
        double newAvg = total <= 1 ? responseTime : ((currentAvg * (total - 1)) + responseTime) / total;
        averageResponseTime.set(newAvg);
    }


    private double calculateSuccessRate() {
        long total = totalRequests.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) successfulRequests.get() / total;
    }

    private boolean isHealthy() {
        if (!isAvailable()) {
            return false;
        }
        LocalDateTime recent = lastSuccess.get();
        return recent != null && recent.isAfter(LocalDateTime.now().minusMinutes(5));
    }

    private Map<String, Object> createUsageFromResponse(Map<String, Object> responseBody) {
        Map<String, Object> usage = new HashMap<>();
        Object usageNode = responseBody.get("usage");
        if (usageNode instanceof Map<?, ?> usageMap) {
            usageMap.forEach((key, value) -> {
                if (key != null) {
                    usage.put(String.valueOf(key), value);
                }
            });
        }
        return usage;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (target != null && hasText(key) && value != null) {
            target.put(key, value);
        }
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

    private Map<String, Object> requireResponseBody(ResponseEntity<Map> response, String message) {
        if (response == null || response.getBody() == null) {
            throw new AIServiceException(message);
        }
        return copyStringKeyMap(response.getBody(), message);
    }

    private List<Map<String, Object>> requireMapList(Map<String, Object> source, String key, String message) {
        Object value = source.get(key);
        if (!(value instanceof List<?> items) || items.isEmpty()) {
            throw new AIServiceException(message);
        }
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object item : items) {
            maps.add(requireMap(item, message + " item was not an object"));
        }
        return List.copyOf(maps);
    }

    private Map<String, Object> requireMap(Object value, String message) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new AIServiceException(message);
        }
        return copyStringKeyMap(map, message);
    }

    private String requireString(Map<String, Object> source, String key, String message) {
        Object value = source.get(key);
        if (!(value instanceof String text)) {
            throw new AIServiceException(message);
        }
        return text;
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

    private AIServiceException wrapException(String message, Exception ex) {
        return ex instanceof AIServiceException serviceException
            ? serviceException
            : new AIServiceException(message + ": " + ex.getMessage(), ex);
    }

    private static final class UnsupportedTransientDocumentException extends RuntimeException {

        private UnsupportedTransientDocumentException(String message) {
            super(message);
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
                        "Azure {} call failed with HTTP {} (attempt {}/{}). Retrying after {}ms.",
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
                        "Azure {} call failed due to network/timeout (attempt {}/{}). Retrying after {}ms. Cause: {}",
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
                // Preserve previous behavior for non-HTTP RestClientExceptions.
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    log.warn(
                        "Azure {} call failed (attempt {}/{}). Retrying after {}ms. Cause: {}",
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
        throw new AIServiceException("Azure " + operation + " call failed after retries");
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
