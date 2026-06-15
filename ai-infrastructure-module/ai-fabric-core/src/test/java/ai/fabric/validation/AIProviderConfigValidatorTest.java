package ai.fabric.validation;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.config.AIServiceConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AIProviderConfigValidatorTest {

    @Test
    void shouldPassValidation_whenOpenAiLlmAndOnnxEmbeddingAreConfigured() {
        AIProviderConfig providerConfig = new AIProviderConfig();
        providerConfig.setLlmProvider("openai");
        providerConfig.setEmbeddingProvider("onnx");

        providerConfig.getOpenai().setEnabled(true);
        providerConfig.getOpenai().setApiKey("sk-test");
        providerConfig.getOpenai().setBaseUrl("https://api.openai.com/v1");
        providerConfig.getOpenai().setModel("gpt-4o-mini");

        providerConfig.getOnnx().setEnabled(true);
        providerConfig.getOnnx().setModelPath("classpath:/models/embeddings/all-MiniLM-L6-v2.onnx");

        AIServiceConfig serviceConfig = new AIServiceConfig();
        serviceConfig.setDefaultTimeout(30);
        serviceConfig.setMaxRetries(3);
        serviceConfig.setThreadPoolSize(10);

        AIProviderConfigValidator validator = new AIProviderConfigValidator(providerConfig, serviceConfig);
        AIProviderConfigValidator.ValidationResult result = validator.validate();

        assertThat(result.isValid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void shouldFailValidation_whenOpenAiLlmMissingRequiredFields() {
        AIProviderConfig providerConfig = new AIProviderConfig();
        providerConfig.setLlmProvider("openai");
        providerConfig.setEmbeddingProvider("onnx");

        providerConfig.getOpenai().setEnabled(true);
        // missing: apiKey, baseUrl, model

        providerConfig.getOnnx().setEnabled(true);
        providerConfig.getOnnx().setModelPath("classpath:/models/embeddings/all-MiniLM-L6-v2.onnx");

        AIServiceConfig serviceConfig = new AIServiceConfig();
        serviceConfig.setDefaultTimeout(30);
        serviceConfig.setMaxRetries(3);
        serviceConfig.setThreadPoolSize(10);

        AIProviderConfigValidator validator = new AIProviderConfigValidator(providerConfig, serviceConfig);
        AIProviderConfigValidator.ValidationResult result = validator.validate();

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors())
            .extracting(AIProviderConfigValidator.ValidationIssue::key)
            .contains(
                "ai.providers.openai.api-key",
                "ai.providers.openai.base-url",
                "ai.providers.openai.model"
            );
    }

    @Test
    void shouldFailValidation_whenOnnxEmbeddingProviderMissingModelPath() {
        AIProviderConfig providerConfig = new AIProviderConfig();
        providerConfig.setLlmProvider("openai");
        providerConfig.setEmbeddingProvider("onnx");

        providerConfig.getOpenai().setEnabled(true);
        providerConfig.getOpenai().setApiKey("sk-test");
        providerConfig.getOpenai().setBaseUrl("https://api.openai.com/v1");
        providerConfig.getOpenai().setModel("gpt-4o-mini");

        providerConfig.getOnnx().setEnabled(true);
        providerConfig.getOnnx().setModelPath(null);

        AIServiceConfig serviceConfig = new AIServiceConfig();
        serviceConfig.setDefaultTimeout(30);
        serviceConfig.setMaxRetries(3);
        serviceConfig.setThreadPoolSize(10);

        AIProviderConfigValidator validator = new AIProviderConfigValidator(providerConfig, serviceConfig);
        AIProviderConfigValidator.ValidationResult result = validator.validate();

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors())
            .extracting(AIProviderConfigValidator.ValidationIssue::key)
            .contains("ai.providers.onnx.model-path");
    }

    @Test
    void shouldValidatePurposeSpecificLlmWhenGlobalIsDisabled() {
        AIProviderConfig providerConfig = new AIProviderConfig();
        providerConfig.setLlmProvider("none"); // disable global provider
        providerConfig.setEmbeddingProvider("onnx");

        providerConfig.getOpenai().setEnabled(true);
        providerConfig.getOpenai().setApiKey("sk-test");
        providerConfig.getOpenai().setBaseUrl("https://api.openai.com/v1");
        providerConfig.getOpenai().setModel(null); // model provided via purpose override

        AIProviderConfig.OrchestrationLlmConfig orchestration = new AIProviderConfig.OrchestrationLlmConfig();
        orchestration.setLlmProvider("openai");
        orchestration.setModel("gpt-4o-mini");
        providerConfig.setOrchestration(orchestration);

        providerConfig.getOnnx().setEnabled(true);
        providerConfig.getOnnx().setModelPath("classpath:/models/embeddings/all-MiniLM-L6-v2.onnx");

        AIServiceConfig serviceConfig = new AIServiceConfig();
        serviceConfig.setDefaultTimeout(30);
        serviceConfig.setMaxRetries(3);
        serviceConfig.setThreadPoolSize(10);

        AIProviderConfigValidator validator = new AIProviderConfigValidator(providerConfig, serviceConfig);
        AIProviderConfigValidator.ValidationResult result = validator.validate();

        assertThat(result.isValid()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isNotEmpty();
    }
}
