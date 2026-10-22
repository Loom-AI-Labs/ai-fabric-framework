package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.access.AIAccessControlService;
import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.config.OrchestrationResultNormalizationProperties;
import ai.fabric.config.PostActionGenerationProperties;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.config.RelationshipQueryPostActionGenerationProperties;
import ai.fabric.config.VectorSpaceRoutingProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.dto.AIAccessControlRequest;
import ai.fabric.dto.AIAccessControlResponse;
import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.dto.AISecurityRequest;
import ai.fabric.dto.AISecurityResponse;
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
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.PendingActionStore;
import ai.fabric.intent.actiondraft.ActionDraftStore;
import ai.fabric.intent.orchestration.OrchestrationAuthContextResolver;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationContextMetadataKeys;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.control.OrchestrationTurnGuard;
import ai.fabric.intent.orchestration.conversation.ApprovedConversationSnapshot;
import ai.fabric.intent.orchestration.conversation.ConversationStateBinding;
import ai.fabric.intent.orchestration.conversation.ConversationStateScope;
import ai.fabric.intent.orchestration.conversation.ConversationStateScopeResolver;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationIntentPolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import ai.fabric.intent.orchestration.request.OrchestrationRequestPurpose;
import ai.fabric.intent.vectorspace.RankBasedMerger;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.security.AISecurityService;
import ai.fabric.spi.AdvancedRAGProvider;
import ai.fabric.spi.RAGProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrustedAnonymousConversationAuthenticationTest {

    @Test
    void canonicalAnonymousOwnerCannotExecuteProtectedActionAndKeepsBindingAndFence() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = registerAction(registry, false);
        PipelineContext context = canonicalContext(ExecutionPrincipalType.ANONYMOUS_SESSION);
        var request = context.getOrchestrationRequest();
        var guard = context.getOrchestrationContext().getOrchestrationTurnGuard();

        PipelineContext handled = newStep(registry).process(context);
        var result = new MetadataBuildingStep(new OrchestrationResultNormalizationProperties())
            .process(handled).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_DENIED);
        assertThat(result.getErrorCode()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(result.getMetadata()).containsEntry("authenticated", false);
        assertThat(handled.isAuthenticated()).isFalse();
        assertThat(handled.isAnonymous()).isTrue();
        assertThat(new ActionContext(handled.getOrchestrationContext(), handled).isAnonymous()).isTrue();
        assertThat(handled.getOrchestrationRequest()).isSameAs(request);
        assertThat(handled.getOrchestrationContext().getOrchestrationTurnGuard()).isSameAs(guard);
        assertThat(ConversationStateScopeResolver.resolve(handled)).contains(
            new ConversationStateScope("tenant-1", "deployment-1", "conversation-1", "owner-1", 7L)
        );
        verify(handler, never()).validateActionAllowed(any());
        verify(handler, never()).executeAction(anyMap(), any());
    }

    @Test
    void canonicalAnonymousOwnerCanStillExecuteExplicitlyGuestSafeAction() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = registerAction(registry, true);
        allowMockExecution(handler);

        var result = newStep(registry)
            .process(canonicalContext(ExecutionPrincipalType.ANONYMOUS_SESSION)).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        verify(handler).executeAction(anyMap(), any());
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionPrincipalType.class, names = {"END_USER", "SERVICE", "SYSTEM"})
    void authenticatedPrincipalsRetainProtectedActionBehavior(ExecutionPrincipalType principalType) {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = registerAction(registry, false);
        allowMockExecution(handler);
        PipelineContext context = canonicalContext(principalType);

        var result = newStep(registry).process(context).getIntentResult();

        assertThat(context.isAuthenticated()).isTrue();
        assertThat(context.isAnonymous()).isFalse();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        verify(handler).executeAction(anyMap(), any());
    }

    @Test
    void anonymousAuthProjectionUsesVerifiedInitiatorNotConversationSubject() {
        TrustedExecutionContext trusted = trusted(ExecutionPrincipalType.ANONYMOUS_SESSION);

        AIAccessSubjectContext auth = OrchestrationAuthContextResolver.from(trusted);

        assertThat(trusted.isAuthenticated()).isFalse();
        assertThat(auth.getSubjectId()).isEqualTo("owner-1");
        assertThat(auth.getSessionId()).isEqualTo("owner-1");
        assertThat(auth.getSubjectType()).isEqualTo("ANONYMOUS_SESSION");
        assertThat(auth.getAuthMode()).isEqualTo("TRUSTED_INTERACTIVE");
        assertThat(auth.getCallerType()).isEqualTo("ANONYMOUS_SESSION");
        assertThat(auth.getTenantId()).isEqualTo("tenant-1");
        assertThat(auth.getDeploymentId()).isEqualTo("deployment-1");
        assertThat(auth.getGrantedScopes()).containsExactly("chat:query");
        assertThat(auth.getIssuer()).isNull();
        assertThat(auth.getAudiences()).isNull();
        assertThat(auth.getExpiresAt()).isNull();
    }

    @Test
    void legacyUserAndMetadataCannotUpgradeTrustedAnonymousIdentity() {
        TrustedExecutionContext trusted = trusted(ExecutionPrincipalType.ANONYMOUS_SESSION);
        OrchestrationContext legacy = OrchestrationContext.builder()
            .userId("legacy-user")
            .sessionId("owner-1")
            .metadata(Map.of(
                "authenticated", true,
                "principalType", "END_USER",
                OrchestrationContextMetadataKeys.SUBJECT_ID, "legacy-user",
                OrchestrationContextMetadataKeys.SUBJECT_TYPE, "END_USER",
                OrchestrationContextMetadataKeys.AUTH_MODE, "spoofed",
                OrchestrationContextMetadataKeys.CALLER_TYPE, "SYSTEM",
                OrchestrationContextMetadataKeys.TENANT_ID, "wrong-tenant",
                OrchestrationContextMetadataKeys.GRANTED_SCOPES, List.of("admin:all")
            ))
            .build();
        PipelineContext context = PipelineContext.from(new OrchestrationRequest(
            "Inspect", legacy, trusted, ConversationPersistencePolicy.READ_ONLY
        ));
        AIAccessControlService access = mock(AIAccessControlService.class);
        when(access.checkAccess(any())).thenReturn(AIAccessControlResponse.builder().accessGranted(true).build());
        AISecurityService security = mock(AISecurityService.class);
        when(security.analyzeRequest(any())).thenReturn(AISecurityResponse.builder().shouldBlock(false).build());

        new AccessControlStep(access).process(context);
        new SecurityAnalysisStep(security).process(context);

        assertThat(legacy.isAuthenticated()).isTrue();
        assertThat(context.isAuthenticated()).isFalse();
        assertThat(new ActionContext(legacy, context).isAuthenticated()).isFalse();
        ArgumentCaptor<AIAccessControlRequest> accessCaptor = ArgumentCaptor.forClass(AIAccessControlRequest.class);
        verify(access).checkAccess(accessCaptor.capture());
        ArgumentCaptor<AISecurityRequest> securityCaptor = ArgumentCaptor.forClass(AISecurityRequest.class);
        verify(security).analyzeRequest(securityCaptor.capture());
        AIAccessSubjectContext expected = OrchestrationAuthContextResolver.from(trusted);
        assertThat(accessCaptor.getValue().getAuthContext()).isEqualTo(expected);
        assertThat(securityCaptor.getValue().getAuthContext()).isEqualTo(expected);
        assertThat(accessCaptor.getValue().getMetadata())
            .containsEntry("authenticated", false)
            .containsEntry(OrchestrationContextMetadataKeys.SUBJECT_ID, "owner-1")
            .containsEntry(OrchestrationContextMetadataKeys.SUBJECT_TYPE, "ANONYMOUS_SESSION")
            .containsEntry(OrchestrationContextMetadataKeys.AUTH_MODE, "TRUSTED_INTERACTIVE")
            .containsEntry(OrchestrationContextMetadataKeys.CALLER_TYPE, "ANONYMOUS_SESSION")
            .containsEntry(OrchestrationContextMetadataKeys.TENANT_ID, "tenant-1")
            .containsEntry(OrchestrationContextMetadataKeys.GRANTED_SCOPES, List.of("chat:query"));
        assertThat(securityCaptor.getValue().getMetadata())
            .containsEntry("authenticated", false)
            .containsEntry("principalType", "ANONYMOUS_SESSION");
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionSource.class, names = "INTERACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void anonymousPrincipalCannotUseMachineExecutionSource(ExecutionSource source) {
        assertThatThrownBy(() -> new TrustedExecutionContext(
            new ExecutionPrincipal("owner-1", ExecutionPrincipalType.ANONYMOUS_SESSION),
            new ExecutionSubjectRef("conversation", "conversation-1"),
            source, "tenant-1", "deployment-1", Set.of("chat:query"), "correlation-1", null
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SERVICE or SYSTEM");
    }

    private PipelineContext canonicalContext(ExecutionPrincipalType principalType) {
        OrchestrationContext orchestration = principalType == ExecutionPrincipalType.ANONYMOUS_SESSION
            ? OrchestrationContext.forSession("owner-1")
            : OrchestrationContext.forUser("owner-1");
        orchestration.setConversationId("conversation-1");
        orchestration.setOrchestrationTurnGuard(mock(OrchestrationTurnGuard.class));
        orchestration.setApprovedConversationSnapshot(new ApprovedConversationSnapshot(
            "turn-1", "owner-1", "conversation-1", "specialist-1", "a".repeat(64),
            0L, List.of(), Instant.parse("2026-10-22T10:00:00Z")
        ));
        return PipelineContext.from(new OrchestrationRequest(
            "Inspect", orchestration, trusted(principalType), ConversationPersistencePolicy.READ_ONLY,
            null, null, null, OrchestrationRequestPurpose.GENERAL, OrchestrationIntentPolicy.MODEL_DIRECTED,
            new ConversationStateBinding("tenant-1", "deployment-1", "conversation-1", "owner-1", 7L)
        )).toBuilder().intentResponse(MultiIntentResponse.builder().intents(List.of(
            Intent.builder().type(IntentType.ACTION).action("manage_account").build()
        )).build()).build();
    }

    private TrustedExecutionContext trusted(ExecutionPrincipalType principalType) {
        ExecutionSource source = principalType == ExecutionPrincipalType.END_USER
            || principalType == ExecutionPrincipalType.ANONYMOUS_SESSION
            ? ExecutionSource.INTERACTIVE : ExecutionSource.APPLICATION;
        return new TrustedExecutionContext(
            new ExecutionPrincipal("owner-1", principalType),
            new ExecutionSubjectRef("conversation", "conversation-1"),
            source, "tenant-1", "deployment-1", Set.of("chat:query"), "correlation-1", null
        );
    }

    private AIActionHandler registerAction(AIActionRegistry registry, boolean anonymousAllowed) {
        AIActionHandler handler = mock(AIActionHandler.class);
        when(registry.findHandler("manage_account")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("manage_account")).thenReturn(Optional.of(AIActionMetaData.builder()
            .name("manage_account").description("Manage account").category("test")
            .accessMode(ActionAccessMode.WRITE_ONLY).anonymousAllowed(anonymousAllowed).build()));
        return handler;
    }

    private void allowMockExecution(AIActionHandler handler) {
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(false);
        when(handler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder()
            .success(true).message("Mock only; no external write").build());
    }

    private IntentHandlingStep newStep(AIActionRegistry registry) {
        return new IntentHandlingStep(
            registry, providerOf(mock(RAGProvider.class)), mock(AICoreService.class), mock(AIServiceConfig.class),
            providerOf((AdvancedRAGProvider) null), new VectorSpaceRoutingProperties(), new RankBasedMerger(),
            new RelationshipQueryPostActionGenerationProperties(), new PostActionGenerationProperties(),
            providerOf(new ObjectMapper()), new OrchestrationProperties(),
            providerOf((KnowledgeBaseOverviewService) null), null, mock(PendingActionStore.class),
            mock(ActionDraftStore.class), new PromptTemplateResolver(
                new ClasspathPromptTemplateStore(new DefaultResourceLoader()), new PromptBundleProperties()
            ), new PromptRenderer()
        );
    }

    private <T> ObjectProvider<T> providerOf(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
