package com.ai.infrastructure.intent.orchestration.pipeline.steps;

import com.ai.infrastructure.config.AIServiceConfig;
import com.ai.infrastructure.config.OrchestrationProperties;
import com.ai.infrastructure.config.PostActionGenerationProperties;
import com.ai.infrastructure.config.PromptBundleProperties;
import com.ai.infrastructure.config.RelationshipQueryPostActionGenerationProperties;
import com.ai.infrastructure.config.VectorSpaceRoutingProperties;
import com.ai.infrastructure.core.AICoreService;
import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.dto.IntentType;
import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.intent.KnowledgeBaseOverviewService;
import com.ai.infrastructure.intent.action.AIActionHandler;
import com.ai.infrastructure.intent.action.AIActionMetaData;
import com.ai.infrastructure.intent.action.AIActionRegistry;
import com.ai.infrastructure.intent.action.ActionAccessMode;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.action.InMemoryPendingActionStore;
import com.ai.infrastructure.intent.actiondraft.InMemoryActionDraftStore;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.OrchestrationResult;
import com.ai.infrastructure.intent.orchestration.OrchestrationResultType;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import com.ai.infrastructure.intent.orchestration.policy.OrchestrationPolicy;
import com.ai.infrastructure.intent.orchestration.policy.OrchestrationProfile;
import com.ai.infrastructure.intent.vectorspace.RankBasedMerger;
import com.ai.infrastructure.prompt.ClasspathPromptTemplateStore;
import com.ai.infrastructure.prompt.PromptRenderer;
import com.ai.infrastructure.prompt.PromptTemplateResolver;
import com.ai.infrastructure.spi.AdvancedRAGProvider;
import com.ai.infrastructure.spi.RAGProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentHandlingStepReadActionPolicyToleranceTest {

    @Test
    void shouldAllowPlannerEligibleReadActionsWhenModeKeepsGeneralActionsDisabled() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name("check_availability")
            .description("Check product availability by SKU.")
            .category("commerce")
            .accessMode(ActionAccessMode.READ)
            .anonymousAllowed(true)
            .groundingEligible(true)
            .readActionResolutionEligible(true)
            .requiredParameters(Set.of("sku"))
            .build();

        when(registry.findHandler("check_availability")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("check_availability")).thenReturn(Optional.of(metadata));
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(false);
        when(handler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder()
            .success(true)
            .message("Availability")
            .build());

        IntentHandlingStep step = newStep(registry);
        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("check_availability")
            .actionParams(Map.of("sku", "SKU-AAA-100"))
            .build();

        PipelineContext context = PipelineContext.from(
                "Check if SKU-AAA-100 is available",
                OrchestrationContext.anonymous()
            )
            .toBuilder()
            .orchestrationPolicy(resolverAssistantPolicy(List.of("check_availability")))
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Availability");
        verify(handler).executeAction(anyMap(), any());
    }

    @Test
    void shouldKeepNonAllowlistedActionsBlockedWhenGeneralActionsAreDisabled() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name("cancel_order")
            .description("Cancel an order.")
            .category("orders")
            .accessMode(ActionAccessMode.WRITE_ONLY)
            .anonymousAllowed(false)
            .groundingEligible(false)
            .readActionResolutionEligible(false)
            .requiredParameters(Set.of("orderId"))
            .build();

        when(registry.findHandler("cancel_order")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("cancel_order")).thenReturn(Optional.of(metadata));

        IntentHandlingStep step = newStep(registry);
        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("cancel_order")
            .actionParams(Map.of("orderId", "ord-123"))
            .build();

        PipelineContext context = PipelineContext.from(
                "Cancel order ord-123",
                OrchestrationContext.forUser("user-1")
            )
            .toBuilder()
            .orchestrationPolicy(resolverAssistantPolicy(List.of("check_availability")))
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("Mutating actions are not enabled in this conversation mode. I can still answer factual questions from configured knowledge and read-only live evidence.");
        assertThat(result.getData()).containsEntry("reason", "ACTIONS_DISABLED_BY_POLICY");
        verify(handler, never()).executeAction(anyMap(), any());
    }

    private IntentHandlingStep newStep(AIActionRegistry registry) {
        return new IntentHandlingStep(
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
    }

    private OrchestrationPolicy resolverAssistantPolicy(List<String> allowedReadActions) {
        return new OrchestrationPolicy(
            OrchestrationProfile.PRODUCTION_CHAT,
            "resolver_assistant",
            null,
            OrchestrationProperties.InformationMode.LLM_DRIVEN,
            new OrchestrationPolicy.OrchestrationCapabilities(false, true, false, false, false, false, true, false, false, true, false, false),
            new OrchestrationPolicy.ReadActionResolutionPolicy(
                true,
                OrchestrationProperties.ReadActionResolutionPlanningMode.SINGLE_PASS,
                allowedReadActions,
                true,
                1,
                2,
                2,
                1,
                4000,
                1200,
                OrchestrationProperties.ReadActionResolutionRagCooperationMode.RAG_IF_ACTIONS_INSUFFICIENT,
                true
            ),
            OrchestrationPolicy.RagBudgets.defaults()
        );
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
