package ai.fabric.provider.anthropic;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIGenerationInputPart;
import ai.fabric.dto.AIGenerationInputType;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.exception.AIServiceException;
import ai.fabric.http.HttpClient;
import ai.fabric.provider.ProviderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicProviderTest {

    @Test
    void autoConfigurationAppliesReleaseDefaultsForApiKeyOnlyConfig() {
        AIProviderConfig aiProviderConfig = new AIProviderConfig();
        AIProviderConfig.AnthropicConfig anthropic = aiProviderConfig.getAnthropic();
        anthropic.setEnabled(true);
        anthropic.setApiKey("test-key");

        ProviderConfig providerConfig = new AnthropicAutoConfiguration().anthropicProviderConfig(aiProviderConfig);

        assertThat(providerConfig.isValid()).isTrue();
        assertThat(providerConfig.getBaseUrl()).isEqualTo(AnthropicAutoConfiguration.DEFAULT_BASE_URL);
        assertThat(providerConfig.getDefaultModel()).isEqualTo(AnthropicAutoConfiguration.DEFAULT_MODEL);
        assertThat(providerConfig.getTimeoutSeconds()).isEqualTo(AnthropicAutoConfiguration.DEFAULT_TIMEOUT_SECONDS);
    }

    @Test
    void imageFileUrlInputsUseImageUrlBlocks() {
        RecordingHttpClient httpClient = new RecordingHttpClient(successResponse());
        AnthropicProvider provider = new AnthropicProvider(config(), httpClient);

        var response = provider.generateContent(AIGenerationRequest.builder()
            .prompt("Analyze attached image")
            .model("claude-3-7-sonnet-latest")
            .inputParts(List.of(AIGenerationInputPart.builder()
                .type(AIGenerationInputType.FILE_URL)
                .documentId("img-1")
                .fileName("scan.png")
                .contentType("image/png")
                .url("https://files.example.com/tmp/scan.png?sig=secret")
                .build()))
            .build());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) httpClient.lastRequestBody().get("messages");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) messages.get(messages.size() - 1).get("content");
        assertThat(content).anySatisfy(item -> {
            assertThat(item).containsEntry("type", "image");
            @SuppressWarnings("unchecked")
            Map<String, Object> source = (Map<String, Object>) item.get("source");
            assertThat(source)
                .containsEntry("type", "url")
                .containsEntry("url", "https://files.example.com/tmp/scan.png?sig=secret");
        });
        assertThat(response.getContent()).isEqualTo("{\"documentUsage\":[{\"status\":\"USED\"}]}");
        assertThat(response.getMetadata().toString()).contains("[REDACTED_TRANSIENT_FILE_URL]");
        assertThat(response.getMetadata().toString()).doesNotContain("sig=secret");
    }

    @Test
    void pdfFileUrlInputsUseDocumentUrlBlocks() {
        RecordingHttpClient httpClient = new RecordingHttpClient(successResponse());
        AnthropicProvider provider = new AnthropicProvider(config(), httpClient);

        provider.generateContent(AIGenerationRequest.builder()
            .prompt("Analyze attached PDF")
            .model("claude-3-7-sonnet-latest")
            .inputParts(List.of(AIGenerationInputPart.builder()
                .type(AIGenerationInputType.FILE_URL)
                .documentId("doc-1")
                .fileName("brief.pdf")
                .contentType("application/pdf")
                .url("https://files.example.com/tmp/brief.pdf?sig=secret")
                .build()))
            .build());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) httpClient.lastRequestBody().get("messages");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) messages.get(messages.size() - 1).get("content");
        assertThat(content).anySatisfy(item -> assertThat(item).containsEntry("type", "document"));
    }

    @Test
    void unsupportedFileUrlContentTypeFailsClosed() {
        RecordingHttpClient httpClient = new RecordingHttpClient(successResponse());
        AnthropicProvider provider = new AnthropicProvider(config(), httpClient);

        var response = provider.generateContent(AIGenerationRequest.builder()
            .prompt("Analyze attached file")
            .model("claude-3-7-sonnet-latest")
            .inputParts(List.of(AIGenerationInputPart.builder()
                .type(AIGenerationInputType.FILE_URL)
                .documentId("zip-1")
                .fileName("bundle.zip")
                .contentType("application/zip")
                .url("https://files.example.com/tmp/bundle.zip?sig=secret")
                .build()))
            .build());

        assertThat(httpClient.lastRequestBody()).isNull();
        assertThat(response.getStatus()).isEqualTo("PROVIDER_FILE_URL_INPUT_UNSUPPORTED");
        assertThat(response.getContent()).contains("\"status\":\"NOT_USED\"");
        assertThat(response.getContent()).doesNotContain("sig=secret");
        assertThat(provider.getStatus().getTotalRequests()).isEqualTo(1);
        assertThat(provider.getStatus().getSuccessfulRequests()).isZero();
        assertThat(provider.getStatus().getFailedRequests()).isEqualTo(1);
    }

    @Test
    void malformedResponseFailsClearlyAndRecordsFailure() {
        RecordingHttpClient httpClient = new RecordingHttpClient(ResponseEntity.ok(Map.of(
            "model", "claude-3-7-sonnet-latest"
        )));
        AnthropicProvider provider = new AnthropicProvider(config(), httpClient);

        assertThatThrownBy(() -> provider.generateContent(AIGenerationRequest.builder()
            .prompt("Hello")
            .build()))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("Anthropic response content text was missing");

        assertThat(provider.getStatus().getTotalRequests()).isEqualTo(1);
        assertThat(provider.getStatus().getSuccessfulRequests()).isZero();
        assertThat(provider.getStatus().getFailedRequests()).isEqualTo(1);
    }

    @Test
    void embeddingRequestsFailWithExplicitUnsupportedCapability() {
        AnthropicProvider provider = new AnthropicProvider(config(), new RecordingHttpClient(successResponse()));

        assertThatThrownBy(() -> provider.generateEmbedding(AIEmbeddingRequest.builder()
            .text("embed me")
            .build()))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("Anthropic does not provide embedding services directly");

        assertThat(provider.getStatus().getTotalRequests()).isEqualTo(1);
        assertThat(provider.getStatus().getSuccessfulRequests()).isZero();
        assertThat(provider.getStatus().getFailedRequests()).isEqualTo(1);
    }

    private static ProviderConfig config() {
        return ProviderConfig.builder()
            .providerName("anthropic")
            .apiKey("test-key")
            .baseUrl("https://api.anthropic.com/v1")
            .defaultModel("claude-3-7-sonnet-latest")
            .maxTokens(512)
            .temperature(0.2d)
            .timeoutSeconds(30)
            .enabled(true)
            .build();
    }

    private static ResponseEntity<Map> successResponse() {
        return ResponseEntity.ok(Map.of(
            "model", "claude-3-7-sonnet-latest",
            "content", List.of(Map.of("text", "{\"documentUsage\":[{\"status\":\"USED\"}]}")),
            "usage", Map.of("input_tokens", 11, "output_tokens", 6)
        ));
    }

    private static final class RecordingHttpClient implements HttpClient {
        private final ResponseEntity<Map> response;
        private Map<String, Object> lastRequestBody;

        private RecordingHttpClient(ResponseEntity<Map> response) {
            this.response = response;
        }

        @Override
        public <T> ResponseEntity<T> exchange(String url,
                                              HttpMethod method,
                                              HttpEntity<?> requestEntity,
                                              Class<T> responseType) {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) requestEntity.getBody();
            lastRequestBody = body;
            @SuppressWarnings("unchecked")
            ResponseEntity<T> casted = (ResponseEntity<T>) response;
            return casted;
        }

        private Map<String, Object> lastRequestBody() {
            return lastRequestBody;
        }
    }
}
