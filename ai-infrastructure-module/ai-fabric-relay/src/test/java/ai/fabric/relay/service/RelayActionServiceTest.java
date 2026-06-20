package ai.fabric.relay.service;

import ai.fabric.relay.api.ActionExecuteRequestDto;
import ai.fabric.relay.api.ActionResultDto;
import ai.fabric.relay.api.TraceContextDto;
import ai.fabric.relay.api.VerifiedAuthContextDto;
import ai.fabric.relay.config.RelayProperties;
import ai.fabric.relay.forward.ForwardingClient;
import ai.fabric.relay.forward.ForwardingResponse;
import ai.fabric.relay.ratelimit.FixedWindowRateLimiter;
import ai.fabric.relay.store.InMemoryRelayKeyValueStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RelayActionServiceTest {

    @Test
    void execute_shouldPassThroughValidActionResult() {
        StubForwardingClient forwardingClient = forwardingClient(200, """
            {"success":true,"message":"ok","data":{"value":42}}
            """);
        RelayActionService service = service(forwardingClient);

        ActionResultDto response = service.execute(request());

        assertThat(response.success()).isTrue();
        assertThat(response.message()).isEqualTo("ok");
        assertThat(response.data()).containsEntry("value", 42);
        assertThat(forwardingClient.lastUri()).isEqualTo(URI.create("http://internal.example/actions/ping"));
    }

    @Test
    void execute_shouldFailClosedWhenNon2xxBodyClaimsSuccess() {
        RelayActionService service = service(forwardingClient(500, """
            {"success":true,"message":"not really ok","data":{"value":42}}
            """));

        ActionResultDto response = service.execute(request());

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(response.message()).contains("HTTP 500");
        assertThat(response.data()).isNull();
    }

    @Test
    void execute_shouldPreserveStructuredFailureForNon2xxResponses() {
        RelayActionService service = service(forwardingClient(403, """
            {"success":false,"message":"No access.","errorCode":"FORBIDDEN","data":{"resource":"order-1"}}
            """));

        ActionResultDto response = service.execute(request());

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(response.message()).isEqualTo("No access.");
        assertThat(response.data()).containsEntry("resource", "order-1");
    }

    @Test
    void execute_shouldMapEmptyHttp429ResponseToRateLimited() {
        RelayActionService service = service(forwardingClient(429, ""));

        ActionResultDto response = service.execute(request());

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("RATE_LIMITED");
        assertThat(response.message()).contains("HTTP 429");
    }

    @Test
    void execute_shouldFailClosedWithInvalidResponseForMalformedSuccessfulPayload() {
        RelayActionService service = service(forwardingClient(200, """
            {"success":true,"message":"ok","data":["not","an","object"]}
            """));

        ActionResultDto response = service.execute(request());

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("INVALID_RESPONSE");
        assertThat(response.message()).contains("invalid ActionResult data");
        assertThat(response.data()).isNull();
    }

    @Test
    void execute_shouldPreserveHandledFailureWhenFailureDataIsMalformed() {
        RelayActionService service = service(forwardingClient(200, """
            {"success":false,"message":"No access.","errorCode":"FORBIDDEN","data":["ignored"]}
            """));

        ActionResultDto response = service.execute(request());

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(response.message()).isEqualTo("No access.");
        assertThat(response.data()).isNull();
    }

    @Test
    void execute_shouldPreserveNon2xxHandledFailureWhenFailureDataIsMalformed() {
        RelayActionService service = service(forwardingClient(403, """
            {"success":false,"message":"No access.","errorCode":"FORBIDDEN","data":["ignored"]}
            """));

        ActionResultDto response = service.execute(request());

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(response.message()).isEqualTo("No access.");
        assertThat(response.data()).isNull();
    }

    private static RelayActionService service(StubForwardingClient forwardingClient) {
        RelayProperties properties = new RelayProperties();
        properties.getAudit().setEnabled(false);
        properties.getIdempotency().setEnabled(false);
        RelayProperties.Route route = new RelayProperties.Route();
        route.setUrl("http://internal.example/actions/ping");
        route.setMethod("POST");
        properties.getRouting().getActions().put("ping", route);
        FixedWindowRateLimiter rateLimiter = new FixedWindowRateLimiter(
            properties,
            new InMemoryRelayKeyValueStore("relay-action-test:")
        );
        ObjectMapper objectMapper = new ObjectMapper();
        return new RelayActionService(
            properties,
            objectMapper,
            forwardingClient,
            rateLimiter,
            new IdempotencyStore(properties, objectMapper, new InMemoryRelayKeyValueStore("relay-action-idem-test:")),
            new AuditLogger(properties)
        );
    }

    private static ActionExecuteRequestDto request() {
        return new ActionExecuteRequestDto(
            "ping",
            Map.of("input", "hello"),
            null,
            new TraceContextDto(
                "req-action-1",
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
                    java.util.List.of("actions:execute"),
                    java.util.List.of("relay")
                )
            )
        );
    }

    private static StubForwardingClient forwardingClient(int statusCode, String body) {
        return new StubForwardingClient(new ForwardingResponse(statusCode, body));
    }

    private static final class StubForwardingClient extends ForwardingClient {
        private final ForwardingResponse response;
        private URI lastUri;

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
            this.lastUri = uri;
            return response;
        }

        private URI lastUri() {
            return lastUri;
        }
    }
}
