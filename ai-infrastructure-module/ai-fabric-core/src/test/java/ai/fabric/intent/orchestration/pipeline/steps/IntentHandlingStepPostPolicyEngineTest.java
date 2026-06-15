package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.config.PostActionGenerationProperties;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.config.RelationshipQueryPostActionGenerationProperties;
import ai.fabric.config.VectorSpaceRoutingProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.KnowledgeBaseOverviewService;
import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.InMemoryPendingActionStore;
import ai.fabric.intent.action.policy.ActionPostPolicyEngine;
import ai.fabric.intent.actiondraft.InMemoryActionDraftStore;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.vectorspace.RankBasedMerger;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.spi.AdvancedRAGProvider;
import ai.fabric.spi.RAGProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentHandlingStepPostPolicyEngineTest {

    @Test
    void successfulActionInvokesPostPolicyEngine() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        ActionPostPolicyEngine engine = mock(ActionPostPolicyEngine.class);
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name("cancel_order")
            .description("Cancel an order")
            .category("test")
            .accessMode(ActionAccessMode.WRITE_ONLY)
            .build();

        when(registry.findHandler("cancel_order")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("cancel_order")).thenReturn(Optional.of(metadata));
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(false);
        when(handler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder().success(true).message("Cancelled").build());

        IntentHandlingStep step = newStep(registry, engine);
        OrchestrationResult result = step.process(actionContext("cancel_order")).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();
        verify(engine).handleSuccessfulAction(any(), anyMap(), any(), any());
    }

    @Test
    void postPolicyEngineFailureDoesNotFlipSuccessfulAction() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        ActionPostPolicyEngine engine = mock(ActionPostPolicyEngine.class);
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name("cancel_order")
            .description("Cancel an order")
            .category("test")
            .accessMode(ActionAccessMode.WRITE_ONLY)
            .build();

        when(registry.findHandler("cancel_order")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("cancel_order")).thenReturn(Optional.of(metadata));
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(false);
        when(handler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder().success(true).message("Cancelled").build());
        doThrow(new IllegalStateException("queue unavailable")).when(engine).handleSuccessfulAction(any(), anyMap(), any(), any());

        IntentHandlingStep step = newStep(registry, engine);
        OrchestrationResult result = step.process(actionContext("cancel_order")).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Cancelled");
    }

    private IntentHandlingStep newStep(AIActionRegistry registry, ActionPostPolicyEngine engine) {
        IntentHandlingStep step = new IntentHandlingStep(
            registry,
            providerOf(mock(RAGProvider.class)),
            mock(AICoreService.class),
            mock(AIServiceConfig.class),
            providerOf((AdvancedRAGProvider) null),
            new VectorSpaceRoutingProperties(),
            new RankBasedMerger(),
            new RelationshipQueryPostActionGenerationProperties(),
            new PostActionGenerationProperties(),
            providerOf(new ObjectMapper()),
            new OrchestrationProperties(),
            providerOf((KnowledgeBaseOverviewService) null),
            null,
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );
        ReflectionTestUtils.setField(step, "actionPostPolicyEngineProvider", providerOf(engine));
        return step;
    }

    private PipelineContext actionContext(String actionName) {
        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action(actionName)
            .actionParams(java.util.Map.of("orderId", "O-100"))
            .build();
        return PipelineContext.from("Cancel order O-100", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();
    }

    private <T> ObjectProvider<T> providerOf(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private PromptTemplateResolver promptTemplateResolver() {
        return new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new PromptBundleProperties()
        );
    }
}
