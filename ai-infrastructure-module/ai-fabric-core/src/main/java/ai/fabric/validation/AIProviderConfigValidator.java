package ai.fabric.validation;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.config.AIServiceConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates AI provider configuration at application startup.
 *
 * <p>This implements fail-fast validation for selected providers (greenfield).</p>
 */
@Slf4j
public class AIProviderConfigValidator {

    private final AIProviderConfig providerConfig;
    private final AIServiceConfig serviceConfig;

    public AIProviderConfigValidator(AIProviderConfig providerConfig, AIServiceConfig serviceConfig) {
        this.providerConfig = providerConfig;
        this.serviceConfig = serviceConfig;
    }

    @PostConstruct
    public void validateOnStartup() {
        if (providerConfig == null || serviceConfig == null) {
            throw new IllegalStateException("AI provider configuration beans must not be null");
        }

        if (!providerConfig.isEnabled()) {
            log.info("AI providers are disabled (ai.providers.enabled=false). Skipping provider configuration validation.");
            return;
        }

        ValidationResult result = validate();

        if (!result.isValid()) {
            String message = buildErrorMessage(result);
            log.error("AI Provider Configuration Validation Failed:\n{}", message);
            throw new IllegalStateException("Invalid AI provider configuration. See logs for details.");
        }

        for (String warning : result.warnings()) {
            log.warn("AI configuration warning: {}", warning);
        }

        log.info("AI provider configuration validation successful");
    }

    /**
     * Validate current provider/service configuration.
     *
     * @return validation result containing errors and warnings
     */
    public ValidationResult validate() {
        ValidationResult result = new ValidationResult();

        if (isGenerationEnabled()) {
            validateLlmProvider(result);
        } else {
            result.addWarning("ai.service.features.enable-generation=false. Skipping LLM provider validation.");
        }

        if (isEmbeddingsEnabled()) {
            validateEmbeddingProvider(result);
        } else {
            result.addWarning("ai.service.features.enable-embeddings=false. Skipping embedding provider validation.");
        }
        validateServiceConfig(result);

        return result;
    }

    private void validateLlmProvider(ValidationResult result) {
        String provider = normalize(providerConfig.getLlmProvider());
        boolean globalDisabled = provider.isBlank() || "none".equals(provider) || "disabled".equals(provider);
        if (globalDisabled) {
            boolean hasPurposeOverride = hasPurposeLlmOverride(providerConfig.getOrchestration())
                || hasPurposeLlmOverride(providerConfig.getGeneration());
            if (hasPurposeOverride) {
                result.addWarning("ai.providers.llm-provider is blank/disabled. Using purpose-specific LLM providers only.");
            } else {
                result.addWarning("ai.providers.llm-provider is blank/disabled and no purpose-specific provider is configured. LLM generation will be unavailable.");
            }
        } else {
            switch (provider) {
                case "openai" -> validateOpenAI(result, true, true);
                case "anthropic" -> validateAnthropic(result);
                case "cohere" -> validateCohere(result);
                case "azure" -> validateAzure(result, true, false, true);
                default -> result.addWarning("ai.providers.llm-provider='" + provider
                    + "' is not a built-in provider. Skipping strict LLM provider validation.");
            }
        }

        validatePurposeLlmProvider("ai.providers.orchestration", providerConfig.getOrchestration(), result);
        validatePurposeLlmProvider("ai.providers.generation", providerConfig.getGeneration(), result);
    }

    private boolean hasPurposeLlmOverride(Object purposeConfig) {
        if (purposeConfig instanceof AIProviderConfig.OrchestrationLlmConfig orchestration) {
            return !normalize(orchestration.getLlmProvider()).isBlank();
        }
        if (purposeConfig instanceof AIProviderConfig.GenerationLlmConfig generation) {
            return !normalize(generation.getLlmProvider()).isBlank();
        }
        return false;
    }

    private void validatePurposeLlmProvider(String configPrefix,
                                            Object purposeConfig,
                                            ValidationResult result) {
        if (purposeConfig == null) {
            return;
        }

        String provider;
        String modelOverride = null;
        if (purposeConfig instanceof AIProviderConfig.OrchestrationLlmConfig orchestration) {
            provider = normalize(orchestration.getLlmProvider());
            modelOverride = orchestration.getModel();
        } else if (purposeConfig instanceof AIProviderConfig.GenerationLlmConfig generation) {
            provider = normalize(generation.getLlmProvider());
            modelOverride = generation.getModel();
        } else {
            return;
        }

        if (provider.isBlank()) {
            // Purpose config is present but provider is blank: treat as "use global".
            return;
        }

        boolean hasModelOverride = modelOverride != null && !modelOverride.trim().isEmpty();
        switch (provider) {
            case "openai" -> validateOpenAI(result, true, !hasModelOverride);
            case "anthropic" -> validateAnthropic(result, !hasModelOverride);
            case "cohere" -> validateCohere(result, !hasModelOverride);
            case "azure" -> validateAzure(result, true, false, !hasModelOverride);
            default -> result.addWarning(configPrefix + ".llm-provider='" + provider
                + "' is not a built-in provider. Skipping strict validation.");
        }
    }

    private void validateEmbeddingProvider(ValidationResult result) {
        String provider = normalize(providerConfig.getEmbeddingProvider());
        if (provider.isBlank() || "none".equals(provider) || "disabled".equals(provider)) {
            result.addWarning("ai.providers.embedding-provider is blank/disabled. Embeddings will be unavailable.");
            return;
        }

        switch (provider) {
            case "openai" -> validateOpenAI(result, false, false);
            case "azure" -> validateAzure(result, false, true, false);
            case "onnx" -> validateOnnx(result);
            default -> result.addWarning("ai.providers.embedding-provider='" + provider
                + "' is not a built-in provider. Skipping strict embedding provider validation.");
        }
    }

    private void validateOpenAI(ValidationResult result, boolean isLlm, boolean requireModel) {
        AIProviderConfig.OpenAIConfig config = providerConfig.getOpenai();

        if (config == null) {
            result.addError("ai.providers.openai", "OpenAI configuration block is missing");
            return;
        }

        if (!config.isEnabled()) {
            if (isLlm) {
                result.addWarning("ai.providers.openai.enabled=false while OpenAI is the selected LLM provider. LLM generation will be unavailable.");
                return;
            }
            result.addError("ai.providers.openai.enabled", "OpenAI is selected but ai.providers.openai.enabled=false");
            return;
        }

        String apiKey = isLlm ? config.getApiKey() : firstPresent(config.getEmbeddingApiKey(), config.getApiKey());
        String baseUrl = isLlm ? config.getBaseUrl() : firstPresent(config.getEmbeddingBaseUrl(), config.getBaseUrl());

        if (isBlank(apiKey)) {
            result.addError(
                isLlm ? "ai.providers.openai.api-key" : "ai.providers.openai.embedding-api-key",
                "OpenAI API key is required when OpenAI is selected."
            );
        }

        if (isBlank(baseUrl)) {
            result.addError(
                isLlm ? "ai.providers.openai.base-url" : "ai.providers.openai.embedding-base-url",
                "OpenAI base URL is required (e.g. https://api.openai.com/v1)."
            );
        }

        if (isLlm && requireModel && isBlank(config.getModel())) {
            result.addError("ai.providers.openai.model",
                "OpenAI model is required when OpenAI is the LLM provider.");
        }

        if (!isLlm && isBlank(config.getEmbeddingModel())) {
            result.addError("ai.providers.openai.embedding-model",
                "OpenAI embedding model is required when OpenAI is the embedding provider.");
        }

        if (config.getTimeout() == null || config.getTimeout() <= 0) {
            result.addWarning("ai.providers.openai.timeout is not configured or invalid. Requests may hang longer than expected.");
        }
    }

    private void validateAnthropic(ValidationResult result) {
        validateAnthropic(result, true);
    }

    private void validateAnthropic(ValidationResult result, boolean requireModel) {
        AIProviderConfig.AnthropicConfig config = providerConfig.getAnthropic();

        if (config == null) {
            result.addError("ai.providers.anthropic", "Anthropic configuration block is missing");
            return;
        }

        if (!config.isEnabled()) {
            result.addWarning("ai.providers.anthropic.enabled=false while Anthropic is the selected LLM provider. LLM generation will be unavailable.");
            return;
        }

        if (isBlank(config.getApiKey())) {
            result.addError("ai.providers.anthropic.api-key", "Anthropic API key is required when Anthropic is selected.");
        }

        if (isBlank(config.getBaseUrl())) {
            result.addError("ai.providers.anthropic.base-url", "Anthropic base URL is required.");
        }

        if (requireModel && isBlank(config.getModel())) {
            result.addError("ai.providers.anthropic.model", "Anthropic model is required when Anthropic is selected.");
        }
    }

    private void validateCohere(ValidationResult result) {
        validateCohere(result, true);
    }

    private void validateCohere(ValidationResult result, boolean requireModel) {
        AIProviderConfig.CohereConfig config = providerConfig.getCohere();

        if (config == null) {
            result.addError("ai.providers.cohere", "Cohere configuration block is missing");
            return;
        }

        if (!config.isEnabled()) {
            result.addWarning("ai.providers.cohere.enabled=false while Cohere is the selected LLM provider. LLM generation will be unavailable.");
            return;
        }

        if (isBlank(config.getApiKey())) {
            result.addError("ai.providers.cohere.api-key", "Cohere API key is required when Cohere is selected.");
        }

        if (isBlank(config.getBaseUrl())) {
            result.addError("ai.providers.cohere.base-url", "Cohere base URL is required.");
        }

        if (requireModel && isBlank(config.getModel())) {
            result.addError("ai.providers.cohere.model", "Cohere model is required when Cohere is selected.");
        }
    }

    private void validateAzure(ValidationResult result, boolean isLlm, boolean isEmbedding, boolean requireLlmDeployment) {
        AIProviderConfig.AzureConfig config = providerConfig.getAzure();

        if (config == null) {
            result.addError("ai.providers.azure", "Azure configuration block is missing");
            return;
        }

        if (!config.isEnabled()) {
            if (isEmbedding) {
                result.addError("ai.providers.azure.enabled", "Azure is selected but ai.providers.azure.enabled=false");
                return;
            }
            result.addWarning("ai.providers.azure.enabled=false while Azure is the selected LLM provider. LLM generation will be unavailable.");
            return;
        }

        String apiKey = isEmbedding ? firstPresent(config.getEmbeddingApiKey(), config.getApiKey()) : config.getApiKey();
        String endpoint = isEmbedding ? firstPresent(config.getEmbeddingEndpoint(), config.getEndpoint()) : config.getEndpoint();
        String apiVersion = isEmbedding ? firstPresent(config.getEmbeddingApiVersion(), config.getApiVersion()) : config.getApiVersion();

        if (isBlank(apiKey)) {
            result.addError(
                isEmbedding ? "ai.providers.azure.embedding-api-key" : "ai.providers.azure.api-key",
                "Azure API key is required when Azure is selected."
            );
        }

        if (isBlank(endpoint)) {
            result.addError(
                isEmbedding ? "ai.providers.azure.embedding-endpoint" : "ai.providers.azure.endpoint",
                "Azure endpoint is required (your Azure OpenAI resource URL)."
            );
        }

        if (isBlank(apiVersion)) {
            result.addError(
                isEmbedding ? "ai.providers.azure.embedding-api-version" : "ai.providers.azure.api-version",
                "Azure api-version is required when Azure is selected."
            );
        }

        if (isLlm && requireLlmDeployment && isBlank(config.getDeploymentName())) {
            result.addError("ai.providers.azure.deployment-name", "Azure deployment-name is required when Azure is the LLM provider.");
        }

        if (isEmbedding && isBlank(config.getEmbeddingDeploymentName())) {
            result.addError("ai.providers.azure.embedding-deployment-name",
                "Azure embedding-deployment-name is required when Azure is the embedding provider.");
        }
    }

    private void validateOnnx(ValidationResult result) {
        AIProviderConfig.ONNXConfig config = providerConfig.getOnnx();

        if (config == null) {
            result.addError("ai.providers.onnx", "ONNX configuration block is missing");
            return;
        }

        if (!config.isEnabled()) {
            result.addError("ai.providers.onnx.enabled", "ONNX is selected but ai.providers.onnx.enabled=false");
            return;
        }

        if (isBlank(config.getModelPath())) {
            result.addError("ai.providers.onnx.model-path", "ONNX model-path is required when ONNX is selected.");
        }

        if (isBlank(config.getTokenizerPath())) {
            result.addWarning("ai.providers.onnx.tokenizer-path is not configured. Falling back to legacy tokenization.");
        }
    }

    private void validateServiceConfig(ValidationResult result) {
        if (serviceConfig.getDefaultTimeout() == null || serviceConfig.getDefaultTimeout() <= 0) {
            result.addWarning("ai.service.default-timeout is not configured or invalid.");
        }

        if (serviceConfig.getMaxRetries() != null && serviceConfig.getMaxRetries() < 0) {
            result.addError("ai.service.max-retries", "Max retries cannot be negative.");
        }

        if (serviceConfig.getThreadPoolSize() != null && serviceConfig.getThreadPoolSize() <= 0) {
            result.addError("ai.service.thread-pool-size", "Thread pool size must be positive.");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private boolean isEmbeddingsEnabled() {
        if (serviceConfig.getFeatures() == null) {
            return true;
        }
        Boolean flag = serviceConfig.getFeatures().getEnableEmbeddings();
        return flag == null || flag;
    }

    private boolean isGenerationEnabled() {
        if (serviceConfig.getFeatures() == null) {
            return true;
        }
        Boolean flag = serviceConfig.getFeatures().getEnableGeneration();
        return flag == null || flag;
    }

    private String firstPresent(String primary, String fallback) {
        return isBlank(primary) ? fallback : primary;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String buildErrorMessage(ValidationResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== AI Provider Configuration Errors ===\n");
        for (ValidationIssue error : result.errors()) {
            sb.append("  - ").append(error.key()).append(": ").append(error.message()).append('\n');
        }
        if (!result.warnings().isEmpty()) {
            sb.append("\n=== AI Provider Configuration Warnings ===\n");
            for (String warning : result.warnings()) {
                sb.append("  - ").append(warning).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Validation outcome container.
     */
    public static final class ValidationResult {
        private final List<ValidationIssue> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        public void addError(String key, String message) {
            errors.add(new ValidationIssue(key, message));
        }

        public void addWarning(String message) {
            warnings.add(message);
        }

        public boolean isValid() {
            return errors.isEmpty();
        }

        public List<ValidationIssue> errors() {
            return List.copyOf(errors);
        }

        public List<String> warnings() {
            return List.copyOf(warnings);
        }
    }

    /**
     * Single validation error entry.
     */
    public record ValidationIssue(String key, String message) {}
}
