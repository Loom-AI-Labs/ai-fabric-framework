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
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.intent.KnowledgeBaseOverviewService;
import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.InMemoryPendingActionStore;
import ai.fabric.intent.actiondraft.InMemoryActionDraftStore;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import ai.fabric.intent.vectorspace.RankBasedMerger;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.spi.AdvancedRAGProvider;
import ai.fabric.spi.RAGProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentHandlingStepAnonymousActionPolicyTest {

    @Test
    void shouldAllowAnonymousGuestSafeActionsToExecute() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name("browse_catalog")
            .description("Browse catalog")
            .category("test")
            .accessMode(ActionAccessMode.READ)
            .anonymousAllowed(true)
            .build();

        when(registry.findHandler("browse_catalog")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("browse_catalog")).thenReturn(Optional.of(metadata));
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(false);
        when(handler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder()
            .success(true)
            .message("Catalog")
            .build());

        IntentHandlingStep step = newStep(registry);

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("browse_catalog")
            .build();

        PipelineContext context = PipelineContext.from("Browse the catalog", OrchestrationContext.anonymous())
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Catalog");
        verify(handler).executeAction(anyMap(), any());
    }

    @Test
    void shouldKeepSensitiveActionsDeniedForAnonymousUsers() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name("manage_account")
            .description("Manage account")
            .category("test")
            .accessMode(ActionAccessMode.WRITE_ONLY)
            .anonymousAllowed(false)
            .build();
        when(registry.findHandler("manage_account")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("manage_account")).thenReturn(Optional.of(metadata));
        IntentHandlingStep step = newStep(registry);

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("manage_account")
            .build();

        PipelineContext context = PipelineContext.from("Manage my account", OrchestrationContext.anonymous())
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_DENIED);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("Action not permitted for anonymous users.");
        verify(handler, never()).executeAction(anyMap(), any());
    }

    @Test
    void shouldAllowTrustedApplicationCallerWithoutFabricatedUserId() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name("get_account_profile")
            .description("Read the trusted current account")
            .category("account")
            .accessMode(ActionAccessMode.READ)
            .anonymousAllowed(false)
            .build();
        when(registry.findHandler("get_account_profile")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("get_account_profile")).thenReturn(Optional.of(metadata));
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(false);
        when(handler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder()
            .success(true)
            .message("Current account profile")
            .build());

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("get_account_profile")
            .build();
        TrustedExecutionContext trustedContext = new TrustedExecutionContext(
            new ExecutionPrincipal(
                "account-resolution-service",
                ExecutionPrincipalType.SERVICE
            ),
            new ExecutionSubjectRef("account", "account-42"),
            ExecutionSource.APPLICATION,
            "tenant-1",
            "resolver-app",
            Set.of("action:get_account_profile"),
            "correlation-1",
            null
        );
        PipelineContext context = PipelineContext.from(new OrchestrationRequest(
            "Review the account",
            OrchestrationContext.builder().build(),
            trustedContext,
            ConversationPersistencePolicy.NEVER
        )).toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = newStep(registry).process(context).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();
        verify(handler).executeAction(anyMap(), any());
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
