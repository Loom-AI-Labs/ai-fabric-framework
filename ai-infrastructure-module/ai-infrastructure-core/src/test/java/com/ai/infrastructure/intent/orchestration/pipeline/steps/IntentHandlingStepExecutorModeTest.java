package com.ai.infrastructure.intent.orchestration.pipeline.steps;

import com.ai.infrastructure.config.AIServiceConfig;
import com.ai.infrastructure.config.OrchestrationProperties;
import com.ai.infrastructure.config.PostActionGenerationProperties;
import com.ai.infrastructure.config.RelationshipQueryPostActionGenerationProperties;
import com.ai.infrastructure.config.VectorSpaceRoutingProperties;
import com.ai.infrastructure.config.PromptBundleProperties;
import com.ai.infrastructure.core.AICoreService;
import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.dto.IntentType;
import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.intent.KnowledgeBaseOverviewService;
import com.ai.infrastructure.intent.action.AIActionRegistry;
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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentHandlingStepExecutorModeTest {

    @Test
    void shouldReturnClarificationWhenRetrievalAllowlistIsRequiredButMissing() {
        IntentHandlingStep step = new IntentHandlingStep(
            mock(AIActionRegistry.class),
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

        OrchestrationPolicy policy = new OrchestrationPolicy(
            OrchestrationProfile.PRODUCTION_CHAT,
            "executor",
            null,
            OrchestrationProperties.InformationMode.LLM_DRIVEN,
            new OrchestrationPolicy.OrchestrationCapabilities(true, true, false, false, false, true, false, true, false, true, false, false),
            new OrchestrationPolicy.RagBudgets(null, null, null, null, null, null, List.of())
        );

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("refund_policy")
            .requiresRetrieval(true)
            .requiresGeneration(false)
            .build();

        PipelineContext context = PipelineContext.from("What is the refund policy?", OrchestrationContext.forUser("user"))
            .toBuilder()
            .orchestrationPolicy(policy)
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getData()).containsEntry("reason", "RETRIEVAL_ALLOWLIST_REQUIRED");
    }

    @Test
    void shouldReturnClarificationWhenVectorSpaceSelectionIsRequiredAndLlmOmittedIt() {
        IntentHandlingStep step = new IntentHandlingStep(
            mock(AIActionRegistry.class),
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

        OrchestrationPolicy policy = new OrchestrationPolicy(
            OrchestrationProfile.PRODUCTION_CHAT,
            "executor",
            null,
            OrchestrationProperties.InformationMode.LLM_DRIVEN,
            new OrchestrationPolicy.OrchestrationCapabilities(true, true, false, false, false, true, false, false, true, true, false, false),
            new OrchestrationPolicy.RagBudgets(null, null, null, null, null, null, List.of("policy", "product"))
        );

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("refund_policy")
            .requiresRetrieval(true)
            .requiresGeneration(false)
            .vectorSpace(null)
            .build();

        PipelineContext context = PipelineContext.from("What is the refund policy?", OrchestrationContext.forUser("user"))
            .toBuilder()
            .orchestrationPolicy(policy)
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getData()).containsEntry("reason", "VECTOR_SPACE_REQUIRED_BY_POLICY");
        assertThat(result.getData()).containsEntry("allowedVectorSpaces", List.of("policy", "product"));
    }

    @Test
    void shouldReturnClarificationWhenRequestedVectorSpaceIsDeniedByAllowlist() {
        IntentHandlingStep step = new IntentHandlingStep(
            mock(AIActionRegistry.class),
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

        OrchestrationPolicy policy = new OrchestrationPolicy(
            OrchestrationProfile.PRODUCTION_CHAT,
            "executor",
            null,
            OrchestrationProperties.InformationMode.LLM_DRIVEN,
            new OrchestrationPolicy.OrchestrationCapabilities(true, true, false, false, false, true, false, true, false, true, false, false),
            new OrchestrationPolicy.RagBudgets(null, null, null, null, null, null, List.of("policy"))
        );

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("search_products")
            .requiresRetrieval(true)
            .requiresGeneration(false)
            .vectorSpace("product")
            .build();

        PipelineContext context = PipelineContext.from("Find laptops under $1000", OrchestrationContext.forUser("user"))
            .toBuilder()
            .orchestrationPolicy(policy)
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getData()).containsEntry("reason", "VECTOR_SPACE_NOT_ALLOWED_BY_POLICY");
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
