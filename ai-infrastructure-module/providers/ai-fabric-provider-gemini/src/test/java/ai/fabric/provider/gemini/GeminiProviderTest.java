package ai.fabric.provider.gemini;

import ai.fabric.dto.AIGenerationInputPart;
import ai.fabric.dto.AIGenerationInputType;
import ai.fabric.dto.AIGenerationRequest;
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

class GeminiProviderTest {

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
