package ai.fabric.intent.action.connector.springai;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionListPayload;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.connector.McpActionExecutor;
import ai.fabric.intent.orchestration.OrchestrationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiMcpActionExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void execute_shouldCallSpringAiManagedMcpClientAndMapStructuredContentList() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.isInitialized()).thenReturn(true);
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("ai-fabric-inventory-mcp", "inventory-mcp", "1.0.0"));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool("inventory.search")), null));
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent("found products")),
            false,
            Map.of("products", List.of(Map.of("sku", "SKU-1", "name", "Canvas Bag"))),
            Map.of("usageEvidence", "mcp-sync-client")
        ));

        SpringAiMcpActionExecutor executor = new SpringAiMcpActionExecutor(() -> List.of(client), objectMapper);

        ActionResult result = executor.execute(
            "inventory_search",
            ActionAccessMode.READ,
            Map.of("query", "bag", "shopperSessionId", "trusted-session"),
            testContext(),
            inventoryActionConfig()
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isInstanceOf(ActionListPayload.class);
        assertThat(result.getData().toMap())
            .containsEntry("_count", 1)
            .containsKey("_items");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.getData().toMap().get("_items");
        assertThat(items.getFirst()).containsEntry("sku", "SKU-1");

        ArgumentCaptor<McpSchema.CallToolRequest> request = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client).callTool(request.capture());
        assertThat(request.getValue().name()).isEqualTo("inventory.search");
        assertThat(request.getValue().arguments())
            .containsEntry("query", "bag")
            .doesNotContainKey("shopperSessionId");
        assertThat(request.getValue().meta())
            .containsEntry("aiFabricActionId", "inventory_search")
            .containsEntry("requestId", "r1");
    }

    @Test
    void execute_shouldReturnToolUnavailableWhenSpringAiClientDoesNotExposeCatalogTool() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.isInitialized()).thenReturn(true);
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("ai-fabric-inventory-mcp", "inventory-mcp", "1.0.0"));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool("inventory.lookup")), null));

        SpringAiMcpActionExecutor executor = new SpringAiMcpActionExecutor(() -> List.of(client), objectMapper);

        ActionResult result = executor.execute(
            "inventory_search",
            ActionAccessMode.READ,
            Map.of("query", "bag"),
            testContext(),
            inventoryActionConfig()
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo(McpActionExecutor.ERROR_MCP_TOOL_NOT_AVAILABLE);
        verify(client, never()).callTool(any(McpSchema.CallToolRequest.class));
    }

    @Test
    void execute_shouldFailClosedWhenActionConfigDoesNotDeclareToolName() {
        SpringAiMcpActionExecutor executor = new SpringAiMcpActionExecutor(List::of, objectMapper);

        ActionResult result = executor.execute(
            "inventory_search",
            ActionAccessMode.READ,
            Map.of("query", "bag"),
            testContext(),
            Map.of("adapterType", "mcp-tool", "execution", Map.of("mcp", Map.of("serverRef", "inventory-mcp")))
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("INVALID_CONFIGURATION");
        assertThat(result.getMessage()).contains("toolName");
    }

    @Test
    void execute_shouldNotFallBackToAnotherServerWithTheSameToolName() {
        McpSyncClient expectedServer = mock(McpSyncClient.class);
        when(expectedServer.isInitialized()).thenReturn(true);
        when(expectedServer.getServerInfo()).thenReturn(
            new McpSchema.Implementation(
                "inventory-mcp",
                "Inventory MCP",
                "1.0.0"
            )
        );
        when(expectedServer.listTools()).thenReturn(
            new McpSchema.ListToolsResult(
                List.of(tool("inventory.lookup")),
                null
            )
        );

        McpSyncClient duplicateToolServer = mock(McpSyncClient.class);
        when(duplicateToolServer.isInitialized()).thenReturn(true);
        when(duplicateToolServer.getServerInfo()).thenReturn(
            new McpSchema.Implementation(
                "untrusted-mcp",
                "Untrusted MCP",
                "1.0.0"
            )
        );
        when(duplicateToolServer.listTools()).thenReturn(
            new McpSchema.ListToolsResult(
                List.of(tool("inventory.search")),
                null
            )
        );

        SpringAiMcpActionExecutor executor = new SpringAiMcpActionExecutor(
            () -> List.of(expectedServer, duplicateToolServer),
            objectMapper
        );

        ActionResult result = executor.execute(
            "inventory_search",
            ActionAccessMode.READ,
            Map.of("query", "bag"),
            testContext(),
            inventoryActionConfig()
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode())
            .isEqualTo(McpActionExecutor.ERROR_MCP_TOOL_NOT_AVAILABLE);
        verify(duplicateToolServer, never()).listTools();
        verify(duplicateToolServer, never())
            .callTool(any(McpSchema.CallToolRequest.class));
    }

    @Test
    void execute_shouldInitializeADeferredClientBeforeInspectingItsServer() {
        McpSyncClient client = mock(McpSyncClient.class);
        AtomicBoolean initialized = new AtomicBoolean(false);
        when(client.isInitialized()).thenAnswer(ignored -> initialized.get());
        when(client.initialize()).thenAnswer(ignored -> {
            initialized.set(true);
            return null;
        });
        when(client.getServerInfo()).thenReturn(
            new McpSchema.Implementation("inventory-mcp", "Inventory MCP", "1.0.0")
        );
        when(client.listTools()).thenReturn(
            new McpSchema.ListToolsResult(List.of(tool("inventory.search")), null)
        );
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(
            new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("ok")),
                false,
                Map.of("products", List.of()),
                Map.of()
            )
        );

        SpringAiMcpActionExecutor executor = new SpringAiMcpActionExecutor(
            () -> List.of(client),
            objectMapper
        );

        ActionResult result = executor.execute(
            "inventory_search",
            ActionAccessMode.READ,
            Map.of("query", "bag"),
            testContext(),
            inventoryActionConfig()
        );

        assertThat(result.isSuccess()).isTrue();
        verify(client).initialize();
    }

    @Test
    void execute_shouldRejectStructuredContentBeyondTheConfiguredLimit() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.isInitialized()).thenReturn(true);
        when(client.getServerInfo()).thenReturn(
            new McpSchema.Implementation("inventory-mcp", "Inventory MCP", "1.0.0")
        );
        when(client.listTools()).thenReturn(
            new McpSchema.ListToolsResult(List.of(tool("inventory.search")), null)
        );
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(
            new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("oversized")),
                false,
                Map.of("products", List.of(Map.of("description", "x".repeat(2_000)))),
                Map.of()
            )
        );

        SpringAiMcpActionExecutor executor = new SpringAiMcpActionExecutor(
            () -> List.of(client),
            objectMapper
        );
        Map<String, Object> config = new java.util.LinkedHashMap<>(
            inventoryActionConfig()
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> execution = new java.util.LinkedHashMap<>(
            (Map<String, Object>) config.get("execution")
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> mcp = new java.util.LinkedHashMap<>(
            (Map<String, Object>) execution.get("mcp")
        );
        mcp.put(
            "responseMapping",
            Map.of(
                "resultPath", "$.structuredContent.products",
                "maxCharacters", 1_024
            )
        );
        execution.put("mcp", mcp);
        config.put("execution", execution);

        ActionResult result = executor.execute(
            "inventory_search",
            ActionAccessMode.READ,
            Map.of("query", "bag"),
            testContext(),
            config
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("MCP_RESULT_TOO_LARGE");
    }

    private McpSchema.Tool tool(String name) {
        return new McpSchema.Tool(
            name,
            name,
            "Search inventory",
            Map.of("type", "object"),
            Map.of(),
            null,
            Map.of()
        );
    }

    private Map<String, Object> inventoryActionConfig() {
        return Map.of(
            "adapterType", "mcp-tool",
            "execution", Map.of(
                "adapterType", "mcp-tool",
                "mcp", Map.of(
                    "serverRef", "inventory-mcp",
                    "toolName", "inventory.search",
                    "argumentTemplate", Map.of("query", "{{params.query}}"),
                    "responseMapping", Map.of("resultPath", "$.structuredContent.products")
                )
            )
        );
    }

    private ActionContext testContext() {
        return new ActionContext(OrchestrationContext.builder()
            .requestId("r1")
            .conversationId("c1")
            .userId("user@example.com")
            .sessionId("s1")
            .build(), null);
    }
}
