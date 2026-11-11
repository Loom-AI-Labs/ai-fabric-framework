package ai.fabric.intent.action.connector;

import ai.fabric.config.PromptBundleProperties;
import ai.fabric.http.OutboundHttpExecutionResponse;
import ai.fabric.intent.EnrichedPromptBuilder;
import ai.fabric.intent.SystemContext;
import ai.fabric.intent.SystemContextBuilder;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.RegisteredActionsAIActionProvider;
import ai.fabric.intent.action.tool.AIActionToolCallbackFactory;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NativeConnectorActionContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void optionalNullSchemasDoNotReplaceTheExistingSimplifiedContractWithAnEmptySchema() {
        var source = new AIActionCatalogProperties.ActionSourceProperties();
        source.setPath("classpath:actions/native-mcp-actions.yml");
        var definition = new ConnectorActionCatalogLoader(new DefaultResourceLoader()).loadActions(List.of(source)).get(2);
        var metadata = ConnectorActionMetadataMapper.toMetadata(definition);
        assertThat(metadata.getInputSchema()).isNull();
        assertThat(metadata.getOutputSchema()).isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            ai.fabric.intent.action.ActionParameterContractValidator.validateExecutable(metadata, Map.of("count", "not-an-integer")))
            .isInstanceOf(ai.fabric.intent.action.ActionParameterContractValidator.ActionParameterContractException.class);
    }

    @Test
    void cataloguePreservesEmptyInputAndNonObjectOutputSchema() {
        var source = new AIActionCatalogProperties.ActionSourceProperties();
        source.setPath("classpath:actions/native-mcp-actions.yml");
        var definition = new ConnectorActionCatalogLoader(new DefaultResourceLoader()).loadActions(List.of(source)).get(1);
        var metadata = ConnectorActionMetadataMapper.toMetadata(definition);
        assertThat(definition.inputSchema()).isEmpty();
        assertThat(metadata.getInputSchema()).isEmpty();
        assertThat(metadata.getOutputSchema()).containsEntry("type", "array");
        assertThat(metadata.getOutputSchema()).isEqualTo(definition.outputSchema());
        assertThat(definition.runtimeActionConfig()).containsEntry("inputSchema", Map.of())
            .containsEntry("outputSchema", definition.outputSchema());
    }

    @Test
    void catalogueNativeSchemasReachActualPlannerPromptToolDefinitionAndRuntimeConfig() throws Exception {
        ConnectorActionDefinition definition = definition();
        AIActionMetaData metadata = ConnectorActionMetadataMapper.toMetadata(definition);
        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.getAllMetadata()).thenReturn(List.of(metadata));
        var info = new RegisteredActionsAIActionProvider(registry).getAvailableActions().getFirst().sanitizedCopy();
        SystemContextBuilder contextBuilder = mock(SystemContextBuilder.class);
        when(contextBuilder.buildContext(any(OrchestrationContext.class)))
            .thenReturn(SystemContext.builder().availableActions(List.of(info)).build());
        var builder = new EnrichedPromptBuilder(contextBuilder,
            new PromptTemplateResolver(new ClasspathPromptTemplateStore(new DefaultResourceLoader()), new PromptBundleProperties()),
            new PromptRenderer());
        String prompt = builder.buildSystemPrompt(OrchestrationContext.anonymous());
        assertThat(prompt).contains("inputSchema:", "oneOf", "#/$defs/selection", "0.25", "2.75", "outputSchema:", "receipt");
        var handler = new ConnectorAIActionHandler(metadata, false, null, java.util.Set.of(), null, null, definition.runtimeActionConfig());
        var callback = new AIActionToolCallbackFactory(registry, JSON).createCallback(handler,
            new ActionContext(OrchestrationContext.anonymous(), null));
        assertThat(JSON.readTree(callback.getToolDefinition().inputSchema())).isEqualTo(JSON.valueToTree(definition.inputSchema()));
        assertThat(metadata.getInputSchema()).isEqualTo(definition.inputSchema());
        assertThat(metadata.getOutputSchema()).isEqualTo(definition.outputSchema());
        assertThat(info.getOutputSchema()).isEqualTo(definition.outputSchema());
        assertThat(handler.actionRuntimeConfig()).containsEntry("inputSchema", definition.inputSchema())
            .containsEntry("outputSchema", definition.outputSchema());
        assertThat(metadata.getGroundingVectorSpaces()).isEmpty();
        assertThat(metadata.isReadActionResolutionEligible()).isTrue();
    }

    @Test
    void validatesResolvedNestedArgumentsBeforeGatewayAndPreservesOutputSchema() throws Exception {
        ConnectorActionDefinition definition = definition();
        AtomicInteger calls = new AtomicInteger();
        var properties = new AIActionConnectorProperties();
        properties.getMcpGateway().setBaseUrl("https://gateway.example");
        properties.getMcpGateway().setApiKey("test-service-key");
        var executor = new ActionConnectorExecutor(properties, request -> {
            calls.incrementAndGet();
            com.fasterxml.jackson.databind.JsonNode body;
            try {
                body = JSON.readTree(request.body());
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new AssertionError(ex);
            }
            assertThat(body.path("params").path("selection").path("note").isNull()).isTrue();
            assertThat(body.path("trace").path("actionConfig").path("outputSchema"))
                .isEqualTo(JSON.valueToTree(definition.outputSchema()));
            return new OutboundHttpExecutionResponse(200, "{\"success\":true,\"data\":{\"accepted\":true}}", Map.of());
        }, null, Clock.systemUTC());
        var context = new ActionContext(OrchestrationContext.anonymous(), null);
        var valid = JSON.readValue("{\"selection\":{\"price\":0.5,\"tier\":1,\"note\":null}}", new TypeReference<Map<String, Object>>() {});
        assertThat(executor.execute(definition.name(), definition.accessMode(), valid, context, definition.runtimeActionConfig()).isSuccess()).isTrue();
        assertThat(calls).hasValue(1);
        var invalid = JSON.readValue("{\"selection\":{\"price\":0.1,\"tier\":1,\"note\":null}}", new TypeReference<Map<String, Object>>() {});
        var result = executor.execute(definition.name(), definition.accessMode(), invalid, context, definition.runtimeActionConfig());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("ACTION_PARAMETER_INVALID");
        assertThat(calls).hasValue(1);
    }

    private static ConnectorActionDefinition definition() {
        var source = new AIActionCatalogProperties.ActionSourceProperties();
        source.setPath("classpath:actions/native-mcp-actions.yml");
        return new ConnectorActionCatalogLoader(new DefaultResourceLoader()).loadActions(List.of(source)).getFirst();
    }
}
