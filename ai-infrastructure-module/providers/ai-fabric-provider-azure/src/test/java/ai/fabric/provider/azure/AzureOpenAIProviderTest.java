package ai.fabric.provider.azure;

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

class AzureOpenAIProviderTest {

    @Test
    void pdfFileUrlInputsUseResponsesApiWithTransientFileData() {
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of(pdfResponse(), responsesSuccessResponse()));
        AzureOpenAIProvider provider = new AzureOpenAIProvider(config(), azureConfig(), httpClient);

        var response = provider.generateContent(AIGenerationRequest.builder()
            .prompt("Analyze attached file")
            .systemPrompt("Return JSON only.")
            .model("gpt-4.1")
            .inputParts(List.of(AIGenerationInputPart.builder()
                .type(AIGenerationInputType.FILE_URL)
                .documentId("doc-1")
                .fileName("brief.pdf")
                .contentType("application/pdf")
                .url("https://files.example.com/tmp/brief.pdf?sig=secret")
                .build()))
            .build());

        assertThat(httpClient.urls()).containsExactly(
            "https://files.example.com/tmp/brief.pdf?sig=secret",
            "https://example-resource.openai.azure.com/openai/v1/responses"
        );
        assertThat(httpClient.providerRequestBody())
            .containsEntry("max_output_tokens", 512)
            .doesNotContainKeys("max_completion_tokens", "messages");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> input = (List<Map<String, Object>>) httpClient.providerRequestBody().get("input");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) input.get(0).get("content");
        assertThat(content).anySatisfy(item -> {
            assertThat(item).containsEntry("type", "input_file");
            assertThat((String) item.get("file_data")).startsWith("data:application/pdf;base64,");
            assertThat(item).doesNotContainKey("file_url");
        });
        assertThat(response.getContent()).isEqualTo("{\"documentUsage\":[{\"status\":\"USED\"}]}");
        assertThat(response.getMetadata().toString()).contains("[REDACTED_TRANSIENT_FILE_URL]");
        assertThat(response.getMetadata().toString()).doesNotContain("sig=secret");
    }

    @Test
    void imageFileUrlInputsUseNativeImageUrlWithoutFetching() {
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of(responsesSuccessResponse()));
        AzureOpenAIProvider provider = new AzureOpenAIProvider(config(), azureConfig(), httpClient);

        provider.generateContent(AIGenerationRequest.builder()
            .prompt("Analyze attached image")
            .model("gpt-4.1")
            .inputParts(List.of(AIGenerationInputPart.builder()
                .type(AIGenerationInputType.FILE_URL)
                .documentId("img-1")
                .fileName("scan.webp")
                .contentType("image/webp")
                .url("https://files.example.com/tmp/scan.webp?sig=secret")
                .build()))
            .build());

        assertThat(httpClient.urls()).containsExactly(
            "https://example-resource.openai.azure.com/openai/v1/responses"
        );
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> input = (List<Map<String, Object>>) httpClient.providerRequestBody().get("input");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) input.get(0).get("content");
        assertThat(content).anySatisfy(item -> {
            assertThat(item).containsEntry("type", "input_image");
            assertThat(item).containsEntry("image_url", "https://files.example.com/tmp/scan.webp?sig=secret");
        });
    }

    @Test
    void stringResponseFormatAliasesAreNormalizedForChatCompletions() {
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of(chatCompletionResponse("{\"ok\":true}")));
        AzureOpenAIProvider provider = new AzureOpenAIProvider(config(), azureConfig(), httpClient);

        provider.generateContent(AIGenerationRequest.builder()
            .prompt("Return JSON")
            .parameters(Map.of("responseFormat", "json"))
            .build());

        assertThat(httpClient.providerRequestBody())
            .containsEntry("response_format", Map.of("type", "json_object"));
    }

    @Test
    void malformedChatCompletionResponseFailsClearlyAndRecordsFailure() {
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of(ResponseEntity.ok(Map.of(
            "choices", List.of(Map.of("message", "bad-shape"))
        ))));
        AzureOpenAIProvider provider = new AzureOpenAIProvider(config(), azureConfig(), httpClient);

        assertThatThrownBy(() -> provider.generateContent(AIGenerationRequest.builder()
            .prompt("Hello")
            .build()))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("Azure response choice message was not an object");

        assertThat(provider.getStatus().getTotalRequests()).isEqualTo(1);
        assertThat(provider.getStatus().getSuccessfulRequests()).isZero();
        assertThat(provider.getStatus().getFailedRequests()).isEqualTo(1);
    }

    @Test
    void numericEmbeddingValuesAreConvertedToDoublesBeforeSuccessMetrics() {
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of(embeddingResponse(List.of(1, 2.5, 3))));
        AzureOpenAIProvider provider = new AzureOpenAIProvider(config(), azureConfig(), httpClient);

        var response = provider.generateEmbedding(AIEmbeddingRequest.builder()
            .text("embed me")
            .build());

        assertThat(response.getEmbedding()).containsExactly(1.0, 2.5, 3.0);
        assertThat(provider.getStatus().getTotalRequests()).isEqualTo(1);
        assertThat(provider.getStatus().getSuccessfulRequests()).isEqualTo(1);
        assertThat(provider.getStatus().getFailedRequests()).isZero();
    }

    private static ProviderConfig config() {
        return ProviderConfig.builder()
            .providerName("azure")
            .apiKey("test-key")
            .baseUrl("https://example-resource.openai.azure.com")
            .defaultModel("gpt-4.1")
            .defaultEmbeddingModel("text-embedding-3-small")
            .maxTokens(512)
            .temperature(0.2d)
            .timeoutSeconds(30)
            .enabled(true)
            .build();
    }

    private static AIProviderConfig.AzureConfig azureConfig() {
        AIProviderConfig.AzureConfig azure = new AIProviderConfig.AzureConfig();
        azure.setEnabled(true);
        azure.setEndpoint("https://example-resource.openai.azure.com");
        azure.setDeploymentName("gpt-4.1");
        azure.setEmbeddingDeploymentName("text-embedding-3-small");
        azure.setApiVersion("2025-04-01-preview");
        return azure;
    }

    private static ResponseEntity<byte[]> pdfResponse() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        return ResponseEntity.ok()
            .headers(headers)
            .body("%PDF-1.4\nfixture".getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<Map> responsesSuccessResponse() {
        return ResponseEntity.ok(Map.of(
            "model", "gpt-4.1",
            "output_text", "{\"documentUsage\":[{\"status\":\"USED\"}]}",
            "usage", Map.of(
                "input_tokens", 11,
                "output_tokens", 6,
                "total_tokens", 17
            )
        ));
    }

    private static ResponseEntity<Map> chatCompletionResponse(String content) {
        return ResponseEntity.ok(Map.of(
            "model", "gpt-4.1",
            "choices", List.of(Map.of(
                "message", Map.of("content", content),
                "finish_reason", "stop"
            )),
            "usage", Map.of(
                "prompt_tokens", 3,
                "completion_tokens", 5,
                "total_tokens", 8
            )
        ));
    }

    private static ResponseEntity<Map> embeddingResponse(List<? extends Number> embedding) {
        return ResponseEntity.ok(Map.of(
            "data", List.of(Map.of("embedding", embedding))
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
