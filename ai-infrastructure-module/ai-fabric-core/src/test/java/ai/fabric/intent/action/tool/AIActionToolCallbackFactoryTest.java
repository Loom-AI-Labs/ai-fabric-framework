package ai.fabric.intent.action.tool;

import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionPayload;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.orchestration.OrchestrationContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AIActionToolCallbackFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void inputSchemaExposesOnlyPublicDeclaredParameters() throws Exception {
        ToolCallback callback = newFactory().createCallback(new RecordingActionHandler(actionMetadata(), false), anonymousContext());

        Map<String, Object> schema = readMap(callback.getToolDefinition().inputSchema());
        Map<String, Object> properties = map(schema.get("properties"));

        assertThat(callback.getToolDefinition().name()).isEqualTo("lookup_order");
        assertThat(callback.getToolMetadata().returnDirect()).isFalse();
        assertThat(properties).containsOnlyKeys("orderId");
        assertThat(properties).doesNotContainKeys("shopperSessionId", "internalTenantId", "confirmationAccepted");
        assertThat(schema).containsEntry("additionalProperties", false);
        assertThat(list(schema.get("required"))).containsExactly("orderId");
    }

    @Test
    void callExecutesWithTrustedHiddenContextAndDropsModelSuppliedHiddenValues() throws Exception {
        RecordingActionHandler handler = new RecordingActionHandler(actionMetadata(), false);
        ActionContext context = new ActionContext(
            OrchestrationContext.forUser("user-1"),
            null,
            Map.of(
                "shopperSessionId", "trusted-session",
                "internalTenantId", "trusted-tenant"
            )
        );
        ToolCallback callback = newFactory().createCallback(handler, context);

        Map<String, Object> output = readMap(callback.call("""
            {
              "orderId": "ORD-1",
              "shopperSessionId": "model-session",
              "internalTenantId": "model-tenant",
              "unknown": "ignored"
            }
            """));

        assertThat(output).containsEntry("actionName", "lookup_order");
        assertThat(output).containsEntry("toolName", "lookup_order");
        assertThat(output).containsEntry("success", true);
        assertThat(handler.executions.get()).isEqualTo(1);
        assertThat(handler.lastParams.get()).containsEntry("orderId", "ORD-1");
        assertThat(handler.lastParams.get()).containsEntry("shopperSessionId", "trusted-session");
        assertThat(handler.lastParams.get()).containsEntry("internalTenantId", "trusted-tenant");
        assertThat(handler.lastParams.get()).doesNotContainKeys("unknown");
    }

    @Test
    void modelSuppliedHiddenRequiredParameterDoesNotSatisfyTrustedContextRequirement() throws Exception {
        RecordingActionHandler handler = new RecordingActionHandler(actionMetadata(), false);
        ToolCallback callback = newFactory().createCallback(handler, anonymousContext());

        Map<String, Object> output = readMap(callback.call("""
            {
              "orderId": "ORD-1",
              "shopperSessionId": "model-session"
            }
            """));
        Map<String, Object> data = map(output.get("data"));

        assertThat(output).containsEntry("success", false);
        assertThat(output).containsEntry("errorCode", "ACTION_PARAMETERS_MISSING");
        assertThat(data).containsEntry("hiddenContextMissingCount", 1);
        assertThat(handler.executions.get()).isZero();
    }

    @Test
    void anonymousContextCannotExecuteAuthenticatedAction() throws Exception {
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name("admin_lookup")
            .description("Admin lookup")
            .accessMode(ActionAccessMode.READ)
            .anonymousAllowed(false)
            .build();
        RecordingActionHandler handler = new RecordingActionHandler(metadata, false);

        Map<String, Object> output = readMap(newFactory()
            .createCallback(handler, anonymousContext())
            .call("{}"));

        assertThat(output).containsEntry("success", false);
        assertThat(output).containsEntry("errorCode", "ACTION_NOT_ALLOWED");
        assertThat(handler.executions.get()).isZero();
    }

    @Test
    void confirmationRequiredActionReturnsConfirmationPayloadWithoutExecuting() throws Exception {
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name("cancel_order")
            .description("Cancel order")
            .accessMode(ActionAccessMode.WRITE_ONLY)
            .anonymousAllowed(true)
            .confirmationRequired(true)
            .parameters(Map.of("orderId", "Order id"))
            .parameterSchemas(Map.of("orderId", stringSchema("Order id", null, true)))
            .requiredParameters(Set.of("orderId"))
            .build();
        RecordingActionHandler handler = new RecordingActionHandler(metadata, true);

        Map<String, Object> output = readMap(newFactory()
            .createCallback(handler, anonymousContext())
            .call("{\"orderId\":\"ORD-1\"}"));

        assertThat(output).containsEntry("success", false);
        assertThat(output).containsEntry("errorCode", "CONFIRMATION_REQUIRED");
        assertThat(output).containsEntry("confirmationRequired", true);
        assertThat(output.get("message")).asString().contains("Confirm cancel_order");
        assertThat(handler.executions.get()).isZero();
    }

    @Test
    void invalidToolInputReturnsStructuredFailure() throws Exception {
        RecordingActionHandler handler = new RecordingActionHandler(actionMetadata(), false);

        Map<String, Object> output = readMap(newFactory()
            .createCallback(handler, anonymousContext())
            .call("[\"not\", \"an\", \"object\"]"));

        assertThat(output).containsEntry("success", false);
        assertThat(output).containsEntry("errorCode", "TOOL_INPUT_INVALID");
        assertThat(handler.executions.get()).isZero();
    }

    @Test
    void registrySupplierIsResolvedOnlyForRegistryBackedLookups() {
        AtomicInteger registryLookups = new AtomicInteger();
        AIActionToolCallbackFactory factory = new AIActionToolCallbackFactory(() -> {
            registryLookups.incrementAndGet();
            return mock(AIActionRegistry.class);
        }, objectMapper);

        factory.createCallback(new RecordingActionHandler(actionMetadata(), false), anonymousContext());

        assertThat(registryLookups).hasValue(0);

        factory.createCallbacks(anonymousContext());

        assertThat(registryLookups).hasValue(1);
    }

    private AIActionToolCallbackFactory newFactory() {
        return new AIActionToolCallbackFactory(mock(AIActionRegistry.class), objectMapper);
    }

    private ActionContext anonymousContext() {
        return new ActionContext(OrchestrationContext.anonymous(), null);
    }

    private AIActionMetaData actionMetadata() {
        Map<String, String> descriptions = new LinkedHashMap<>();
        descriptions.put("orderId", "Order id");
        descriptions.put("shopperSessionId", "Shopper session");
        descriptions.put("internalTenantId", "Internal tenant");
        descriptions.put("confirmationAccepted", "Confirmation flag");

        Map<String, AIActionParamSchema> schemas = new LinkedHashMap<>();
        schemas.put("orderId", stringSchema("Order id", null, true));
        schemas.put("shopperSessionId", stringSchema("Shopper session", "SYSTEM", false));
        schemas.put("internalTenantId", stringSchema("Internal tenant", "SECRET", false));
        schemas.put("confirmationAccepted", AIActionParamSchema.builder()
            .type(AIActionParamType.BOOLEAN)
            .description("Confirmation flag")
            .visibility("SYSTEM")
            .askUser(false)
            .build());

        return AIActionMetaData.builder()
            .name("lookup_order")
            .displayName("Lookup order")
            .description("Lookup order by id")
            .category("orders")
            .accessMode(ActionAccessMode.READ)
            .anonymousAllowed(true)
            .parameters(descriptions)
            .parameterSchemas(schemas)
            .requiredParameters(Set.of("orderId", "shopperSessionId"))
            .build();
    }

    private static AIActionParamSchema stringSchema(String description, String visibility, Boolean askUser) {
        return AIActionParamSchema.builder()
            .type(AIActionParamType.STRING)
            .description(description)
            .visibility(visibility)
            .askUser(askUser)
            .build();
    }

    private Map<String, Object> readMap(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<>() {
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Object> list(Object value) {
        return (java.util.List<Object>) value;
    }

    private static final class RecordingActionHandler implements AIActionHandler {
        private final AIActionMetaData metadata;
        private final boolean requiresConfirmation;
        private final AtomicInteger executions = new AtomicInteger();
        private final AtomicReference<Map<String, Object>> lastParams = new AtomicReference<>(Map.of());

        private RecordingActionHandler(AIActionMetaData metadata, boolean requiresConfirmation) {
            this.metadata = metadata;
            this.requiresConfirmation = requiresConfirmation;
        }

        @Override
        public AIActionMetaData getActionMetadata() {
            return metadata;
        }

        @Override
        public boolean requiresConfirmation() {
            return requiresConfirmation;
        }

        @Override
        public String getConfirmationMessage(Map<String, Object> params, ActionContext context) {
            return "Confirm " + metadata.getName();
        }

        @Override
        public ActionResult executeAction(Map<String, Object> params, ActionContext context) {
            executions.incrementAndGet();
            lastParams.set(Map.copyOf(params));
            return ActionResult.builder()
                .success(true)
                .message("ok")
                .data(ActionPayload.object(Map.of("params", params)))
                .build();
        }
    }
}
