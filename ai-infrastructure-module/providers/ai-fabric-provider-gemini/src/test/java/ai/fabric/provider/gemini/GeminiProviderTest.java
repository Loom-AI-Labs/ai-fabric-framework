package ai.fabric.provider.gemini;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AIGenerationInputPart;
import ai.fabric.dto.AIGenerationInputType;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.exception.AIServiceException;
import ai.fabric.http.HttpClient;
import ai.fabric.provider.ProviderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiProviderTest {

    @Test
    void autoConfigurationAppliesReleaseDefaultsForApiKeyOnlyConfig() {
        AIProviderConfig aiProviderConfig = new AIProviderConfig();
        AIProviderConfig.GeminiConfig gemini = aiProviderConfig.getGemini();
        gemini.setEnabled(true);
        gemini.setApiKey("test-key");

        ProviderConfig providerConfig = new GeminiAutoConfiguration().geminiProviderConfig(aiProviderConfig);

        assertThat(providerConfig.isValid()).isTrue();
        assertThat(providerConfig.getBaseUrl()).isEqualTo(GeminiAutoConfiguration.DEFAULT_BASE_URL);
        assertThat(providerConfig.getDefaultModel()).isEqualTo(GeminiAutoConfiguration.DEFAULT_MODEL);
        assertThat(providerConfig.getDefaultEmbeddingModel()).isEqualTo(GeminiAutoConfiguration.DEFAULT_EMBEDDING_MODEL);
        assertThat(providerConfig.getMaxTokens()).isEqualTo(GeminiAutoConfiguration.DEFAULT_MAX_TOKENS);
        assertThat(providerConfig.getTemperature()).isEqualTo(GeminiAutoConfiguration.DEFAULT_TEMPERATURE);
        assertThat(providerConfig.getTimeoutSeconds()).isEqualTo(GeminiAutoConfiguration.DEFAULT_TIMEOUT_SECONDS);
    }

    @Test
    void supportedFileUrlInputsAreFetchedTransientlyAndSentAsInlineData() {
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of(imageResponse(), generateSuccessResponse()));
        GeminiProvider provider = new GeminiProvider(config(), httpClient);

        var response = provider.generateContent(AIGenerationRequest.builder()
            .prompt("Analyze attached image")
            .model("gemini-2.5-flash")
            .inputParts(List.of(AIGenerationInputPart.builder()
                .type(AIGenerationInputType.FILE_URL)
                .documentId("img-1")
                .fileName("scan.png")
                .contentType("image/png")
                .url("https://files.example.com/tmp/scan.png?sig=secret")
                .build()))
            .build());

        assertThat(httpClient.urls()).containsExactly(
            "https://files.example.com/tmp/scan.png?sig=secret",
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=test-key"
        );
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contents = (List<Map<String, Object>>) httpClient.providerRequestBody().get("contents");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts = (List<Map<String, Object>>) contents.get(contents.size() - 1).get("parts");
        assertThat(parts).anySatisfy(part -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> inlineData = (Map<String, Object>) part.get("inlineData");
            assertThat(inlineData)
                .containsEntry("mimeType", "image/png")
                .containsKey("data");
        });
        assertThat(response.getContent()).isEqualTo("{\"documentUsage\":[{\"status\":\"USED\"}]}");
        assertThat(response.getMetadata().toString()).contains("[REDACTED_TRANSIENT_FILE_URL]");
        assertThat(response.getMetadata().toString()).doesNotContain("sig=secret");
    }

    @Test
    void unsupportedFileUrlContentTypeFailsClosedWithoutFetching() {
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of());
        GeminiProvider provider = new GeminiProvider(config(), httpClient);

        var response = provider.generateContent(AIGenerationRequest.builder()
            .prompt("Analyze attached file")
            .model("gemini-2.5-flash")
            .inputParts(List.of(AIGenerationInputPart.builder()
                .type(AIGenerationInputType.FILE_URL)
                .documentId("zip-1")
                .fileName("bundle.zip")
                .contentType("application/zip")
                .url("https://files.example.com/tmp/bundle.zip?sig=secret")
                .build()))
            .build());

        assertThat(httpClient.urls()).isEmpty();
        assertThat(response.getStatus()).isEqualTo("PROVIDER_FILE_URL_INPUT_UNSUPPORTED");
        assertThat(response.getContent()).contains("\"status\":\"NOT_USED\"");
        assertThat(response.getContent()).doesNotContain("sig=secret");
        assertThat(provider.getStatus().getTotalRequests()).isEqualTo(1);
        assertThat(provider.getStatus().getSuccessfulRequests()).isZero();
        assertThat(provider.getStatus().getFailedRequests()).isEqualTo(1);
    }

    @Test
    void malformedGenerateContentResponseFailsClearlyAndRecordsFailure() {
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of(ResponseEntity.ok(Map.of(
            "candidates", List.of(Map.of("finishReason", "STOP"))
        ))));
        GeminiProvider provider = new GeminiProvider(config(), httpClient);

        assertThatThrownBy(() -> provider.generateContent(AIGenerationRequest.builder()
            .prompt("Hello")
            .build()))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("Gemini response content was missing");

        assertThat(provider.getStatus().getTotalRequests()).isEqualTo(1);
        assertThat(provider.getStatus().getSuccessfulRequests()).isZero();
        assertThat(provider.getStatus().getFailedRequests()).isEqualTo(1);
    }

    @Test
    void generateEmbeddingConvertsNumericValuesAndRecordsSuccess() {
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of(embeddingSuccessResponse()));
        GeminiProvider provider = new GeminiProvider(config(), httpClient);

        AIEmbeddingResponse response = provider.generateEmbedding(AIEmbeddingRequest.builder()
            .text("embed me")
            .build());

        assertThat(httpClient.urls()).containsExactly(
            "https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent?key=test-key"
        );
        assertThat(response.getEmbedding()).containsExactly(1.0d, 2.5d, 3.0d);
        assertThat(response.getDimensions()).isEqualTo(3);
        assertThat(provider.getStatus().getTotalRequests()).isEqualTo(1);
        assertThat(provider.getStatus().getSuccessfulRequests()).isEqualTo(1);
        assertThat(provider.getStatus().getFailedRequests()).isZero();
    }

    @Test
    void malformedEmbeddingResponseFailsClearlyAndRecordsFailure() {
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of(ResponseEntity.ok(Map.of(
            "embedding", Map.of("values", List.of("not-a-number"))
        ))));
        GeminiProvider provider = new GeminiProvider(config(), httpClient);

        assertThatThrownBy(() -> provider.generateEmbedding(AIEmbeddingRequest.builder()
            .text("embed me")
            .build()))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("contained non-numeric value");

        assertThat(provider.getStatus().getTotalRequests()).isEqualTo(1);
        assertThat(provider.getStatus().getSuccessfulRequests()).isZero();
        assertThat(provider.getStatus().getFailedRequests()).isEqualTo(1);
    }

    @Test
    void embeddingProviderUsesBatchEndpointForMultipleTexts() {
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of(batchEmbeddingSuccessResponse()));
        GeminiEmbeddingProvider provider = new GeminiEmbeddingProvider(geminiAiProviderConfig(), httpClient);
        provider.initialize();

        List<AIEmbeddingResponse> responses = provider.generateEmbeddings(List.of("first text", "second text"));

        assertThat(httpClient.urls()).containsExactly(
            "https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:batchEmbedContents?key=test-key"
        );
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> requests = (List<Map<String, Object>>) httpClient.providerRequestBody().get("requests");
        assertThat(requests).hasSize(2);
        assertThat(requests).allSatisfy(request -> assertThat(request).containsEntry("model", "models/text-embedding-004"));
        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).getEmbedding()).containsExactly(1.0d, 2.0d);
        assertThat(responses.get(1).getEmbedding()).containsExactly(3.0d, 4.0d);
    }

    private static ProviderConfig config() {
        return ProviderConfig.builder()
            .providerName("gemini")
            .apiKey("test-key")
            .baseUrl("https://generativelanguage.googleapis.com/v1beta")
            .defaultModel("gemini-2.5-flash")
            .defaultEmbeddingModel("text-embedding-004")
            .maxTokens(512)
            .temperature(0.2d)
            .timeoutSeconds(30)
            .enabled(true)
            .build();
    }

    private static AIProviderConfig geminiAiProviderConfig() {
        AIProviderConfig aiProviderConfig = new AIProviderConfig();
        AIProviderConfig.GeminiConfig gemini = aiProviderConfig.getGemini();
        gemini.setEnabled(true);
        gemini.setApiKey("test-key");
        gemini.setBaseUrl("https://generativelanguage.googleapis.com/v1beta");
        gemini.setEmbeddingModel("text-embedding-004");
        return aiProviderConfig;
    }

    private static ResponseEntity<byte[]> imageResponse() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        return ResponseEntity.ok()
            .headers(headers)
            .body("png-fixture".getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<Map> generateSuccessResponse() {
        return ResponseEntity.ok(Map.of(
            "candidates", List.of(Map.of(
                "finishReason", "STOP",
                "content", Map.of("parts", List.of(Map.of("text", "{\"documentUsage\":[{\"status\":\"USED\"}]}")))
            )),
            "usageMetadata", Map.of(
                "promptTokenCount", 11,
                "candidatesTokenCount", 6,
                "totalTokenCount", 17
            )
        ));
    }

    private static ResponseEntity<Map> embeddingSuccessResponse() {
        return ResponseEntity.ok(Map.of(
            "embedding", Map.of("values", List.of(1, 2.5d, 3L))
        ));
    }

    private static ResponseEntity<Map> batchEmbeddingSuccessResponse() {
        return ResponseEntity.ok(Map.of(
            "embeddings", List.of(
                Map.of("values", List.of(1, 2)),
                Map.of("values", List.of(3L, 4.0d))
            )
        ));
    }

    private static final class RecordingHttpClient implements HttpClient {
        private final List<ResponseEntity<?>> responses;
        private final List<String> urls = new ArrayList<>();
        private Map<String, Object> providerRequestBody;
        private int index;

        private RecordingHttpClient(List<ResponseEntity<?>> responses) {
            this.responses = responses;
        }

        @Override
        public <T> ResponseEntity<T> exchange(String url,
                                              HttpMethod method,
                                              HttpEntity<?> requestEntity,
                                              Class<T> responseType) {
            urls.add(url);
            if (Map.class.equals(responseType)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) requestEntity.getBody();
                providerRequestBody = body;
            }
            @SuppressWarnings("unchecked")
            ResponseEntity<T> casted = (ResponseEntity<T>) responses.get(index++);
            return casted;
        }

        private List<String> urls() {
            return urls;
        }

        private Map<String, Object> providerRequestBody() {
            return providerRequestBody;
        }
    }
}
