package ai.fabric.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AIProviderConfigTest {

    @Test
    void shouldPreserveCustomGenerationProviderInsteadOfFallingBackToOpenAi() {
        AIProviderConfig config = new AIProviderConfig();
        config.setLlmProvider("custom-provider");

        assertThat(config.resolveLlmDefaults().providerName()).isEqualTo("custom-provider");
        assertThat(config.resolveLlmDefaults().model()).isNull();
        assertThat(config.resolveOrchestrationLlmDefaults().providerName())
            .isEqualTo("custom-provider");
    }

    @Test
    void shouldPreserveCustomPurposeProviderAndExplicitModel() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.OrchestrationLlmConfig orchestration =
            new AIProviderConfig.OrchestrationLlmConfig();
        orchestration.setLlmProvider("orchestration-provider");
        orchestration.setModel("intent-model");
        config.setOrchestration(orchestration);

        AIProviderConfig.GenerationDefaults defaults = config.resolveOrchestrationLlmDefaults();

        assertThat(defaults.providerName()).isEqualTo("orchestration-provider");
        assertThat(defaults.model()).isEqualTo("intent-model");
    }

    @Test
    void shouldPreserveCustomEmbeddingProviderInsteadOfFallingBackToOnnx() {
        AIProviderConfig config = new AIProviderConfig();
        config.setEmbeddingProvider("custom-embedding-provider");

        assertThat(config.resolveEmbeddingDefaults().providerName())
            .isEqualTo("custom-embedding-provider");
        assertThat(config.resolveEmbeddingDefaults().model()).isNull();
    }
}
