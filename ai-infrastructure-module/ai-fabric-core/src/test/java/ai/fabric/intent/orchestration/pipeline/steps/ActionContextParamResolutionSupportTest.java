package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionPayload;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.attachment.NormalizedAttachment;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActionContextParamResolutionSupportTest {

    @Test
    void shouldResolveRuntimeAttachmentAndOwnedResourceParams() {
        AIActionMetaData meta = AIActionMetaData.builder()
            .parameterSchemas(Map.of(
                "sessionId", schema(Map.of("source", "RUNTIME_CONTEXT", "field", "sessionId")),
                "documentId", schema(Map.of("source", "ATTACHMENT_METADATA", "candidateKeys", List.of("documentId"))),
                "cartId", schema(Map.of(
                    "source", "OWNED_RESOURCE",
                    "resourceType", "cart",
                    "metadataKeys", List.of("id")
                ))
            ))
            .build();
        OrchestrationContext context = OrchestrationContext.builder()
            .userId("user-1")
            .sessionId("session-123")
            .attachmentsNormalized(List.of(NormalizedAttachment.builder()
                .id("attachment-1")
                .metadata(Map.of("documentId", "doc-456"))
                .build()))
            .build();
        PipelineContext pipelineContext = PipelineContext.from("query", context)
            .toBuilder()
            .metadata(Map.of(
                "ownedResources",
                List.of(Map.of("resourceType", "cart", "id", "cart-789"))
            ))
            .build();

        ActionContextParamResolutionSupport.ResolvedActionParams resolved =
            new ActionContextParamResolutionSupport(mock(AIActionRegistry.class))
                .resolveContextActionParams(meta, Map.of(), context, pipelineContext);

        assertThat(resolved.params())
            .containsEntry("sessionId", "session-123")
            .containsEntry("documentId", "doc-456")
            .containsEntry("cartId", "cart-789");
        assertThat(resolved.resolvedParameters()).containsExactlyInAnyOrder("sessionId", "documentId", "cartId");
        assertThat(resolved.blockingReadActionResult()).isNull();
    }

    @Test
    void shouldResolveParamFromAllowedReadActionResult() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        AIActionMetaData readMeta = readMeta("lookup_product");
        AIActionMetaData meta = AIActionMetaData.builder()
            .parameterSchemas(Map.of(
                "productId", schema(Map.of(
                    "source", "READ_ACTION",
                    "actionName", "lookup_product",
                    "params", Map.of("query", "{{params.sku|context.originalQuery}}"),
                    "resultPath", "productId"
                ))
            ))
            .build();
        when(registry.findMetadata("lookup_product")).thenReturn(Optional.of(readMeta));
        when(registry.findHandler("lookup_product")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(any(ActionContext.class))).thenReturn(true);
        when(handler.executeAction(any(), any())).thenReturn(ActionResult.builder()
            .success(true)
            .data(ActionPayload.object(Map.of("productId", "product-123")))
            .build());

        OrchestrationContext context = OrchestrationContext.forUser("user-1");
        PipelineContext pipelineContext = PipelineContext.from("Find SKU-1", context)
            .toBuilder()
            .orchestrationPolicy(policy())
            .build();

        ActionContextParamResolutionSupport.ResolvedActionParams resolved =
            new ActionContextParamResolutionSupport(registry)
                .resolveContextActionParams(meta, Map.of("sku", "SKU-1"), context, pipelineContext);

        assertThat(resolved.params()).containsEntry("productId", "product-123");
        assertThat(resolved.resolvedParameters()).containsExactly("productId");
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(handler).executeAction(paramsCaptor.capture(), any(ActionContext.class));
        assertThat(paramsCaptor.getValue()).containsEntry("query", "SKU-1");
    }

    @Test
    void shouldSurfaceBlockingReadActionResultWhenDependencyFails() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        AIActionMetaData readMeta = readMeta("lookup_product");
        AIActionMetaData meta = AIActionMetaData.builder()
            .parameterSchemas(Map.of(
                "productId", schema(Map.of(
                    "source", "READ_ACTION",
                    "actionName", "lookup_product",
                    "params", Map.of("query", "{{context.originalQuery}}"),
                    "resultPath", "productId"
                ))
            ))
            .build();
        ActionResult failure = ActionResult.builder()
            .success(false)
            .message("No matching product found.")
            .build();
        when(registry.findMetadata("lookup_product")).thenReturn(Optional.of(readMeta));
        when(registry.findHandler("lookup_product")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(any(ActionContext.class))).thenReturn(true);
        when(handler.executeAction(any(), any())).thenReturn(failure);

        OrchestrationContext context = OrchestrationContext.forUser("user-1");
        PipelineContext pipelineContext = PipelineContext.from("Find missing SKU", context)
            .toBuilder()
            .orchestrationPolicy(policy())
            .build();

        ActionContextParamResolutionSupport.ResolvedActionParams resolved =
            new ActionContextParamResolutionSupport(registry)
                .resolveContextActionParams(meta, Map.of(), context, pipelineContext);

        assertThat(resolved.params()).isEmpty();
        assertThat(resolved.blockingReadActionResult()).isNotNull();
        assertThat(resolved.blockingReadActionResult().actionName()).isEqualTo("lookup_product");
        assertThat(resolved.blockingReadActionResult().result()).isSameAs(failure);
    }

    private AIActionParamSchema schema(Map<String, Object> resolveFrom) {
        return AIActionParamSchema.builder()
            .resolveFrom(resolveFrom)
            .build();
    }

    private AIActionMetaData readMeta(String name) {
        return AIActionMetaData.builder()
            .name(name)
            .accessMode(ActionAccessMode.READ)
            .readActionResolutionEligible(true)
            .groundingEligible(true)
            .build();
    }

    private OrchestrationPolicy policy() {
        return new OrchestrationPolicy(
            OrchestrationProfile.DEFAULT,
            "navigator",
            null,
            null,
            OrchestrationPolicy.OrchestrationCapabilities.defaults(),
            new OrchestrationPolicy.ReadActionResolutionPolicy(
                true,
                OrchestrationProperties.ReadActionResolutionPlanningMode.SINGLE_PASS,
                List.of("lookup_product"),
                true,
                1,
                2,
                2,
                1,
                4_000,
                2_400,
                OrchestrationProperties.ReadActionResolutionRagCooperationMode.RAG_IF_ACTIONS_INSUFFICIENT,
                true
            ),
            OrchestrationPolicy.RagBudgets.defaults()
        );
    }
}
