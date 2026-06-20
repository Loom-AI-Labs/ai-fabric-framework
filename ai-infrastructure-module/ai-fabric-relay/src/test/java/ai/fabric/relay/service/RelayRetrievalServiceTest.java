package ai.fabric.relay.service;

import ai.fabric.relay.api.RetrievalSearchRequestDto;
import ai.fabric.relay.api.RetrievalSearchResponseDto;
import ai.fabric.relay.api.TraceContextDto;
import ai.fabric.relay.api.VerifiedAuthContextDto;
import ai.fabric.relay.config.RelayProperties;
import ai.fabric.relay.error.RelayRequestRejectedException;
import ai.fabric.relay.forward.ForwardingClient;
import ai.fabric.relay.forward.ForwardingResponse;
import ai.fabric.relay.ratelimit.FixedWindowRateLimiter;
import ai.fabric.relay.store.InMemoryRelayKeyValueStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelayRetrievalServiceTest {

    @Test
    void search_shouldPassThroughValidDocumentsOnlyResponse() {
        StubForwardingClient forwardingClient = forwardingClient("""
            {"success":true,"documents":[{"id":"d1","content":"c1","score":0.9,"source":"policy"}],"count":1,"totalCount":1,"cursor":null}
            """);
        RelayRetrievalService service = service(forwardingClient);

        RetrievalSearchResponseDto response = service.search(request());

        assertThat(response.success()).isTrue();
        assertThat(response.documents()).hasSize(1);
        assertThat(response.documents().get(0).id()).isEqualTo("d1");
        assertThat(response.count()).isEqualTo(1);
        assertThat(forwardingClient.lastUri()).isEqualTo(URI.create("http://internal.example/retrieval/search"));
    }

    @Test
    void search_shouldFailClosedWhenInternalRetrievalReturnsGeneratedContent() {
        RelayRetrievalService service = service(forwardingClient("""
            {"success":true,"answer":"Generated outside AI Fabric","systemPrompt":"hidden","documents":[{"id":"d1","content":"c1","score":0.9}],"count":1}
            """));

        RetrievalSearchResponseDto response = service.search(request());

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("INVALID_RESPONSE");
        assertThat(response.message())
            .contains("documents-only")
            .contains("answer")
            .contains("systemPrompt");
        assertThat(response.documents()).isEmpty();
    }

    @Test
    void search_shouldFailClosedWhenNon2xxBodyClaimsSuccess() {
        RelayRetrievalService service = service(forwardingClient(500, """
            {"success":true,"documents":[{"id":"d1","content":"c1","score":0.9}],"count":1}
            """));

        RetrievalSearchResponseDto response = service.search(request());

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(response.message()).contains("HTTP 500");
        assertThat(response.documents()).isEmpty();
    }

    @Test
    void search_shouldPreserveStructuredFailureForNon2xxResponses() {
        RelayRetrievalService service = service(forwardingClient(403, """
            {"success":false,"errorCode":"FORBIDDEN","message":"No access.","documents":[],"count":0}
            """));

        RetrievalSearchResponseDto response = service.search(request());

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(response.message()).isEqualTo("No access.");
        assertThat(response.documents()).isEmpty();
    }

    @Test
    void search_shouldMapEmptyHttp429ResponseToRateLimited() {
        RelayRetrievalService service = service(forwardingClient(429, ""));

        RetrievalSearchResponseDto response = service.search(request());

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("RATE_LIMITED");
        assertThat(response.message()).contains("HTTP 429");
    }

    @Test
    void search_shouldFailClosedWhenSuccessfulResponseOmitsDocumentsArray() {
        RelayRetrievalService service = service(forwardingClient("{\"success\":true,\"count\":1}"));

        RetrievalSearchResponseDto response = service.search(request());

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("INVALID_RESPONSE");
        assertThat(response.message()).contains("documents must be an array");
    }

    @Test
    void search_shouldSkipInvalidDocumentsWhenAtLeastOneDocumentIsValid() {
        RelayRetrievalService service = service(forwardingClient("""
            {"success":true,"documents":[{"id":"d1","content":"c1","score":0.9},{"id":"missing-content","score":0.8}],"count":2,"totalCount":2}
            """));

        RetrievalSearchResponseDto response = service.search(request());

        assertThat(response.success()).isTrue();
        assertThat(response.documents()).hasSize(1);
        assertThat(response.documents().get(0).id()).isEqualTo("d1");
        assertThat(response.count()).isEqualTo(1);
        assertThat(response.totalCount()).isEqualTo(2);
    }

    @Test
    void search_shouldFailClosedWhenAllReturnedDocumentsAreInvalid() {
        RelayRetrievalService service = service(forwardingClient("""
            {"success":true,"documents":[{"id":"missing-content","score":0.8},{"content":"missing-id","score":0.7}],"count":2}
            """));

        RetrievalSearchResponseDto response = service.search(request());

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("INVALID_RESPONSE");
        assertThat(response.message()).contains("did not include any valid documents");
        assertThat(response.documents()).isEmpty();
    }

    @Test
    void search_shouldRejectUnserializableRequestInsteadOfForwardingEmptyJson() {
        StubForwardingClient forwardingClient = forwardingClient("""
            {"success":true,"documents":[{"id":"d1","content":"c1","score":0.9}],"count":1}
            """);
        RelayRetrievalService service = service(forwardingClient);
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("self", filters);
        RetrievalSearchRequestDto badRequest = new RetrievalSearchRequestDto(
            "return policy",
            "policy",
            5,
            null,
            filters,
            request().trace()
        );

        assertThatThrownBy(() -> service.search(badRequest))
            .isInstanceOf(RelayRequestRejectedException.class)
            .satisfies(ex -> {
                RelayRequestRejectedException rejected = (RelayRequestRejectedException) ex;
                assertThat(rejected.getStatus().value()).isEqualTo(400);
                assertThat(rejected.getErrorCode()).isEqualTo("INVALID_REQUEST");
            });
        assertThat(forwardingClient.callCount()).isZero();
    }

    private static RelayRetrievalService service(StubForwardingClient forwardingClient) {
        RelayProperties properties = new RelayProperties();
        properties.getAudit().setEnabled(false);
        properties.getRouting().getRetrieval().setUrl("http://internal.example/retrieval/search");
        FixedWindowRateLimiter rateLimiter = new FixedWindowRateLimiter(
            properties,
            new InMemoryRelayKeyValueStore("relay-test:")
        );
        return new RelayRetrievalService(
            properties,
            new ObjectMapper(),
            forwardingClient,
            rateLimiter,
            new AuditLogger(properties)
        );
    }

    private static RetrievalSearchRequestDto request() {
        return new RetrievalSearchRequestDto(
            "return policy",
            "policy",
            5,
            null,
            Map.of("locale", "en_US"),
            new TraceContextDto(
                "req-1",
                "chat-1",
                new VerifiedAuthContextDto(
                    "subject-1",
                    "END_USER",
                    "PUBLIC_RUNTIME_AUTHENTICATED",
                    "PUBLIC_BROWSER",
                    "session-1",
                    "dep-1",
                    "customer-1",
                    "tenant-1",
                    "runtime",
                    null,
                    java.util.List.of("retrieval:search"),
                    java.util.List.of("relay")
                )
            )
        );
    }

    private static StubForwardingClient forwardingClient(String body) {
        return forwardingClient(200, body);
    }

    private static StubForwardingClient forwardingClient(int statusCode, String body) {
        return new StubForwardingClient(new ForwardingResponse(statusCode, body));
    }

    private static final class StubForwardingClient extends ForwardingClient {
        private final ForwardingResponse response;
        private URI lastUri;
        private int callCount;

        private StubForwardingClient(ForwardingResponse response) {
            super(new RelayProperties());
            this.response = response;
        }

        @Override
        public ForwardingResponse execute(URI uri,
                                          String method,
                                          String jsonBody,
                                          Map<String, String> headers,
                                          Duration timeout) {
            this.callCount++;
            this.lastUri = uri;
            return response;
        }

        private URI lastUri() {
            return lastUri;
        }

        private int callCount() {
            return callCount;
        }
    }
}
