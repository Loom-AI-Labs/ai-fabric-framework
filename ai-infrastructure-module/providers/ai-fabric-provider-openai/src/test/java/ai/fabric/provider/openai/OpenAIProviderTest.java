package ai.fabric.provider.openai;

import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationInputPart;
import ai.fabric.dto.AIGenerationInputType;
import ai.fabric.http.HttpClient;
import ai.fabric.provider.ProviderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAIProviderTest {

    @Test
    void omitsNullOptionalGenerationFields() {
        RecordingHttpClient httpClient = new RecordingHttpClient(successResponse());
        OpenAIProvider provider = new OpenAIProvider(config(null, null), httpClient);

        provider.generateContent(AIGenerationRequest.builder()
            .prompt("Classify this request")
            .systemPrompt("Return JSON only.")
            .model("gpt-5.4-nano")
            .build());

        assertThat(httpClient.lastRequestBody())
            .doesNotContainKeys("max_completion_tokens", "max_tokens", "temperature");
    }

    @Test
    void keepsConfiguredOptionalGenerationFields() {
        RecordingHttpClient httpClient = new RecordingHttpClient(successResponse());
        OpenAIProvider provider = new OpenAIProvider(config(512, 0.2d), httpClient);

        provider.generateContent(AIGenerationRequest.builder()
            .prompt("Classify this request")
            .systemPrompt("Return JSON only.")
            .model("gpt-5.4-nano")
            .build());

        assertThat(httpClient.lastRequestBody())
            .containsEntry("max_completion_tokens", 512)
            .doesNotContainKey("max_tokens")
            .containsEntry("temperature", 0.2d);
    }

    @Test
    void fileUrlInputsUseResponsesApiAndRedactedDescriptors() {
        RecordingHttpClient httpClient = new RecordingHttpClient(responsesSuccessResponse());
        OpenAIProvider provider = new OpenAIProvider(config(512, 0.2d), httpClient);

        var response = provider.generateContent(AIGenerationRequest.builder()
            .prompt("Analyze attached file")
            .systemPrompt("Return JSON only.")
            .model("gpt-5.4-nano")
            .inputParts(List.of(AIGenerationInputPart.builder()
                .type(AIGenerationInputType.FILE_URL)
                .documentId("doc-1")
                .fileName("brief.pdf")
                .contentType("application/pdf")
                .url("https://files.example.com/tmp/brief.pdf?sig=secret")
                .build()))
            .build());

        assertThat(httpClient.lastUrl()).endsWith("/responses");
        assertThat(httpClient.lastRequestBody())
            .containsEntry("max_output_tokens", 512)
            .doesNotContainKeys("max_completion_tokens", "messages");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> input = (List<Map<String, Object>>) httpClient.lastRequestBody().get("input");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) input.get(0).get("content");
        assertThat(content).anySatisfy(item -> {
            assertThat(item).containsEntry("type", "input_file");
            assertThat(item).containsEntry("file_url", "https://files.example.com/tmp/brief.pdf?sig=secret");
        });
        assertThat(response.getContent()).isEqualTo("{\"documentUsage\":[{\"status\":\"USED\"}]}");
        assertThat(response.getMetadata().toString()).contains("[REDACTED_TRANSIENT_FILE_URL]");
        assertThat(response.getMetadata().toString()).doesNotContain("sig=secret");
    }

    @Test
    void imageFileUrlInputsUseResponsesImageBlocks() {
        RecordingHttpClient httpClient = new RecordingHttpClient(responsesSuccessResponse());
        OpenAIProvider provider = new OpenAIProvider(config(512, 0.2d), httpClient);

        provider.generateContent(AIGenerationRequest.builder()
            .prompt("Analyze attached image")
            .model("gpt-5.4-nano")
            .inputParts(List.of(AIGenerationInputPart.builder()
                .type(AIGenerationInputType.FILE_URL)
                .documentId("img-1")
                .fileName("scan.png")
                .contentType("image/png")
                .url("https://files.example.com/tmp/scan.png?sig=secret")
                .build()))
            .build());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> input = (List<Map<String, Object>>) httpClient.lastRequestBody().get("input");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) input.get(0).get("content");
        assertThat(content).anySatisfy(item -> {
            assertThat(item).containsEntry("type", "input_image");
            assertThat(item).containsEntry("image_url", "https://files.example.com/tmp/scan.png?sig=secret");
        });
    }

    @Test
    void unsupportedFileUrlContentTypeFailsClosed() {
        RecordingHttpClient httpClient = new RecordingHttpClient(responsesSuccessResponse());
        OpenAIProvider provider = new OpenAIProvider(config(512, 0.2d), httpClient);

        var response = provider.generateContent(AIGenerationRequest.builder()
            .prompt("Analyze attached file")
            .model("gpt-5.4-nano")
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
    }

    private static ProviderConfig config(Integer maxTokens, Double temperature) {
        return ProviderConfig.builder()
            .providerName("openai")
            .apiKey("test-key")
            .baseUrl("https://api.openai.com/v1")
            .defaultModel("gpt-4o-mini")
            .defaultEmbeddingModel("text-embedding-3-small")
            .maxTokens(maxTokens)
            .temperature(temperature)
            .timeoutSeconds(30)
            .enabled(true)
            .build();
    }

    private static ResponseEntity<Map> successResponse() {
        return ResponseEntity.ok(Map.of(
            "model", "gpt-5.4-nano",
            "choices", List.of(Map.of(
                "message", Map.of("content", "{\"ok\":true}"),
                "finish_reason", "stop"
            )),
            "usage", Map.of(
                "prompt_tokens", 10,
                "completion_tokens", 5,
                "total_tokens", 15
            )
        ));
    }

    private static ResponseEntity<Map> responsesSuccessResponse() {
        return ResponseEntity.ok(Map.of(
            "model", "gpt-5.4-nano",
            "output_text", "{\"documentUsage\":[{\"status\":\"USED\"}]}",
            "usage", Map.of(
                "input_tokens", 11,
                "output_tokens", 6,
                "total_tokens", 17
            )
        ));
    }

    private static final class RecordingHttpClient implements HttpClient {
        private final ResponseEntity<Map> response;
        private Map<String, Object> lastRequestBody;
        private String lastUrl;

        private RecordingHttpClient(ResponseEntity<Map> response) {
            this.response = response;
        }

        @Override
        public <T> ResponseEntity<T> exchange(String url, HttpMethod method, HttpEntity<?> requestEntity, Class<T> responseType) {
            lastUrl = url;
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

        private String lastUrl() {
            return lastUrl;
        }
    }
}
