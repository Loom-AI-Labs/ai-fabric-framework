package ai.fabric.intent.action.connector;

import ai.fabric.http.OutboundHttpExecutionRequest;
import ai.fabric.http.OutboundHttpExecutionResponse;
import ai.fabric.http.OutboundHttpExecutor;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionListPayload;
import ai.fabric.intent.action.ActionObjectPayload;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationContextMetadataKeys;
import ai.fabric.intent.orchestration.attachment.OrchestrationAttachment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ActionConnectorExecutorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void execute_shouldParseObjectPayloadSuccess() {
        FakeHttpClient fake = new FakeHttpClient(List.of(
            new OutboundHttpExecutionResponse(200, "{\"success\":true,\"message\":\"ok\",\"data\":{\"orderRef\":\"PO-1\"}}", Map.of())
        ));

        ActionConnectorExecutor executor = new ActionConnectorExecutor(
            connectorProps("https://example", 1, Duration.ZERO),
            fake,
            null,
            fixedClock()
        );

        ActionResult result = executor.execute(
            "create_purchase_order",
            ActionAccessMode.WRITE_ONLY,
            Map.of("sku", "SKU-1", "quantity", 1),
            testContext()
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("ok");
        assertThat(result.getData()).isInstanceOf(ActionObjectPayload.class);
        assertThat(result.getData().toMap()).containsEntry("orderRef", "PO-1");
        assertThat(fake.lastRequestBody()).isNotBlank();
        Map<String, Object> request = readRequest(fake.lastRequestBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> trace = (Map<String, Object>) request.get(ActionConnectorProtocol.KEY_TRACE);
        assertThat(trace)
            .containsEntry(ActionConnectorProtocol.TRACE_REQUEST_ID, "r1")
            .containsEntry(ActionConnectorProtocol.TRACE_CONVERSATION_ID, "c1")
            .containsEntry(ActionConnectorProtocol.TRACE_USER_ID, "user@example.com")
            .containsEntry(ActionConnectorProtocol.TRACE_SESSION_ID, "s1");
        @SuppressWarnings("unchecked")
        Map<String, Object> authContext = (Map<String, Object>) trace.get(ActionConnectorProtocol.TRACE_AUTH_CONTEXT);
        assertThat(authContext)
            .containsEntry("subjectId", "user@example.com")
            .containsEntry("subjectType", "INTERNAL_PLATFORM_USER")
            .containsEntry("authMode", "PLATFORM_PROXY_SESSION")
            .containsEntry("callerType", "PLATFORM_PROXY")
            .containsEntry("sessionId", "s1")
            .containsEntry("deploymentId", "dep-1")
            .containsEntry("customerId", "cust-1")
            .containsEntry("tenantId", "ten-1")
            .containsEntry("issuer", "platform-poc:SESSION");
    }

    @Test
    void execute_shouldParseListPayloadWithCursorAndTotalCount() {
        FakeHttpClient fake = new FakeHttpClient(List.of(
            new OutboundHttpExecutionResponse(
                200,
                """
                    {"success":true,"message":"Products","data":{"_count":2,"_items":[{"id":"p1"},{"id":"p2"}],"_totalCount":10,"_cursor":"abc","note":"x"}}
                    """.trim(),
                Map.of()
            )
        ));

        ActionConnectorExecutor executor = new ActionConnectorExecutor(
            connectorProps("https://example", 1, Duration.ZERO),
            fake,
            null,
            fixedClock()
        );

        ActionResult result = executor.execute(
            "list_products",
            ActionAccessMode.READ,
            Map.of("q", "sony"),
            testContext()
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isInstanceOf(ActionListPayload.class);
        Map<String, Object> map = result.getData().toMap();
        assertThat(map).containsEntry("_count", 2);
        assertThat(map).containsEntry("_totalCount", 10L);
        assertThat(map).containsEntry("_cursor", "abc");
        assertThat(map).containsEntry("note", "x");
    }

    @Test
    void execute_shouldSignRequestsWithDefaultHmacHeaderNamesWhenConfiguredNamesAreBlank() {
        FakeHttpClient fake = new FakeHttpClient(List.of(
            new OutboundHttpExecutionResponse(200, "{\"success\":true,\"message\":\"ok\",\"data\":{}}", Map.of())
        ));
        AIActionConnectorProperties props = connectorProps("https://example", 1, Duration.ZERO);
        props.getHmac().setSecret("secret");
        props.getHmac().setTimestampHeader(" ");
        props.getHmac().setNonceHeader("");
        props.getHmac().setSignatureHeader(null);
        ActionConnectorExecutor executor = new ActionConnectorExecutor(
            props,
            fake,
            null,
            fixedClock()
        );

        ActionResult result = executor.execute(
            "read_order",
            ActionAccessMode.READ,
            Map.of("orderId", "order-1"),
            testContext()
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(fake.lastRequest().headers()).containsKeys(
            "X-AIFABRIC-TIMESTAMP",
            "X-AIFABRIC-NONCE",
            "X-AIFABRIC-SIGNATURE"
        );
        assertThat(fake.lastRequest().headers().get("X-AIFABRIC-TIMESTAMP")).isEqualTo("1770768000");
    }

    @Test
    void execute_shouldIncludeRuntimeActionConfigInTrace() {
        FakeHttpClient fake = new FakeHttpClient(List.of(
            new OutboundHttpExecutionResponse(200, "{\"success\":true,\"message\":\"ok\",\"data\":{}}", Map.of())
        ));
        AIActionConnectorProperties props = connectorProps("https://example", 1, Duration.ZERO);
        props.getMcpGateway().setBaseUrl("https://mcp-gateway.internal");
        props.getMcpGateway().setApiKey("gateway-secret");
        ActionConnectorExecutor executor = new ActionConnectorExecutor(
            props,
            fake,
            null,
            fixedClock()
        );

        executor.execute(
            "inventory_search",
            ActionAccessMode.READ,
            Map.of("query", "bag"),
            testContext(),
            Map.of(
                "adapterType", "mcp-tool",
                "execution", Map.of("mcp", Map.of("serverRef", "inventory-mcp", "toolName", "inventory.search")),
                "mcpServers", Map.of("inventory-mcp", Map.of("endpointUrl", "https://inventory.example/mcp")),
                "params", List.of(Map.of(
                    "name", "selected_items",
                    "type", "ARRAY",
                    "batchTargets", true,
                    "items", Map.of(
                        "type", "OBJECT",
                        "requiredProperties", List.of("product_variant_id", "quantity"),
                        "properties", Map.of(
                            "product_variant_id", Map.of("type", "STRING", "required", true),
                            "quantity", Map.of("type", "INTEGER", "required", true)
                        )
                    )
                ))
            )
        );

        Map<String, Object> request = readRequest(fake.lastRequestBody());
        assertThat(fake.lastRequest().url()).isEqualTo("https://mcp-gateway.internal/api/internal/mcp/actions/execute");
        assertThat(fake.lastRequest().headers()).containsEntry("X-MCP-GATEWAY-API-KEY", "gateway-secret");
        @SuppressWarnings("unchecked")
        Map<String, Object> trace = (Map<String, Object>) request.get(ActionConnectorProtocol.KEY_TRACE);
        @SuppressWarnings("unchecked")
        Map<String, Object> actionConfig = (Map<String, Object>) trace.get("actionConfig");
        assertThat(actionConfig).containsEntry("adapterType", "mcp-tool");
        assertThat(actionConfig).containsKeys("execution", "mcpServers", "params");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> params = (List<Map<String, Object>>) actionConfig.get("params");
        assertThat(params.getFirst()).containsEntry("name", "selected_items");
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) params.getFirst().get("items");
        assertThat(items).containsEntry("requiredProperties", List.of("product_variant_id", "quantity"));
    }

    @Test
    void execute_shouldForwardReferencedMcpSecretValuesFromRuntimeEnvironment() {
        String secretRef = "MCP_SECRET_VENDOR_TOKEN";
        String oldValue = System.getProperty(secretRef);
        System.setProperty(secretRef, "vendor-secret-value");
        try {
            FakeHttpClient fake = new FakeHttpClient(List.of(
                new OutboundHttpExecutionResponse(200, "{\"success\":true,\"message\":\"ok\",\"data\":{}}", Map.of())
            ));
            AIActionConnectorProperties props = connectorProps("https://example", 1, Duration.ZERO);
            props.getMcpGateway().setBaseUrl("https://mcp-gateway.internal");
            props.getMcpGateway().setApiKey("gateway-secret");
            ActionConnectorExecutor executor = new ActionConnectorExecutor(
                props,
                fake,
                null,
                fixedClock()
            );

            executor.execute(
                "inventory_search",
                ActionAccessMode.READ,
                Map.of("query", "bag"),
                testContext(),
                Map.of(
                    "adapterType", "mcp-tool",
                    "execution", Map.of("mcp", Map.of(
                        "serverRef", "inventory-mcp",
                        "toolName", "inventory.search",
                        "auth", Map.of("mode", "API_KEY_HEADER_SECRET", "secretRef", secretRef)
                    )),
                    "mcpServers", Map.of("inventory-mcp", Map.of(
                        "endpointUrl", "https://inventory.example/mcp",
                        "auth", Map.of("mode", "API_KEY_HEADER_SECRET", "secretRef", secretRef)
                    ))
                )
            );

            Map<String, Object> request = readRequest(fake.lastRequestBody());
            @SuppressWarnings("unchecked")
            Map<String, Object> trace = (Map<String, Object>) request.get(ActionConnectorProtocol.KEY_TRACE);
            @SuppressWarnings("unchecked")
            Map<String, Object> secretValues = (Map<String, Object>) trace.get("mcpSecretValues");
            assertThat(secretValues).containsEntry(secretRef, "vendor-secret-value");
        } finally {
            if (oldValue == null) {
                System.clearProperty(secretRef);
            } else {
                System.setProperty(secretRef, oldValue);
            }
        }
    }

    @Test
    void execute_shouldPromoteStorefrontShopDomainAttachmentIntoMcpTrace() {
        FakeHttpClient fake = new FakeHttpClient(List.of(
            new OutboundHttpExecutionResponse(200, "{\"success\":true,\"message\":\"ok\",\"data\":{}}", Map.of())
        ));
        AIActionConnectorProperties props = connectorProps("https://example", 1, Duration.ZERO);
        props.getMcpGateway().setBaseUrl("https://mcp-gateway.internal");
        props.getMcpGateway().setApiKey("gateway-secret");
        ActionConnectorExecutor executor = new ActionConnectorExecutor(
            props,
            fake,
            null,
            fixedClock()
        );

        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user@example.com")
            .sessionId("s1")
            .conversationId("c1")
            .requestId("r1")
            .attachments(List.of(OrchestrationAttachment.builder()
                .source("commerce-storefront-context")
                .metadata(Map.of("shopDomain", "alpha.store.example.com"))
                .build()))
            .build();

        executor.execute(
            "commerce_search_catalog",
            ActionAccessMode.READ,
            Map.of("query", "bag"),
            new ActionContext(orchestrationContext, null),
            Map.of(
                "adapterType", "mcp-tool",
                "execution", Map.of("mcp", Map.of(
                    "serverRef", "commerce-storefront",
                    "endpointKind", "STOREFRONT_STANDARD",
                    "toolName", "search_catalog"
                ))
            )
        );

        Map<String, Object> request = readRequest(fake.lastRequestBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> trace = (Map<String, Object>) request.get(ActionConnectorProtocol.KEY_TRACE);
        assertThat(trace).containsEntry("shopDomain", "alpha.store.example.com");
    }

    @Test
    void execute_shouldFailClosedForMcpToolWhenGatewayApiKeyMissing() {
        FakeHttpClient fake = new FakeHttpClient(List.of());
        AIActionConnectorProperties props = connectorProps("https://example", 1, Duration.ZERO);
        props.getMcpGateway().setBaseUrl("https://mcp-gateway.internal");
        ActionConnectorExecutor executor = new ActionConnectorExecutor(
            props,
            fake,
            null,
            fixedClock()
        );

        ActionResult result = executor.execute(
            "inventory_search",
            ActionAccessMode.READ,
            Map.of("query", "bag"),
            testContext(),
            Map.of(
                "adapterType", "mcp-tool",
                "execution", Map.of("mcp", Map.of("serverRef", "inventory-mcp", "toolName", "inventory.search")),
                "mcpServers", Map.of("inventory-mcp", Map.of("endpointUrl", "https://inventory.example/mcp"))
            )
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("INVALID_CONFIGURATION");
        assertThat(result.getMessage()).contains("mcp-gateway.api-key");
        assertThat(fake.callCount()).isZero();
    }

    @Test
    void execute_shouldUseMcpActionExecutorWhenGatewayIsNotConfigured() {
        FakeHttpClient fake = new FakeHttpClient(List.of());
        RecordingMcpActionExecutor mcpExecutor = new RecordingMcpActionExecutor(ActionResult.builder()
            .success(true)
            .message("bridge ok")
            .data(ai.fabric.intent.action.ActionPayload.object(Map.of("source", "spring-ai-mcp")))
            .build());
        ActionConnectorExecutor executor = new ActionConnectorExecutor(
            connectorProps("https://example", 1, Duration.ZERO),
            fake,
            null,
            fixedClock(),
            mcpExecutor
        );

        ActionResult result = executor.execute(
            "inventory_search",
            ActionAccessMode.READ,
            Map.of("query", "bag"),
            testContext(),
            Map.of(
                "adapterType", "mcp-tool",
                "execution", Map.of("mcp", Map.of("serverRef", "inventory-mcp", "toolName", "inventory.search"))
            )
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("bridge ok");
        assertThat(result.getData().toMap()).containsEntry("source", "spring-ai-mcp");
        assertThat(mcpExecutor.calls()).isEqualTo(1);
        assertThat(fake.callCount()).isZero();
    }

    @Test
    void execute_shouldRetryOnRetryableErrorCodeWhenIdempotent() {
        FakeHttpClient fake = new FakeHttpClient(List.of(
            new OutboundHttpExecutionResponse(200, "{\"success\":false,\"errorCode\":\"SERVICE_UNAVAILABLE\",\"message\":\"temp\"}", Map.of()),
            new OutboundHttpExecutionResponse(200, "{\"success\":true,\"message\":\"ok\",\"data\":{\"orderRef\":\"PO-2\"}}", Map.of())
        ));

        ActionConnectorExecutor executor = new ActionConnectorExecutor(
            connectorProps("https://example", 2, Duration.ZERO),
            fake,
            null,
            fixedClock()
        );

        ActionResult result = executor.execute(
            "create_purchase_order",
            ActionAccessMode.WRITE_ONLY,
            Map.of("sku", "SKU-2", "quantity", 1),
            testContext()
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(fake.callCount()).isEqualTo(2);
    }

    @Test
    void execute_shouldFailClosedWhenNon2xxBodyClaimsSuccess() {
        FakeHttpClient fake = new FakeHttpClient(List.of(
            new OutboundHttpExecutionResponse(500, "{\"success\":true,\"message\":\"not ok\",\"data\":{\"orderRef\":\"PO-3\"}}", Map.of())
        ));

        ActionConnectorExecutor executor = new ActionConnectorExecutor(
            connectorProps("https://example", 1, Duration.ZERO),
            fake,
            null,
            fixedClock()
        );

        ActionResult result = executor.execute(
            "create_purchase_order",
            ActionAccessMode.WRITE_ONLY,
            Map.of("sku", "SKU-3", "quantity", 1),
            testContext()
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(result.getMessage()).contains("HTTP 500");
        assertThat(result.getData()).isNull();
    }

    @Test
    void execute_shouldFailClosedWithInvalidResponseForMalformedSuccessfulPayload() {
        FakeHttpClient fake = new FakeHttpClient(List.of(
            new OutboundHttpExecutionResponse(
                200,
                "{\"success\":true,\"message\":\"ok\",\"data\":{\"_items\":[{\"id\":\"p1\"}],\"_count\":\"one\"}}",
                Map.of()
            )
        ));

        ActionConnectorExecutor executor = new ActionConnectorExecutor(
            connectorProps("https://example", 1, Duration.ZERO),
            fake,
            null,
            fixedClock()
        );

        ActionResult result = executor.execute(
            "list_products",
            ActionAccessMode.READ,
            Map.of("q", "sony"),
            testContext()
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("INVALID_RESPONSE");
        assertThat(result.getMessage()).contains("_count must be a number");
    }

    @Test
    void execute_shouldPreserveHandledFailureWhenFailurePayloadIsMalformed() {
        FakeHttpClient fake = new FakeHttpClient(List.of(
            new OutboundHttpExecutionResponse(
                200,
                "{\"success\":false,\"message\":\"No access.\",\"errorCode\":\"FORBIDDEN\",\"data\":{\"_items\":[{\"id\":\"p1\"}],\"_count\":\"one\"}}",
                Map.of()
            )
        ));

        ActionConnectorExecutor executor = new ActionConnectorExecutor(
            connectorProps("https://example", 1, Duration.ZERO),
            fake,
            null,
            fixedClock()
        );

        ActionResult result = executor.execute(
            "list_products",
            ActionAccessMode.READ,
            Map.of("q", "sony"),
            testContext()
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("FORBIDDEN");
        assertThat(result.getMessage()).isEqualTo("No access.");
        assertThat(result.getData()).isNull();
    }

    @Test
    void execute_shouldPreserveStructuredFailureForNon2xxResponses() {
        FakeHttpClient fake = new FakeHttpClient(List.of(
            new OutboundHttpExecutionResponse(
                403,
                "{\"success\":false,\"message\":\"No access.\",\"errorCode\":\"FORBIDDEN\",\"data\":{\"resource\":\"order-1\"}}",
                Map.of()
            )
        ));

        ActionConnectorExecutor executor = new ActionConnectorExecutor(
            connectorProps("https://example", 1, Duration.ZERO),
            fake,
            null,
            fixedClock()
        );

        ActionResult result = executor.execute(
            "read_order",
            ActionAccessMode.READ,
            Map.of("orderId", "order-1"),
            testContext()
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("FORBIDDEN");
        assertThat(result.getMessage()).isEqualTo("No access.");
        assertThat(result.getData()).isInstanceOf(ActionObjectPayload.class);
        assertThat(result.getData().toMap()).containsEntry("resource", "order-1");
    }

    @Test
    void execute_shouldMapEmptyHttp429ResponseToRateLimited() {
        FakeHttpClient fake = new FakeHttpClient(List.of(
            new OutboundHttpExecutionResponse(429, "", Map.of())
        ));

        ActionConnectorExecutor executor = new ActionConnectorExecutor(
            connectorProps("https://example", 1, Duration.ZERO),
            fake,
            null,
            fixedClock()
        );

        ActionResult result = executor.execute(
            "read_order",
            ActionAccessMode.READ,
            Map.of("orderId", "order-1"),
            testContext()
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("RATE_LIMITED");
        assertThat(result.getMessage()).contains("HTTP 429");
    }

    @Test
    void execute_shouldRetryMappedHttp503WhenActionIsIdempotent() {
        FakeHttpClient fake = new FakeHttpClient(List.of(
            new OutboundHttpExecutionResponse(503, "", Map.of()),
            new OutboundHttpExecutionResponse(200, "{\"success\":true,\"message\":\"ok\",\"data\":{\"orderRef\":\"PO-4\"}}", Map.of())
        ));

        ActionConnectorExecutor executor = new ActionConnectorExecutor(
            connectorProps("https://example", 2, Duration.ZERO),
            fake,
            null,
            fixedClock()
        );

        ActionResult result = executor.execute(
            "create_purchase_order",
            ActionAccessMode.WRITE_ONLY,
            Map.of("sku", "SKU-4", "quantity", 1),
            testContext()
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().toMap()).containsEntry("orderRef", "PO-4");
        assertThat(fake.callCount()).isEqualTo(2);
    }

    private static AIActionConnectorProperties connectorProps(String baseUrl, int maxAttempts, Duration initialBackoff) {
        AIActionConnectorProperties props = new AIActionConnectorProperties();
        props.setBaseUrl(baseUrl);
        props.setMaxAttempts(maxAttempts);
        props.setInitialBackoff(initialBackoff);
        props.setConnectTimeout(Duration.ofMillis(1));
        props.setReadTimeout(Duration.ofMillis(1));
        return props;
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-02-11T00:00:00Z"), ZoneOffset.UTC);
    }

    private static ActionContext testContext() {
        OrchestrationContext orch = OrchestrationContext.builder()
            .userId("user@example.com")
            .sessionId("s1")
            .conversationId("c1")
            .requestId("r1")
            .metadata(Map.of(
                OrchestrationContextMetadataKeys.SUBJECT_ID, "user@example.com",
                OrchestrationContextMetadataKeys.SUBJECT_TYPE, "INTERNAL_PLATFORM_USER",
                OrchestrationContextMetadataKeys.AUTH_MODE, "PLATFORM_PROXY_SESSION",
                OrchestrationContextMetadataKeys.CALLER_TYPE, "PLATFORM_PROXY",
                OrchestrationContextMetadataKeys.DEPLOYMENT_ID, "dep-1",
                OrchestrationContextMetadataKeys.CUSTOMER_ID, "cust-1",
                OrchestrationContextMetadataKeys.TENANT_ID, "ten-1",
                OrchestrationContextMetadataKeys.AUTH_ISSUER, "platform-poc:SESSION"
            ))
            .build();
        return new ActionContext(orch, null);
    }

    private static Map<String, Object> readRequest(String body) {
        try {
            return OBJECT_MAPPER.readValue(body, Map.class);
        } catch (Exception ex) {
            throw new AssertionError("Failed to parse connector request JSON", ex);
        }
    }

    private static final class FakeHttpClient implements OutboundHttpExecutor {
        private final List<OutboundHttpExecutionResponse> responses;
        private final AtomicInteger calls = new AtomicInteger(0);
        private volatile OutboundHttpExecutionRequest lastRequest;
        private volatile String lastRequestBody;

        private FakeHttpClient(List<OutboundHttpExecutionResponse> responses) {
            this.responses = responses != null ? new ArrayList<>(responses) : List.of();
        }

        @Override
        public OutboundHttpExecutionResponse execute(OutboundHttpExecutionRequest request) {
            int idx = calls.getAndIncrement();
            lastRequest = request;
            lastRequestBody = request != null ? request.body() : null;
            return responses.get(Math.min(idx, responses.size() - 1));
        }

        int callCount() {
            return calls.get();
        }

        String lastRequestBody() {
            return lastRequestBody;
        }

        OutboundHttpExecutionRequest lastRequest() {
            return lastRequest;
        }
    }

    private static final class RecordingMcpActionExecutor implements McpActionExecutor {
        private final ActionResult result;
        private final AtomicInteger calls = new AtomicInteger();

        private RecordingMcpActionExecutor(ActionResult result) {
            this.result = result;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public ActionResult execute(String actionId,
                                    ActionAccessMode accessMode,
                                    Map<String, Object> params,
                                    ActionContext context,
                                    Map<String, Object> actionConfig) {
            calls.incrementAndGet();
            return result;
        }

        int calls() {
            return calls.get();
        }
    }
}
