package ai.fabric.provider;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AIGenerationInputPart;
import ai.fabric.dto.AIGenerationInputType;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransientInputSupportTest {

    @Test
    void unsupportedFileUrlResponseReturnsExplicitNotUsedEvidenceWithoutUrl() {
        AIGenerationRequest request = requestWithFileUrl();

        AIGenerationResponse response = TransientInputSupport.unsupportedFileUrlResponse(
            request,
            "cohere",
            "not supported"
        );

        assertThat(response.getStatus()).isEqualTo(TransientInputSupport.STATUS_UNSUPPORTED);
        assertThat(response.getContent()).contains("\"status\":\"NOT_USED\"");
        assertThat(response.getContent()).contains("\"accessMethod\":\"NONE\"");
        assertThat(response.getContent()).doesNotContain("https://files.example.com");
        assertThat(response.getMetadata().toString()).contains("[REDACTED_TRANSIENT_FILE_URL]");
        assertThat(response.getMetadata().toString()).doesNotContain("sig=secret");
    }

    @Test
    void providerManagerDoesNotFallbackForTransientFileUrlInputs() {
        FailingProvider primary = new FailingProvider("openai");
        CountingProvider fallback = new CountingProvider("cohere");
        AIProviderConfig config = new AIProviderConfig();
        config.setLlmProvider("openai");
        config.setEnableFallback(true);

        AIProviderManager manager = new AIProviderManager(List.of(primary, fallback), config);
        manager.initialize();

        assertThatThrownBy(() -> manager.generateContent(requestWithFileUrl()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("primary failed");
        assertThat(fallback.calls).isZero();
    }

    @Test
    void providerManagerFailsClosedWhenTransientResponseOmitsDocumentUsage() {
        MissingUsageProvider primary = new MissingUsageProvider("openai");
        AIProviderConfig config = new AIProviderConfig();
        config.setLlmProvider("openai");
        config.setEnableFallback(true);

        AIProviderManager manager = new AIProviderManager(List.of(primary), config);
        manager.initialize();

        AIGenerationResponse response = manager.generateContent(requestWithFileUrl());

        assertThat(response.getStatus()).isEqualTo(TransientInputSupport.STATUS_UNSUPPORTED);
        assertThat(response.getContent()).contains("\"status\":\"NOT_USED\"");
        assertThat(response.getContent()).contains("documentUsage");
        assertThat(response.getContent()).doesNotContain("sig=secret");
    }

    @Test
    void validateFileUrlInputRejectsUnsafeTemporaryUrls() {
        TransientInputSupport.validateFileUrlInput(AIGenerationInputPart.builder()
            .type(AIGenerationInputType.FILE_URL)
            .url("https://files.example.com/tmp/brief.pdf?sig=secret")
            .build());

        assertThatThrownBy(() -> TransientInputSupport.validateFileUrlInput(AIGenerationInputPart.builder()
            .type(AIGenerationInputType.FILE_URL)
            .url("http://files.example.com/tmp/brief.pdf")
            .build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HTTPS");

        assertThatThrownBy(() -> TransientInputSupport.validateFileUrlInput(AIGenerationInputPart.builder()
            .type(AIGenerationInputType.FILE_URL)
            .url("https://127.0.0.1/tmp/brief.pdf")
            .build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not allowed");
    }

    private static AIGenerationRequest requestWithFileUrl() {
        return AIGenerationRequest.builder()
            .prompt("Analyze")
            .inputParts(List.of(AIGenerationInputPart.builder()
                .type(AIGenerationInputType.FILE_URL)
                .documentId("doc-1")
                .fileName("brief.pdf")
                .contentType("application/pdf")
                .url("https://files.example.com/tmp/brief.pdf?sig=secret")
                .build()))
            .build();
    }

    private static ProviderConfig config(String name) {
        return ProviderConfig.builder()
            .providerName(name)
            .apiKey("key")
            .baseUrl("https://provider.example.com")
            .defaultModel("model")
            .timeoutSeconds(10)
            .enabled(true)
            .build();
    }

    private static class FailingProvider implements AIProvider {
        private final String name;

        private FailingProvider(String name) {
            this.name = name;
        }

        @Override
        public String getProviderName() {
            return name;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public AIGenerationResponse generateContent(AIGenerationRequest request) {
            throw new RuntimeException("primary failed");
        }

        @Override
        public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
            return null;
        }

        @Override
        public ProviderStatus getStatus() {
            return ProviderStatus.builder().providerName(name).available(true).healthy(false).build();
        }

        @Override
        public ProviderConfig getConfig() {
            return config(name);
        }
    }

    private static final class CountingProvider extends FailingProvider {
        private int calls;

        private CountingProvider(String name) {
            super(name);
        }

        @Override
        public AIGenerationResponse generateContent(AIGenerationRequest request) {
            calls += 1;
            return AIGenerationResponse.builder().content("fallback").build();
        }
    }

    private static final class MissingUsageProvider extends FailingProvider {

        private MissingUsageProvider(String name) {
            super(name);
        }

        @Override
        public AIGenerationResponse generateContent(AIGenerationRequest request) {
            return AIGenerationResponse.builder()
                .content("{\"answer\":\"I read the document\"}")
                .build();
        }
    }
}
