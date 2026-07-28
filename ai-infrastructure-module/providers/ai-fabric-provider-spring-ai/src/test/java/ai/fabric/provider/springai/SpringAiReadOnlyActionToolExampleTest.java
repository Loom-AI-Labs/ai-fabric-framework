package ai.fabric.provider.springai;

import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.tool.AIActionToolCallbackFactory;
import ai.fabric.intent.orchestration.OrchestrationContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.tool.ToolCallback;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringAiReadOnlyActionToolExampleTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void commerceReadOnlyActionsCanBeExposedAsProviderNativeToolsWithTrustedContext() throws Exception {
        AtomicReference<Map<String, Object>> executedParams = new AtomicReference<>(Map.of());
        AIActionRegistry actionRegistry = commerceReadOnlyActionRegistry(executedParams);
        AIActionToolCallbackFactory callbackFactory = new AIActionToolCallbackFactory(actionRegistry, OBJECT_MAPPER);
        ToolRecordingChatClientFactory chatClientFactory = new ToolRecordingChatClientFactory();
        SpringAiChatProvider provider = new SpringAiChatProvider(
            "openai",
            new StubResolver(),
            chatClientFactory,
            callbackFactory
        );

        ActionContext actionContext = new ActionContext(
            OrchestrationContext.forUser("shopper-7"),
            null,
            Map.of("shopperSessionId", "server-session-123")
        );

        var response = provider.generateContent(AIGenerationRequest.builder()
            .prompt("Can you look up order PO-1001 and summarize the status?")
            .parameters(AIActionToolCallbackFactory.requestParameters(
                actionContext,
                List.of("get_order_details", "list_orders")))
            .build());

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getMetadata()).containsEntry("actionToolCallbacks", 2);
        assertThat(response.getMetadata()).containsEntry("actionToolNames", List.of("get_order_details", "list_orders"));
        assertThat(chatClientFactory.toolCallbacks())
            .extracting(callback -> callback.getToolDefinition().name())
            .containsExactly("get_order_details", "list_orders");

        ToolCallback getOrderDetails = chatClientFactory.toolCallbacks().getFirst();
        assertThat(getOrderDetails.getToolDefinition().inputSchema())
            .contains("orderNumberOrId")
            .doesNotContain("shopperSessionId")
            .doesNotContain("internalTenantId");

        String toolResult = getOrderDetails.call("""
            {
              "orderNumberOrId": "PO-1001",
              "shopperSessionId": "model-supplied-session",
              "internalTenantId": "model-supplied-tenant",
              "unknown": "ignored"
            }
            """);
        Map<String, Object> result = OBJECT_MAPPER.readValue(toolResult, new TypeReference<>() {
        });

        assertThat(result).containsEntry("actionName", "get_order_details");
        assertThat(result).containsEntry("toolName", "get_order_details");
        assertThat(result).containsEntry("success", true);
        assertThat(executedParams.get())
            .containsEntry("orderNumberOrId", "PO-1001")
            .containsEntry("shopperSessionId", "server-session-123")
            .doesNotContainKeys("internalTenantId", "unknown");
        assertThat(toolResult)
            .contains("PO-1001")
            .doesNotContain("model-supplied-session")
            .doesNotContain("model-supplied-tenant")
            .doesNotContain("server-session-123");
    }

    private static AIActionRegistry commerceReadOnlyActionRegistry(AtomicReference<Map<String, Object>> executedParams) {
        AIActionHandler getOrderDetails = readOnlyHandler(
            "get_order_details",
            "Get purchase order details by order number",
            Map.of("orderNumberOrId", "Order number", "shopperSessionId", "Trusted shopper session"),
            Map.of(
                "orderNumberOrId", stringSchema("Order number", null, true),
                "shopperSessionId", stringSchema("Trusted shopper session", "SYSTEM", false)
            ),
            Set.of("orderNumberOrId", "shopperSessionId"),
            params -> {
                executedParams.set(Map.copyOf(params));
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("orderNumber", params.get("orderNumberOrId"));
                data.put("status", "SHIPPED");
                data.put("total", "129.99");
                return ActionResult.builder()
                    .success(true)
                    .message("Order details")
                    .data(ActionResultContracts.object(data))
                    .build();
            }
        );
        AIActionHandler listOrders = readOnlyHandler(
            "list_orders",
            "List recent orders",
            Map.of("limit", "Maximum orders to return"),
            Map.of("limit", AIActionParamSchema.builder()
                .type(AIActionParamType.INTEGER)
                .description("Maximum orders to return")
                .build()),
            Set.of(),
            params -> ActionResult.builder()
                .success(true)
                .message("Orders")
                .data(ActionResultContracts.list(List.of(Map.of("orderNumber", "PO-1001"))))
                .build()
        );

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.getAllMetadata()).thenReturn(List.of(
            getOrderDetails.getActionMetadata(),
            listOrders.getActionMetadata()
        ));
        when(registry.findMetadata("get_order_details"))
            .thenReturn(Optional.of(getOrderDetails.getActionMetadata()));
        when(registry.findMetadata("list_orders"))
            .thenReturn(Optional.of(listOrders.getActionMetadata()));
        when(registry.findHandler("get_order_details")).thenReturn(Optional.of(getOrderDetails));
        when(registry.findHandler("list_orders")).thenReturn(Optional.of(listOrders));
        when(registry.findHandler("cancel_purchase_order")).thenReturn(Optional.empty());
        return registry;
    }

    private static AIActionHandler readOnlyHandler(String name,
                                                   String description,
                                                   Map<String, String> parameters,
                                                   Map<String, AIActionParamSchema> schemas,
                                                   Set<String> requiredParameters,
                                                   java.util.function.Function<Map<String, Object>, ActionResult> executor) {
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name(name)
            .description(description)
            .category("commerce")
            .accessMode(ActionAccessMode.READ)
            .anonymousAllowed(false)
            .confirmationRequired(false)
            .parameters(parameters)
            .parameterSchemas(schemas)
            .requiredParameters(requiredParameters)
            .build();
        return new AIActionHandler() {
            @Override
            public AIActionMetaData getActionMetadata() {
                return metadata;
            }

            @Override
            public boolean requiresConfirmation() {
                return false;
            }

            @Override
            public String getConfirmationMessage(Map<String, Object> params, ActionContext context) {
                return "";
            }

            @Override
            public ActionResult executeAction(Map<String, Object> params, ActionContext context) {
                assertThat(context.userId()).isEqualTo("shopper-7");
                return executor.apply(params);
            }
        };
    }

    private static AIActionParamSchema stringSchema(String description, String visibility, Boolean askUser) {
        return AIActionParamSchema.builder()
            .type(AIActionParamType.STRING)
            .description(description)
            .visibility(visibility)
            .askUser(askUser)
            .build();
    }

    private static final class StubResolver extends SpringAiModelResolver {
        private StubResolver() {
            super(new ai.fabric.config.AIProviderConfig());
        }

        @Override
        public ChatModel resolveChatModel(String providerName, AIGenerationRequest request) {
            return prompt -> new ChatResponse(
                List.of(new Generation(new AssistantMessage("Generated answer"))),
                ChatResponseMetadata.builder().model("spring-model").build()
            );
        }

        @Override
        public ChatOptions resolveChatOptions(String providerName, AIGenerationRequest request) {
            return ChatOptions.builder().model("spring-model").build();
        }

        @Override
        public boolean isChatAvailable(String providerName, AIGenerationRequest request) {
            return true;
        }

        @Override
        public EmbeddingModel resolveEmbeddingModel(String providerName, ai.fabric.dto.AIEmbeddingRequest request) {
            throw new UnsupportedOperationException("Example does not use embeddings");
        }

        @Override
        public EmbeddingOptions resolveEmbeddingOptions(String providerName, ai.fabric.dto.AIEmbeddingRequest request) {
            return null;
        }
    }

    private static final class ToolRecordingChatClientFactory extends SpringAiChatClientFactory {
        private final AtomicReference<List<ToolCallback>> toolCallbacks = new AtomicReference<>(List.of());

        private ToolRecordingChatClientFactory() {
            super(ObservationRegistry.NOOP, List.of());
        }

        @Override
        public ChatClient create(ChatModel chatModel) {
            ChatClient chatClient = mock(ChatClient.class);
            ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
            ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
            when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
            Answer<ChatClient.ChatClientRequestSpec> captureTools = invocation -> {
                List<ToolCallback> captured = new ArrayList<>();
                for (Object argument : invocation.getArguments()) {
                    if (argument instanceof ToolCallback callback) {
                        captured.add(callback);
                    } else if (argument instanceof ToolCallback[] callbacks) {
                        captured.addAll(List.of(callbacks));
                    }
                }
                toolCallbacks.set(List.copyOf(captured));
                return requestSpec;
            };
            when(requestSpec.tools(any())).thenAnswer(captureTools);
            when(requestSpec.tools(any(), any())).thenAnswer(captureTools);
            when(requestSpec.call()).thenReturn(callSpec);
            when(callSpec.chatResponse()).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("Generated answer"))),
                ChatResponseMetadata.builder().model("spring-model").build()));
            return chatClient;
        }

        List<ToolCallback> toolCallbacks() {
            return toolCallbacks.get();
        }
    }
}
