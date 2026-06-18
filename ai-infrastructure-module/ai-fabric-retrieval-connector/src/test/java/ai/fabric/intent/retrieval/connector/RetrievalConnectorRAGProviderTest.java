package ai.fabric.intent.retrieval.connector;

import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.http.AIHttpClientFactory;
import ai.fabric.http.HttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalConnectorRAGProviderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void performRag_shouldParseDocumentsAndContext() {
        FakeHttpClient fake = new FakeHttpClient(List.of(
            ResponseEntity.ok("""
                {"success":true,"documents":[{"id":"d1","content":"c1","score":0.9,"source":"policy","url":"https://x","vectorSpace":"policy","metadata":{"locale":"en_US"}}],"count":1,"totalCount":1,"cursor":null}
                """.trim())
        ));

        RetrievalConnectorRAGProvider provider = new RetrievalConnectorRAGProvider(
            props("https://example", 1, Duration.ZERO),
            factory(fake),
            null,
            fixedClock()
        );

        RAGResponse response = provider.performRag(RAGRequest.builder()
            .query("return policy")
            .entityType("policy")
            .limit(10)
            .requestId("req-1")
            .authContext(AIAccessSubjectContext.builder()
                .subjectId("verified-user")
                .sessionId("verified-session")
                .subjectType("END_USER")
                .authMode("PUBLIC_RUNTIME_AUTHENTICATED")
                .callerType("PUBLIC_BROWSER")
                .build())
            .build());

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getDocuments()).hasSize(1);
        assertThat(response.getDocuments().get(0).getId()).isEqualTo("d1");
        assertThat(response.getContext()).contains("Relevant Context:");
        assertThat(fake.lastRequestBody()).isNotBlank();
        Map<String, Object> request = readRequest(fake.lastRequestBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> trace = (Map<String, Object>) request.get(RetrievalConnectorProtocol.KEY_TRACE);
        assertThat(trace)
            .containsEntry(RetrievalConnectorProtocol.TRACE_REQUEST_ID, "req-1")
            .doesNotContainKeys("userId", "sessionId");
    }

    @Test
    void performRag_shouldRetryOnRetryableErrorCode() {
        FakeHttpClient fake = new FakeHttpClient(List.of(
            ResponseEntity.ok("{\"success\":false,\"errorCode\":\"SERVICE_UNAVAILABLE\",\"message\":\"temp\"}"),
            ResponseEntity.ok("{\"success\":true,\"documents\":[{\"id\":\"d1\",\"content\":\"c1\",\"score\":0.9}],\"count\":1}")
        ));

        RetrievalConnectorRAGProvider provider = new RetrievalConnectorRAGProvider(
            props("https://example", 2, Duration.ZERO),
            factory(fake),
            null,
            fixedClock()
        );

        RAGResponse response = provider.performRag(RAGRequest.builder()
            .query("q")
            .entityType("vs")
            .limit(1)
            .build());

        assertThat(response.getSuccess()).isTrue();
        assertThat(fake.callCount()).isEqualTo(2);
    }

    @Test
    void performRag_shouldClampTopKAndSendApiKeyHeader() {
        FakeHttpClient fake = new FakeHttpClient(List.of(successfulDocumentResponse()));
        AIRetrievalConnectorProperties props = props("https://example/", 1, Duration.ZERO);
        props.setMaxTopK(5);
        props.getApiKey().setHeader(" X-CUSTOM-KEY ");
        props.getApiKey().setValue(" key-123 ");
        RetrievalConnectorRAGProvider provider = new RetrievalConnectorRAGProvider(
            props,
            factory(fake),
            null,
            fixedClock()
        );

        RAGResponse response = provider.performRag(RAGRequest.builder()
            .query("q")
            .entityType("vs")
            .limit(99)
            .build());

        assertThat(response.getSuccess()).isTrue();
        assertThat(fake.lastUrl()).isEqualTo("https://example/retrieval/search");
        assertThat(fake.lastRequestHeaders().getFirst("X-CUSTOM-KEY")).isEqualTo("key-123");
        Map<String, Object> request = readRequest(fake.lastRequestBody());
        assertThat(request).containsEntry(RetrievalConnectorProtocol.KEY_TOP_K, 5);
    }

    @Test
    void performRag_shouldSignRequestsWithDefaultHmacHeaderNamesWhenConfiguredNamesAreBlank() {
        FakeHttpClient fake = new FakeHttpClient(List.of(successfulDocumentResponse()));
        AIRetrievalConnectorProperties props = props("https://example", 1, Duration.ZERO);
        props.getHmac().setSecret("secret");
        props.getHmac().setTimestampHeader(" ");
        props.getHmac().setNonceHeader("");
        props.getHmac().setSignatureHeader(null);
        RetrievalConnectorRAGProvider provider = new RetrievalConnectorRAGProvider(
            props,
            factory(fake),
            null,
            fixedClock()
        );

        RAGResponse response = provider.performRag(RAGRequest.builder()
            .query("q")
            .entityType("vs")
            .limit(1)
            .build());

        assertThat(response.getSuccess()).isTrue();
        HttpHeaders headers = fake.lastRequestHeaders();
        assertThat(headers.getFirst("X-AIFABRIC-TIMESTAMP")).isEqualTo("1770768000");
        assertThat(headers.getFirst("X-AIFABRIC-NONCE")).isNotBlank();
        assertThat(headers.getFirst("X-AIFABRIC-SIGNATURE")).isNotBlank();
    }

    @Test
    void performRag_shouldTreatNon2xxHttpStatusAsFailureEvenWhenBodyClaimsSuccess() {
        FakeHttpClient fake = new FakeHttpClient(List.of(
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("{\"success\":true,\"documents\":[{\"id\":\"d1\",\"content\":\"c1\",\"score\":0.9}],\"count\":1}")
        ));
        RetrievalConnectorRAGProvider provider = new RetrievalConnectorRAGProvider(
            props("https://example", 1, Duration.ZERO),
            factory(fake),
            null,
            fixedClock()
        );

        RAGResponse response = provider.performRag(RAGRequest.builder()
            .query("q")
            .entityType("vs")
            .limit(1)
            .build());

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getDocuments()).isEmpty();
        assertThat(response.getErrorMessage()).contains("HTTP 500");
        assertThat(response.getMetadata()).containsEntry("errorCode", "SERVICE_UNAVAILABLE");
    }

    @Test
    void performRag_shouldRetryEmptyHttp429Response() {
        FakeHttpClient fake = new FakeHttpClient(List.of(
            ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(""),
            successfulDocumentResponse()
        ));
        RetrievalConnectorRAGProvider provider = new RetrievalConnectorRAGProvider(
            props("https://example", 2, Duration.ZERO),
            factory(fake),
            null,
            fixedClock()
        );

        RAGResponse response = provider.performRag(RAGRequest.builder()
            .query("q")
            .entityType("vs")
            .limit(1)
            .build());

        assertThat(response.getSuccess()).isTrue();
        assertThat(fake.callCount()).isEqualTo(2);
    }

    @Test
    void performRag_shouldRejectBlankQueryWithoutCallingConnector() {
        FakeHttpClient fake = new FakeHttpClient(List.of());
        RetrievalConnectorRAGProvider provider = new RetrievalConnectorRAGProvider(
            props("https://example", 1, Duration.ZERO),
            factory(fake),
            null,
            fixedClock()
        );

        RAGResponse response = provider.performRag(RAGRequest.builder()
            .query(" ")
            .entityType("vs")
            .limit(1)
            .build());

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getMetadata()).containsEntry("errorCode", "INVALID_REQUEST");
        assertThat(fake.callCount()).isZero();
    }

    @Test
    void indexingOperations_shouldFailBecauseConnectorIsReadOnly() {
        RetrievalConnectorRAGProvider provider = new RetrievalConnectorRAGProvider(
            props("https://example", 1, Duration.ZERO),
            factory(new FakeHttpClient(List.of())),
            null,
            fixedClock()
        );

        assertThatThrownBy(() -> provider.indexContent("product", "p1", "content", Map.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("read-only");
        assertThatThrownBy(() -> provider.removeContent("product", "p1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("read-only");
    }

    private static Map<String, Object> readRequest(String body) {
        try {
            return OBJECT_MAPPER.readValue(body, Map.class);
        } catch (Exception ex) {
            throw new AssertionError("Failed to parse retrieval connector request JSON", ex);
        }
    }

    private static AIRetrievalConnectorProperties props(String baseUrl, int maxAttempts, Duration initialBackoff) {
        AIRetrievalConnectorProperties props = new AIRetrievalConnectorProperties();
        props.setEnabled(true);
        props.setBaseUrl(baseUrl);
        props.setMaxAttempts(maxAttempts);
        props.setInitialBackoff(initialBackoff);
        props.setConnectTimeout(Duration.ofMillis(1));
        props.setReadTimeout(Duration.ofMillis(1));
        return props;
    }

    private static ResponseEntity<String> successfulDocumentResponse() {
        return ResponseEntity.ok("{\"success\":true,\"documents\":[{\"id\":\"d1\",\"content\":\"c1\",\"score\":0.9}],\"count\":1}");
    }

    private static AIHttpClientFactory factory(HttpClient client) {
        return new AIHttpClientFactory() {
            @Override
            public HttpClient create() {
                return client;
            }

            @Override
            public HttpClient create(Duration connectTimeout, Duration readTimeout) {
                return client;
            }
        };
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-02-11T00:00:00Z"), ZoneOffset.UTC);
    }

    private static final class FakeHttpClient implements HttpClient {
        private final List<ResponseEntity<String>> responses;
        private final AtomicInteger calls = new AtomicInteger(0);
        private volatile String lastRequestBody;
        private volatile HttpHeaders lastRequestHeaders = HttpHeaders.EMPTY;
        private volatile String lastUrl;

        private FakeHttpClient(List<ResponseEntity<String>> responses) {
            this.responses = responses != null ? new ArrayList<>(responses) : List.of();
        }

        @Override
        public <T> ResponseEntity<T> exchange(String url, HttpMethod method, HttpEntity<?> requestEntity, Class<T> responseType) {
            int idx = calls.getAndIncrement();
            lastUrl = url;
            Object body = requestEntity != null ? requestEntity.getBody() : null;
            lastRequestBody = body instanceof String s ? s : null;
            lastRequestHeaders = requestEntity != null ? requestEntity.getHeaders() : HttpHeaders.EMPTY;
            @SuppressWarnings("unchecked")
            ResponseEntity<T> casted = (ResponseEntity<T>) responses.get(Math.min(idx, responses.size() - 1));
            return casted;
        }

        int callCount() {
            return calls.get();
        }

        String lastRequestBody() {
            return lastRequestBody;
        }

        HttpHeaders lastRequestHeaders() {
            return lastRequestHeaders;
        }

        String lastUrl() {
            return lastUrl;
        }
    }
}
