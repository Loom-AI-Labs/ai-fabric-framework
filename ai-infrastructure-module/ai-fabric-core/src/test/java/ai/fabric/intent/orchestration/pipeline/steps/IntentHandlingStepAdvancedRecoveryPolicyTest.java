package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.config.PostActionGenerationProperties;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.config.RelationshipQueryPostActionGenerationProperties;
import ai.fabric.config.VectorSpaceRoutingProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AdvancedRAGResponse;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
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
import ai.fabric.intent.orchestration.policy.SemanticRecoveryPolicy;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentHandlingStepAdvancedRecoveryPolicyTest {

    @Test
    void shouldNotBroadenNoEvidenceWithoutAnExplicitPositiveFanOutBudget() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        AdvancedRAGProvider advancedProvider = noEvidenceAdvancedProvider();
        IntentHandlingStep step = step(ragProvider, advancedProvider);
        OrchestrationPolicy.RagBudgets budgets = new OrchestrationPolicy.RagBudgets(
            true, null, 2, 5, 5, 4000, List.of("product", "policy")
        );

        OrchestrationResult result = step.process(context(budgets)).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(String.valueOf(result.getMetadata()))
            .contains("EXPLICIT_FAN_OUT_BUDGET_REQUIRED")
            .contains("recoveryAttempted=false");
        verify(ragProvider, never()).performRAGQuery(any(RAGRequest.class));
    }

    @Test
    void shouldRunAndExposeOnlyExplicitlyBoundedNoEvidenceRecovery() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRAGQuery(any())).thenAnswer(invocation -> {
            RAGRequest request = invocation.getArgument(0);
            return RAGResponse.builder()
                .success(true)
                .documents(List.of(RAGResponse.RAGDocument.builder()
                    .id(request.getEntityType() + "-1")
                    .type(request.getEntityType())
                    .content("Grounded " + request.getEntityType() + " evidence")
                    .score(0.9d)
                    .build()))
                .build();
        });
        IntentHandlingStep step = step(ragProvider, noEvidenceAdvancedProvider());
        OrchestrationPolicy.RagBudgets budgets = new OrchestrationPolicy.RagBudgets(
            true, 2, 2, 5, 5, 4000, List.of("product", "policy")
        );

        OrchestrationResult result = step.process(context(budgets)).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData())
            .containsEntry("candidateVectorSpaces", List.of("product", "policy"))
            .containsEntry("vectorSpaceRoutingStrategy", "FAN_OUT");
        assertThat(String.valueOf(result.getMetadata()))
            .contains("BOUNDED_ALLOWED_SPACE_FAN_OUT")
            .contains("recoveryAttempted=true");
        verify(ragProvider, times(2)).performRAGQuery(any(RAGRequest.class));
    }

    private IntentHandlingStep step(RAGProvider ragProvider, AdvancedRAGProvider advancedProvider) {
        AIServiceConfig serviceConfig = new AIServiceConfig();
        serviceConfig.getFeatures().setEnableGeneration(true);
        serviceConfig.getFeatures().setEnableAdvancedRAG(true);
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("Grounded response").build());

        return new IntentHandlingStep(
            mock(AIActionRegistry.class),
            providerOf(ragProvider),
            aiCoreService,
            serviceConfig,
            providerOf(advancedProvider),
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

    private PipelineContext context(OrchestrationPolicy.RagBudgets budgets) {
        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("Compare product policy")
            .optimizedQuery("Compare product policy")
            .requiresRetrieval(true)
            .requiresGeneration(true)
            .requiresTargetResolution(false)
            .needsAdvancedRAG(true)
            .vectorSpace("product")
            .build();
        OrchestrationPolicy.OrchestrationCapabilities capabilities =
            new OrchestrationPolicy.OrchestrationCapabilities(
                false, true, true, false, false, false, false,
                true, false, false, false, false, false
            );
        SemanticRecoveryPolicy recovery = new SemanticRecoveryPolicy(
            null,
            null,
            null,
            null,
            null,
            null,
            OrchestrationProperties.AdvancedNoEvidenceRecovery.BOUNDED_ALLOWED_SPACE_RECOVERY
        );
        OrchestrationPolicy policy = new OrchestrationPolicy(
            OrchestrationProfile.PRODUCTION_CHAT,
            "search",
            "search",
            OrchestrationProperties.InformationMode.LLM_DRIVEN,
            capabilities,
            OrchestrationPolicy.ReadActionResolutionPolicy.defaults(),
            budgets,
            OrchestrationPolicy.ResponseGenerationBudgets.defaults(),
            recovery
        );
        OrchestrationContext request = OrchestrationContext.forUser("user-1");
        return PipelineContext.from("Compare product policy", request)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .orchestrationPolicy(policy)
            .build();
    }

    private AdvancedRAGProvider noEvidenceAdvancedProvider() {
        AdvancedRAGProvider provider = mock(AdvancedRAGProvider.class);
        when(provider.performAdvancedRAG(any())).thenReturn(
            AdvancedRAGResponse.builder()
                .success(true)
                .documents(List.of())
                .confidenceScore(0.0d)
                .build()
        );
        return provider;
    }

    private PromptTemplateResolver promptTemplateResolver() {
        return new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new PromptBundleProperties()
        );
    }

    private <T> ObjectProvider<T> providerOf(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
