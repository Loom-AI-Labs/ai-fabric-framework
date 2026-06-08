package com.ai.infrastructure.connector.rest.service;

import com.ai.infrastructure.connector.rest.api.ActionExecuteRequestDto;
import com.ai.infrastructure.connector.rest.api.ActionResultDto;
import com.ai.infrastructure.connector.rest.api.TraceContextDto;
import com.ai.infrastructure.connector.rest.api.VerifiedAuthContextDto;
import com.ai.infrastructure.connector.rest.config.RestRoutingConfig;
import com.ai.infrastructure.connector.rest.template.TemplateEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RestActionExecutionServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void executePerformsRouteLevelAuthzPreflightBeforeUpstreamCall() throws Exception {
        AtomicInteger upstreamCalls = new AtomicInteger();
        AtomicReference<JsonNode> authzPayload = new AtomicReference<>();
        AtomicReference<String> upstreamSubjectHeader = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/authz/check", exchange -> {
            authzPayload.set(OBJECT_MAPPER.readTree(exchange.getRequestBody()));
            respond(exchange, 200, """
                {"granted":true,"reason":"ok","policyVersion":"p1"}
                """);
        });
        server.createContext("/orders/ORD-1/cancel", exchange -> {
            upstreamCalls.incrementAndGet();
            upstreamSubjectHeader.set(exchange.getRequestHeaders().getFirst("X-AIFABRIC-AUTH-SUBJECT-ID"));
            respond(exchange, 200, """
                {"status":"cancelled"}
                """);
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        RestRoutingConfig config = config(serverBaseUrl());
        RestActionExecutionService service = service(config);

        ActionResultDto result = service.execute(new ActionExecuteRequestDto(
            "cancel_order",
            Map.of("orderId", "ORD-1"),
            null,
            verifiedTrace()
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("status", "cancelled");
        assertThat(upstreamCalls.get()).isEqualTo(1);
        assertThat(upstreamSubjectHeader.get()).isEqualTo("customer-123");

        JsonNode payload = authzPayload.get();
        assertThat(payload).isNotNull();
        assertThat(payload.path("contractVersion").asText()).isEqualTo("AUTH_CONTEXT_V1");
        assertThat(payload.path("resourceId").asText()).isEqualTo("order:ORD-1");
        assertThat(payload.path("operationType").asText()).isEqualTo("ORDER_CANCEL");
        assertThat(payload.path("requestedScopes").get(0).asText()).isEqualTo("orders:write");
        assertThat(payload.path("requestContext").path("actionId").asText()).isEqualTo("cancel_order");
        assertThat(payload.path("requestContext").path("orderId").asText()).isEqualTo("ORD-1");
        assertThat(payload.path("authContext").path("subjectId").asText()).isEqualTo("customer-123");
        assertThat(payload.path("userId").isMissingNode()).isTrue();
        assertThat(payload.path("subjectId").isMissingNode()).isTrue();
        assertThat(payload.path("sessionId").isMissingNode()).isTrue();
        assertThat(payload.path("compatibilityAliases").isMissingNode()).isTrue();
    }

    @Test
    void executeFailsClosedWhenRouteLevelAuthzDeniesAction() throws Exception {
        AtomicInteger upstreamCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/authz/check", exchange -> respond(exchange, 200, """
            {"granted":false,"reason":"Order not owned by caller","policyVersion":"p1"}
            """));
        server.createContext("/orders/ORD-1/cancel", exchange -> {
            upstreamCalls.incrementAndGet();
            respond(exchange, 200, "{\"status\":\"cancelled\"}");
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        RestRoutingConfig config = config(serverBaseUrl());
        RestActionExecutionService service = service(config);

        ActionResultDto result = service.execute(new ActionExecuteRequestDto(
            "cancel_order",
            Map.of("orderId", "ORD-1"),
            null,
            verifiedTrace()
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("AUTHZ_DENIED");
        assertThat(result.message()).isEqualTo("Order not owned by caller");
        assertThat(upstreamCalls.get()).isZero();
    }

    @Test
    void executeRequiresVerifiedAuthContextWhenRouteLevelAuthzIsEnabled() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/authz/check", exchange -> respond(exchange, 200, """
            {"granted":true,"reason":"ok","policyVersion":"p1"}
            """));
        server.createContext("/orders/ORD-1/cancel", exchange -> respond(exchange, 200, "{\"status\":\"cancelled\"}"));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        RestRoutingConfig config = config(serverBaseUrl());
        RestActionExecutionService service = service(config);

        ActionResultDto result = service.execute(new ActionExecuteRequestDto(
            "cancel_order",
            Map.of("orderId", "ORD-1"),
            null,
            new TraceContextDto("req-2", "chat-2", null)
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("AUTHZ_DENIED");
        assertThat(result.message()).contains("Verified auth context is required");
    }

    private RestActionExecutionService service(RestRoutingConfig config) {
        TemplateEngine templateEngine = new TemplateEngine();
        RestAuthzProxyService authzProxyService = new RestAuthzProxyService(config);
        InMemoryIdempotencyStore idempotencyStore = new InMemoryIdempotencyStore(config, Clock.systemUTC());
        return new RestActionExecutionService(config, templateEngine, OBJECT_MAPPER, idempotencyStore, authzProxyService);
    }

    private RestRoutingConfig config(String baseUrl) {
        RestRoutingConfig config = new RestRoutingConfig();
        config.getConnector().getUpstream().setBaseUrl(baseUrl);
        config.getAuthz().setEnabled(true);
        config.getAuthz().setPath("/authz/check");
        config.getAuthz().getUpstream().setBaseUrl(baseUrl);

        RestRoutingConfig.ActionRoute route = new RestRoutingConfig.ActionRoute();
        route.setPath("/orders/ORD-1/cancel");
        route.setMethod("POST");
        route.getResponse().setSuccessHttpStatus(List.of(200));
        RestRoutingConfig.ActionAuthz authz = route.getAuthz();
        authz.setEnabled(true);
        authz.setResourceId("order:{{params.orderId}}");
        authz.setOperationType("ORDER_CANCEL");
        authz.setRequestedScopes(List.of("orders:write"));
        Map<String, Object> requestContext = new LinkedHashMap<>();
        requestContext.put("orderId", "{{params.orderId}}");
        authz.setRequestContext(requestContext);
        config.getActions().put("cancel_order", route);
        return config;
    }

    private TraceContextDto verifiedTrace() {
        return new TraceContextDto(
            "req-1",
            "chat-1",
            new VerifiedAuthContextDto(
                "customer-123",
                "END_USER",
                "PRIVATE_RUNTIME_BACKEND_MEDIATED",
                "TRUSTED_BACKEND",
                "customer-session-123",
                "dep-123",
                "cus-123",
                "ten-123",
                "shop-backend",
                "2026-04-07T12:00:00Z",
                List.of("chat:query", "orders:write"),
                List.of("storefront-chat")
            )
        );
    }

    private String serverBaseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        } finally {
            exchange.close();
        }
    }
}
