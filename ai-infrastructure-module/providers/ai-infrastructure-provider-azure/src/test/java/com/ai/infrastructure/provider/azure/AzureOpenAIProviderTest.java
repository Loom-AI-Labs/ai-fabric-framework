package com.ai.infrastructure.provider.azure;

import com.ai.infrastructure.config.AIProviderConfig;
import com.ai.infrastructure.dto.AIGenerationInputPart;
import com.ai.infrastructure.dto.AIGenerationInputType;
import com.ai.infrastructure.dto.AIGenerationRequest;
import com.ai.infrastructure.http.HttpClient;
import com.ai.infrastructure.provider.ProviderConfig;
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
