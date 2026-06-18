package ai.fabric.provider.cohere;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.exception.AIServiceException;
import ai.fabric.http.HttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CohereEmbeddingProviderTest {

    @Test
    void batchEmbeddingsUseSingleCohereRequest() {
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of(ResponseEntity.ok(Map.of(
            "model", "embed-english-v3.0",
            "embeddings", List.of(
                List.of(1, 2.5),
                List.of(3, 4.5)
            )
        ))));
        CohereEmbeddingProvider provider = new CohereEmbeddingProvider(config(), httpClient);
        provider.initialize();

        var responses = provider.generateEmbeddings(List.of("first", "second"));

        assertThat(httpClient.urls()).containsExactly("https://cohere.internal/v1/embed");
        assertThat(httpClient.lastHeaders().getFirst("Authorization")).isEqualTo("Bearer test-key");
        assertThat(httpClient.lastRequestBody()).containsEntry("texts", List.of("first", "second"));
        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).getEmbedding()).containsExactly(1.0, 2.5);
        assertThat(responses.get(1).getEmbedding()).containsExactly(3.0, 4.5);
    }

    @Test
    void batchEmbeddingCountMismatchFailsClearly() {
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of(ResponseEntity.ok(Map.of(
            "embeddings", List.of(List.of(1, 2))
        ))));
        CohereEmbeddingProvider provider = new CohereEmbeddingProvider(config(), httpClient);
        provider.initialize();

        assertThatThrownBy(() -> provider.generateEmbeddings(List.of("first", "second")))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("Cohere embedding response count did not match request count");
    }

    private static AIProviderConfig config() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.CohereConfig cohere = config.getCohere();
        cohere.setEnabled(true);
        cohere.setApiKey("test-key");
        cohere.setBaseUrl("https://cohere.internal/v1/");
        cohere.setEmbeddingModel("embed-english-v3.0");
        return config;
    }

    private static final class RecordingHttpClient implements HttpClient {
        private final List<ResponseEntity<?>> responses;
        private final List<String> urls = new ArrayList<>();
        private HttpHeaders lastHeaders;
        private Map<String, Object> lastRequestBody;
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
            lastHeaders = requestEntity.getHeaders();
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) requestEntity.getBody();
            lastRequestBody = body;
            @SuppressWarnings("unchecked")
            ResponseEntity<T> casted = (ResponseEntity<T>) responses.get(index++);
            return casted;
        }

        private List<String> urls() {
            return urls;
        }

        private HttpHeaders lastHeaders() {
            return lastHeaders;
        }

        private Map<String, Object> lastRequestBody() {
            return lastRequestBody;
        }
    }
}
