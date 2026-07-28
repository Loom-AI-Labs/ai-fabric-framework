package ai.fabric.intent.orchestration.pipeline.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.InMemoryPendingActionStore;
import ai.fabric.intent.actiondraft.InMemoryActionDraftStore;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import ai.fabric.intent.orchestration.request.OrchestrationRequestPurpose;
import ai.fabric.intent.vectorspace.RankBasedMerger;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.spi.AdvancedRAGProvider;
import ai.fabric.spi.RAGProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;

class IntentHandlingStepSpecialistProposalTest {

    @Test
    void emitsInternalProposalWithoutExecutingSpecialistWrite() throws Exception {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name("update_address")
            .accessMode(ActionAccessMode.WRITE_ONLY)
            .confirmationRequired(true)
            .requiredParameters(Set.of("streetAddress"))
            .parameterSchemas(Map.of(
                "streetAddress",
                AIActionParamSchema.builder()
                    .name("streetAddress")
                    .type(AIActionParamType.STRING)
                    .required(true)
                    .build()
            ))
            .build();
        when(registry.findHandler("update_address"))
            .thenReturn(Optional.of(handler));
        when(registry.findMetadata("update_address"))
            .thenReturn(Optional.of(metadata));
        when(handler.requiresConfirmation()).thenReturn(true);
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.getConfirmationMessage(anyMap(), any()))
            .thenReturn("Update your billing address?");

        IntentHandlingStep step = newStep(registry);
        OrchestrationContext orchestrationContext =
            OrchestrationContext.forUser("user-1");
        OrchestrationRequest request = new OrchestrationRequest(
            "Update my address to 1 Main Street",
            orchestrationContext,
            null,
            ConversationPersistencePolicy.NEVER,
            null,
            null,
            null,
            OrchestrationRequestPurpose.SPECIALIST
        );
        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .intent("update_address")
            .action("update_address")
            .actionParams(Map.of("streetAddress", "1 Main Street"))
            .build();
        EffectiveCapabilityProfile profile = new EffectiveCapabilityProfile(
            "DEFAULT",
            "resolver",
            false,
            Set.of(),
            Set.of("update_address"),
            Set.of(),
            Set.of("update_address"),
            OrchestrationPolicy.RagBudgets.defaults(),
            OrchestrationPolicy.ReadActionResolutionPolicy.defaults(),
            "specialist-profile"
        );
        OrchestrationPolicy policy = new OrchestrationPolicy(
            OrchestrationProfile.DEFAULT,
            "resolver",
            null,
            OrchestrationProperties.InformationMode.LLM_DRIVEN,
            OrchestrationPolicy.OrchestrationCapabilities.defaults(),
            OrchestrationPolicy.RagBudgets.defaults()
        );
        PipelineContext context = PipelineContext.from(request)
            .toBuilder()
            .orchestrationPolicy(policy)
            .effectiveCapabilityProfile(profile)
            .intentResponse(MultiIntentResponse.builder()
                .intents(List.of(intent))
                .build())
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();

        assertThat(result.getType())
            .isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        assertThat(result.getActionProposalCandidate()).satisfies(candidate -> {
            assertThat(candidate.actionName()).isEqualTo("update_address");
            assertThat(candidate.parameters())
                .containsEntry("streetAddress", "1 Main Street");
            assertThat(candidate.toString())
                .doesNotContain("1 Main Street");
        });
        assertThat(new ObjectMapper().writeValueAsString(result))
            .doesNotContain(
                "actionProposalCandidate",
                "providedParameters",
                "1 Main Street"
            );
        verify(handler, never()).executeAction(anyMap(), any());
    }

    @Test
    void deniesSpecialistWriteOutsideEffectiveProfileWithoutExecutingIt() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name("update_address")
            .accessMode(ActionAccessMode.WRITE_ONLY)
            .confirmationRequired(true)
            .requiredParameters(Set.of("streetAddress"))
            .parameterSchemas(Map.of(
                "streetAddress",
                AIActionParamSchema.builder()
                    .name("streetAddress")
                    .type(AIActionParamType.STRING)
                    .required(true)
                    .build()
            ))
            .build();
        when(registry.findHandler("update_address"))
            .thenReturn(Optional.of(handler));
        when(registry.findMetadata("update_address"))
            .thenReturn(Optional.of(metadata));
        when(handler.requiresConfirmation()).thenReturn(true);
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.getConfirmationMessage(anyMap(), any()))
            .thenReturn("Update your billing address?");

        OrchestrationContext orchestrationContext =
            OrchestrationContext.forUser("user-1");
        OrchestrationRequest request = new OrchestrationRequest(
            "Update my address to 1 Main Street",
            orchestrationContext,
            null,
            ConversationPersistencePolicy.NEVER,
            null,
            null,
            null,
            OrchestrationRequestPurpose.SPECIALIST
        );
        EffectiveCapabilityProfile profile = new EffectiveCapabilityProfile(
            "DEFAULT",
            "resolver",
            false,
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of(),
            OrchestrationPolicy.RagBudgets.defaults(),
            OrchestrationPolicy.ReadActionResolutionPolicy.defaults(),
            "read-only-profile"
        );
        PipelineContext context = PipelineContext.from(request)
            .toBuilder()
            .orchestrationPolicy(new OrchestrationPolicy(
                OrchestrationProfile.DEFAULT,
                "resolver",
                null,
                OrchestrationProperties.InformationMode.LLM_DRIVEN,
                OrchestrationPolicy.OrchestrationCapabilities.defaults(),
                OrchestrationPolicy.RagBudgets.defaults()
            ))
            .effectiveCapabilityProfile(profile)
            .intentResponse(MultiIntentResponse.builder()
                .intents(List.of(Intent.builder()
                    .type(IntentType.ACTION)
                    .action("update_address")
                    .actionParams(Map.of(
                        "streetAddress",
                        "1 Main Street"
                    ))
                    .build()))
                .build())
            .build();

        OrchestrationResult result = newStep(registry)
            .process(context)
            .getIntentResult();

        assertThat(result.getType())
            .isEqualTo(OrchestrationResultType.ACTION_DENIED);
        assertThat(result.getErrorCode())
            .isEqualTo("ACTION_NOT_IN_EFFECTIVE_PROFILE");
        assertThat(result.getActionProposalCandidate()).isNull();
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
            new PromptTemplateResolver(
                new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
                new PromptBundleProperties()
            ),
            new PromptRenderer()
        );
    }

    private <T> ObjectProvider<T> providerOf(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
