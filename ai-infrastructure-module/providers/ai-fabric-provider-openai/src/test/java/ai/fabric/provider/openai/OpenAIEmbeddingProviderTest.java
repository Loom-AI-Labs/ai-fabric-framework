package ai.fabric.provider.openai;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.http.HttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAIEmbeddingProviderTest {

    @Test
    void directHttpEmbeddingUsesEmbeddingSpecificConnectionSettings() {
        RecordingHttpClient httpClient = new RecordingHttpClient(embeddingResponse());
        OpenAIEmbeddingProvider provider = new OpenAIEmbeddingProvider(openAiConfig(), httpClient);
        provider.initialize();

        var response = provider.generateEmbedding(AIEmbeddingRequest.builder()
            .text("hello")
            .build());

        assertThat(httpClient.lastUrl()).isEqualTo("https://embeddings.example.com/v1/embeddings");
        assertThat(httpClient.lastAuthorization()).isEqualTo("Bearer embedding-key");
        assertThat(httpClient.lastRequestBody())
            .containsEntry("dimensions", 512)
            .containsEntry("input", List.of("hello"));
        assertThat(response.getEmbedding()).containsExactly(0.1d, 0.2d);
        assertThat(response.getDimensions()).isEqualTo(2);
    }

    @Test
    void directHttpBatchEmbeddingUsesEmbeddingSpecificConnectionSettings() {
        RecordingHttpClient httpClient = new RecordingHttpClient(embeddingResponse());
        OpenAIEmbeddingProvider provider = new OpenAIEmbeddingProvider(openAiConfig(), httpClient);
        provider.initialize();

        var responses = provider.generateEmbeddings(List.of("hello"));

        assertThat(httpClient.lastUrl()).isEqualTo("https://embeddings.example.com/v1/embeddings");
        assertThat(httpClient.lastAuthorization()).isEqualTo("Bearer embedding-key");
        assertThat(httpClient.lastRequestBody())
            .containsEntry("dimensions", 512)
            .containsEntry("input", List.of("hello"));
        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getEmbedding()).containsExactly(0.1d, 0.2d);
    }

    private static AIProviderConfig openAiConfig() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.OpenAIConfig openai = config.getOpenai();
        openai.setApiKey("general-key");
        openai.setBaseUrl("https://general.example.com/v1");
        openai.setEmbeddingApiKey("embedding-key");
        openai.setEmbeddingBaseUrl("https://embeddings.example.com/v1");
        openai.setEmbeddingModel("text-embedding-3-small");
        openai.setEmbeddingDimensions(512);
        openai.setTimeout(30);
        return config;
    }

    private static ResponseEntity<Map> embeddingResponse() {
        return ResponseEntity.ok(Map.of(
            "model", "text-embedding-3-small",
            "data", List.of(Map.of("embedding", List.of(0.1d, 0.2d)))
        ));
    }

    private static final class RecordingHttpClient implements HttpClient {
        private final ResponseEntity<Map> response;
        private Map<String, Object> lastRequestBody;
        private String lastUrl;
        private String lastAuthorization;

        private RecordingHttpClient(ResponseEntity<Map> response) {
            this.response = response;
        }

        @Override
        public <T> ResponseEntity<T> exchange(String url, HttpMethod method, HttpEntity<?> requestEntity, Class<T> responseType) {
            lastUrl = url;
            lastAuthorization = requestEntity.getHeaders().getFirst("Authorization");
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

        private String lastAuthorization() {
            return lastAuthorization;
        }
    }
}
