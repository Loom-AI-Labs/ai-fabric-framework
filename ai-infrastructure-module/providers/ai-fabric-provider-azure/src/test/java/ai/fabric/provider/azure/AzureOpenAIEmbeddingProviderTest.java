package ai.fabric.provider.azure;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AIEmbeddingRequest;
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

class AzureOpenAIEmbeddingProviderTest {

    @Test
    void singleEmbeddingUsesEmbeddingSpecificConnectionSettings() {
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of(embeddingResponse(List.of(1, 2.5))));
        AzureOpenAIEmbeddingProvider provider = new AzureOpenAIEmbeddingProvider(embeddingConfig(), httpClient);
        provider.initialize();

        var response = provider.generateEmbedding(AIEmbeddingRequest.builder()
            .text("hello")
            .build());

        assertThat(httpClient.urls()).containsExactly(
            "https://embedding-resource.openai.azure.com/openai/deployments/embed-deploy/embeddings?api-version=2024-10-21"
        );
        assertThat(httpClient.lastHeaders().getFirst("api-key")).isEqualTo("embedding-key");
        assertThat(response.getEmbedding()).containsExactly(1.0, 2.5);
        assertThat(response.getDimensions()).isEqualTo(2);
    }

    @Test
    void foundryEmbeddingEndpointDoesNotRequireDeploymentName() {
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of(embeddingResponse(List.of(0.25, 0.75))));
        AzureOpenAIEmbeddingProvider provider = new AzureOpenAIEmbeddingProvider(foundryConfig(), httpClient);
        provider.initialize();

        assertThat(provider.isAvailable()).isTrue();

        var response = provider.generateEmbedding(AIEmbeddingRequest.builder()
            .text("hello")
            .build());

        assertThat(httpClient.urls()).containsExactly(
            "https://example.services.ai.azure.com/models/embeddings?api-version=2024-02-15-preview"
        );
        assertThat(response.getEmbedding()).containsExactly(0.25, 0.75);
    }

    @Test
    void malformedEmbeddingVectorFailsClearly() {
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of(ResponseEntity.ok(Map.of(
            "data", List.of(Map.of("embedding", List.of("not-a-number")))
        ))));
        AzureOpenAIEmbeddingProvider provider = new AzureOpenAIEmbeddingProvider(embeddingConfig(), httpClient);
        provider.initialize();

        assertThatThrownBy(() -> provider.generateEmbedding(AIEmbeddingRequest.builder()
            .text("hello")
            .build()))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("Azure embedding vector missing contained non-numeric value");
    }

    private static AIProviderConfig embeddingConfig() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.AzureConfig azure = config.getAzure();
        azure.setEnabled(true);
        azure.setApiKey("general-key");
        azure.setEndpoint("https://llm-resource.openai.azure.com");
        azure.setDeploymentName("gpt-4.1");
        azure.setEmbeddingApiKey("embedding-key");
        azure.setEmbeddingEndpoint("https://embedding-resource.openai.azure.com");
        azure.setEmbeddingDeploymentName("embed-deploy");
        azure.setEmbeddingApiVersion("2024-10-21");
        return config;
    }

    private static AIProviderConfig foundryConfig() {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.AzureConfig azure = config.getAzure();
        azure.setEnabled(true);
        azure.setApiKey("foundry-key");
        azure.setEndpoint("https://example.services.ai.azure.com/models");
        return config;
    }

    private static ResponseEntity<Map> embeddingResponse(List<? extends Number> embedding) {
        return ResponseEntity.ok(Map.of(
            "data", List.of(Map.of("embedding", embedding))
        ));
    }

    private static final class RecordingHttpClient implements HttpClient {
        private final List<ResponseEntity<?>> responses;
        private final List<String> urls = new ArrayList<>();
        private HttpHeaders lastHeaders;
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
            ResponseEntity<T> casted = (ResponseEntity<T>) responses.get(index++);
            return casted;
        }

        private List<String> urls() {
            return urls;
        }

        private HttpHeaders lastHeaders() {
            return lastHeaders;
        }
    }
}
