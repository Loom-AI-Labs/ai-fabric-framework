package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.config.PostActionGenerationProperties;
import ai.fabric.config.RelationshipQueryPostActionGenerationProperties;
import ai.fabric.config.VectorSpaceRoutingProperties;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.KnowledgeBaseOverviewService;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.InMemoryPendingActionStore;
import ai.fabric.intent.actiondraft.InMemoryActionDraftStore;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
import ai.fabric.intent.vectorspace.RankBasedMerger;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.spi.AdvancedRAGProvider;
import ai.fabric.spi.RAGProvider;
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
