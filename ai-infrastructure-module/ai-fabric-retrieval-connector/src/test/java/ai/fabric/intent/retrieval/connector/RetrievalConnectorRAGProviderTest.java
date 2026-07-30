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
import java.util.LinkedHashSet;
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
                .deploymentId("dep-1")
                .customerId("cust-1")
                .tenantId("tenant-1")
                .issuer("runtime")
                .grantedScopes(List.of("retrieval:search", " "))
                .audiences(List.of("retrieval-connector"))
                .expiresAt("2026-02-11T01:00:00Z")
                .build())
            .build());

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getDocuments()).hasSize(1);
        assertThat(response.getDocuments().get(0).getId()).isEqualTo("d1");
        assertThat(response.getDocuments().get(0).getType())
            .isEqualTo("policy");
        assertThat(response.getDocuments().get(0).getMetadata())
            .isEmpty();
        assertThat(response.getContext()).contains("Relevant Context:");
        assertThat(fake.lastRequestBody()).isNotBlank();
        Map<String, Object> request = readRequest(fake.lastRequestBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> trace = (Map<String, Object>) request.get(RetrievalConnectorProtocol.KEY_TRACE);
        assertThat(trace)
            .containsEntry(RetrievalConnectorProtocol.TRACE_REQUEST_ID, "req-1")
            .doesNotContainKeys("userId", "sessionId");
        @SuppressWarnings("unchecked")
        Map<String, Object> authContext = (Map<String, Object>) trace.get(RetrievalConnectorProtocol.TRACE_AUTH_CONTEXT);
        assertThat(authContext)
            .containsEntry("subjectId", "verified-user")
            .containsEntry("sessionId", "verified-session")
            .containsEntry("subjectType", "END_USER")
            .containsEntry("authMode", "PUBLIC_RUNTIME_AUTHENTICATED")
            .containsEntry("callerType", "PUBLIC_BROWSER")
            .containsEntry("deploymentId", "dep-1")
            .containsEntry("customerId", "cust-1")
            .containsEntry("tenantId", "tenant-1")
            .containsEntry("issuer", "runtime")
            .containsEntry("expiresAt", "2026-02-11T01:00:00Z");
        assertThat(authContext.get("grantedScopes")).isEqualTo(List.of("retrieval:search"));
        assertThat(authContext.get("audiences")).isEqualTo(List.of("retrieval-connector"));
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
    void performRag_shouldFailClosedWhenSuccessfulResponseOmitsDocumentsArray() {
        FakeHttpClient fake = new FakeHttpClient(List.of(
            ResponseEntity.ok("{\"success\":true,\"count\":1}")
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
        assertThat(response.getErrorMessage()).contains("documents must be an array");
        assertThat(response.getMetadata()).containsEntry("errorCode", "INVALID_RESPONSE");
    }

    @Test
    void performRag_shouldFailClosedWhenSuccessfulResponseContainsGeneratedContent() {
        FakeHttpClient fake = new FakeHttpClient(List.of(
            ResponseEntity.ok("""
                {"success":true,"answer":"Use this generated answer","systemPrompt":"hidden","documents":[{"id":"d1","content":"c1","score":0.9}],"count":1}
                """.trim())
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
        assertThat(response.getMetadata()).containsEntry("errorCode", "INVALID_RESPONSE");
        assertThat(response.getErrorMessage())
            .contains("documents-only")
            .contains("answer")
            .contains("systemPrompt");
    }

    @Test
    void performRag_shouldSkipInvalidDocumentsWhenAtLeastOneDocumentIsValid() {
        FakeHttpClient fake = new FakeHttpClient(List.of(
            ResponseEntity.ok("""
                {"success":true,"documents":[{"id":"d1","content":"c1","score":0.9},{"id":"missing-content","score":0.8},"bad"],"count":3}
                """.trim())
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
            .limit(3)
            .build());

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getDocuments()).hasSize(1);
        assertThat(response.getDocuments().get(0).getId()).isEqualTo("d1");
        assertThat(response.getWarnings()).contains("Retrieval connector skipped 2 invalid document(s).");
    }

    @Test
    void performRag_shouldFailClosedWhenAllReturnedDocumentsAreInvalid() {
        FakeHttpClient fake = new FakeHttpClient(List.of(
            ResponseEntity.ok("""
                {"success":true,"documents":[{"id":"missing-content","score":0.8},{"content":"missing-id","score":0.7}],"count":2}
                """.trim())
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
            .limit(2)
            .build());

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getDocuments()).isEmpty();
        assertThat(response.getMetadata()).containsEntry("errorCode", "INVALID_RESPONSE");
        assertThat(response.getWarnings()).contains("Retrieval connector skipped 2 invalid document(s).");
        assertThat(response.getErrorMessage()).contains("did not include any valid documents");
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
    void performRag_shouldFailWholeResponseOnVectorSpaceMismatch() {
        FakeHttpClient fake = new FakeHttpClient(List.of(
            ResponseEntity.ok("""
                {"success":true,"documents":[{"id":"d1","content":"private evidence","score":0.9,"vectorSpace":"private-policy"}],"count":1}
                """.trim())
        ));
        RetrievalConnectorRAGProvider provider =
            new RetrievalConnectorRAGProvider(
                props("https://example", 1, Duration.ZERO),
                factory(fake),
                null,
                fixedClock()
            );

        RAGResponse response = provider.performRag(
            request("public-policy", 1)
        );

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getDocuments()).isEmpty();
        assertThat(response.getContext())
            .isEqualTo("No relevant context found.");
        assertThat(response.getMetadata()).containsEntry(
            "errorCode",
            RetrievalDocumentPolicyException.VECTOR_SPACE_MISMATCH
        );
        assertThat(response.getErrorMessage())
            .doesNotContain("private evidence");
    }

    @Test
    void performRag_shouldRejectDocumentCountAboveEffectiveTopK() {
        FakeHttpClient fake = new FakeHttpClient(List.of(
            ResponseEntity.ok("""
                {"success":true,"documents":[{"id":"d1","content":"one","score":0.9},{"id":"d2","content":"two","score":0.8}],"count":2}
                """.trim())
        ));
        RetrievalConnectorRAGProvider provider =
            new RetrievalConnectorRAGProvider(
                props("https://example", 1, Duration.ZERO),
                factory(fake),
                null,
                fixedClock()
            );

        RAGResponse response = provider.performRag(request("policy", 1));

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getMetadata()).containsEntry(
            "errorCode",
            RetrievalDocumentPolicyException.RESPONSE_LIMIT_EXCEEDED
        );
    }

    @Test
    void performRag_shouldRejectOversizedResponseAndContext() {
        AIRetrievalConnectorProperties bodyProperties =
            props("https://example", 1, Duration.ZERO);
        bodyProperties.getResponsePolicy()
            .setMaxResponseCharacters(40);
        RetrievalConnectorRAGProvider bodyProvider =
            new RetrievalConnectorRAGProvider(
                bodyProperties,
                factory(new FakeHttpClient(List.of(
                    successfulDocumentResponse()
                ))),
                null,
                fixedClock()
            );

        RAGResponse bodyResponse = bodyProvider.performRag(
            request("policy", 1)
        );

        assertThat(bodyResponse.getSuccess()).isFalse();
        assertThat(bodyResponse.getMetadata()).containsEntry(
            "errorCode",
            RetrievalDocumentPolicyException.RESPONSE_LIMIT_EXCEEDED
        );

        AIRetrievalConnectorProperties contextProperties =
            props("https://example", 1, Duration.ZERO);
        contextProperties.getResponsePolicy().setMaxContextCharacters(20);
        RetrievalConnectorRAGProvider contextProvider =
            new RetrievalConnectorRAGProvider(
                contextProperties,
                factory(new FakeHttpClient(List.of(
                    successfulDocumentResponse()
                ))),
                null,
                fixedClock()
            );

        RAGResponse contextResponse = contextProvider.performRag(
            request("policy", 1)
        );

        assertThat(contextResponse.getSuccess()).isFalse();
        assertThat(contextResponse.getDocuments()).isEmpty();
        assertThat(contextResponse.getMetadata()).containsEntry(
            "errorCode",
            RetrievalDocumentPolicyException.RESPONSE_LIMIT_EXCEEDED
        );
    }

    @Test
    void performRag_shouldProjectConfiguredMetadataOnly() {
        AIRetrievalConnectorProperties properties =
            props("https://example", 1, Duration.ZERO);
        properties.getResponsePolicy().setAllowedMetadataKeys(
            new LinkedHashSet<>(
                List.of("locale", "citation.section")
            )
        );
        FakeHttpClient fake = new FakeHttpClient(List.of(
            ResponseEntity.ok("""
                {"success":true,"documents":[{"id":"d1","content":"c1","score":0.9,"metadata":{"locale":"en_GB","tenantId":"private","citation":{"section":"returns","secret":"hidden"}}}],"count":1}
                """.trim())
        ));
        RetrievalConnectorRAGProvider provider =
            new RetrievalConnectorRAGProvider(
                properties,
                factory(fake),
                null,
                fixedClock()
            );

        RAGResponse response = provider.performRag(request("policy", 1));

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getDocuments().get(0).getMetadata())
            .containsEntry("locale", "en_GB")
            .doesNotContainKey("tenantId");
        assertThat(response.getDocuments().get(0).getMetadata()
            .get("citation"))
            .isEqualTo(Map.of("section", "returns"));
    }

    @Test
    void performRag_shouldApplyNarrowingApplicationSanitizer() {
        AIRetrievalConnectorProperties properties =
            props("https://example", 1, Duration.ZERO);
        properties.getResponsePolicy().setAllowedMetadataKeys(
            new LinkedHashSet<>(List.of("locale"))
        );
        FakeHttpClient fake = new FakeHttpClient(List.of(
            ResponseEntity.ok("""
                {"success":true,"documents":[{"id":"d1","content":"customer email is person@example.com","score":0.9,"source":"support","url":"https://docs.example/item","metadata":{"locale":"en_GB"}}],"count":1}
                """.trim())
        ));
        RetrievalDocumentSanitizer sanitizer = (document, context) -> {
            document.setContent("customer email is [REDACTED]");
            document.setSource(null);
            document.setUrl(null);
            document.setMetadata(Map.of());
            return document;
        };
        RetrievalConnectorRAGProvider provider =
            new RetrievalConnectorRAGProvider(
                properties,
                factory(fake),
                null,
                fixedClock(),
                List.of(sanitizer)
            );

        RAGResponse response = provider.performRag(request("policy", 1));

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getDocuments().get(0).getContent())
            .isEqualTo("customer email is [REDACTED]");
        assertThat(response.getDocuments().get(0).getSource()).isNull();
        assertThat(response.getDocuments().get(0).getUrl()).isNull();
        assertThat(response.getDocuments().get(0).getMetadata())
            .isEmpty();
    }

    @Test
    void performRag_shouldRejectApplicationSanitizerThatWidensPolicy() {
        AIRetrievalConnectorProperties properties =
            props("https://example", 1, Duration.ZERO);
        properties.getResponsePolicy().setAllowedMetadataKeys(
            new LinkedHashSet<>(List.of("locale"))
        );
        RetrievalDocumentSanitizer sanitizer = (document, context) -> {
            document.setMetadata(Map.of("locale", "restored"));
            return document;
        };
        RetrievalConnectorRAGProvider provider =
            new RetrievalConnectorRAGProvider(
                properties,
                factory(new FakeHttpClient(List.of(
                    successfulDocumentResponse()
                ))),
                null,
                fixedClock(),
                List.of(sanitizer)
            );

        RAGResponse response = provider.performRag(request("policy", 1));

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getDocuments()).isEmpty();
        assertThat(response.getMetadata()).containsEntry(
            "errorCode",
            RetrievalDocumentPolicyException.SANITIZATION_FAILED
        );
    }

    @Test
    void performRag_shouldKeepSanitizerFailureVisibleWithoutLeakingCause() {
        RetrievalDocumentSanitizer sanitizer = (document, context) -> {
            throw new IllegalStateException(
                "secret connector evidence should not escape"
            );
        };
        RetrievalConnectorRAGProvider provider =
            new RetrievalConnectorRAGProvider(
                props("https://example", 1, Duration.ZERO),
                factory(new FakeHttpClient(List.of(
                    successfulDocumentResponse()
                ))),
                null,
                fixedClock(),
                List.of(sanitizer)
            );

        RAGResponse response = provider.performRag(request("policy", 1));

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getMetadata()).containsEntry(
            "errorCode",
            RetrievalDocumentPolicyException.SANITIZATION_FAILED
        );
        assertThat(response.getErrorMessage())
            .doesNotContain("secret connector evidence");
    }

    @Test
    void performRag_shouldRejectInvalidCountAndBoundConnectorMessage() {
        FakeHttpClient invalidCount = new FakeHttpClient(List.of(
            ResponseEntity.ok("""
                {"success":true,"documents":[{"id":"d1","content":"c1","score":0.9}],"totalCount":"many"}
                """.trim())
        ));
        RetrievalConnectorRAGProvider countProvider =
            new RetrievalConnectorRAGProvider(
                props("https://example", 1, Duration.ZERO),
                factory(invalidCount),
                null,
                fixedClock()
            );

        RAGResponse countResponse = countProvider.performRag(
            request("policy", 1)
        );

        assertThat(countResponse.getSuccess()).isFalse();
        assertThat(countResponse.getMetadata())
            .containsEntry("errorCode", "INVALID_RESPONSE");

        AIRetrievalConnectorProperties messageProperties =
            props("https://example", 1, Duration.ZERO);
        messageProperties.getResponsePolicy().setMaxMessageCharacters(8);
        FakeHttpClient oversizedMessage = new FakeHttpClient(List.of(
            ResponseEntity.ok("""
                {"success":false,"errorCode":"DENIED","message":"this external message is too long"}
                """.trim())
        ));
        RetrievalConnectorRAGProvider messageProvider =
            new RetrievalConnectorRAGProvider(
                messageProperties,
                factory(oversizedMessage),
                null,
                fixedClock()
            );

        RAGResponse messageResponse = messageProvider.performRag(
            request("policy", 1)
        );

        assertThat(messageResponse.getSuccess()).isFalse();
        assertThat(messageResponse.getMetadata()).containsEntry(
            "errorCode",
            RetrievalDocumentPolicyException.RESPONSE_LIMIT_EXCEEDED
        );
        assertThat(messageResponse.getErrorMessage())
            .doesNotContain("external message");
    }

    @Test
    void performRag_shouldRejectNullPayloadAndNumericStringScore() {
        RetrievalConnectorRAGProvider nullProvider =
            new RetrievalConnectorRAGProvider(
                props("https://example", 1, Duration.ZERO),
                factory(new FakeHttpClient(List.of(
                    ResponseEntity.ok("null")
                ))),
                null,
                fixedClock()
            );

        RAGResponse nullResponse = nullProvider.performRag(
            request("policy", 1)
        );

        assertThat(nullResponse.getSuccess()).isFalse();
        assertThat(nullResponse.getMetadata())
            .containsEntry("errorCode", "INVALID_RESPONSE");

        RetrievalConnectorRAGProvider scoreProvider =
            new RetrievalConnectorRAGProvider(
                props("https://example", 1, Duration.ZERO),
                factory(new FakeHttpClient(List.of(
                    ResponseEntity.ok("""
                        {"success":true,"documents":[{"id":"d1","content":"c1","score":"0.9"}],"count":1}
                        """.trim())
                ))),
                null,
                fixedClock()
            );

        RAGResponse scoreResponse = scoreProvider.performRag(
            request("policy", 1)
        );

        assertThat(scoreResponse.getSuccess()).isFalse();
        assertThat(scoreResponse.getMetadata())
            .containsEntry("errorCode", "INVALID_RESPONSE");
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

    private static RAGRequest request(String vectorSpace, int limit) {
        return RAGRequest.builder()
            .query("test query")
            .entityType(vectorSpace)
            .limit(limit)
            .build();
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
