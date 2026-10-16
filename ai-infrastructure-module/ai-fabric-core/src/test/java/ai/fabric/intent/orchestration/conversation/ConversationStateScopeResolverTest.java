package ai.fabric.intent.orchestration.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationIntentPolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import ai.fabric.intent.orchestration.request.OrchestrationRequestPurpose;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConversationStateScopeResolverTest {

    @Test
    void trustedSpecialistRequestDoesNotImplicitlyActivateCanonicalState() {
        PipelineContext context = PipelineContext.from(new OrchestrationRequest(
            "inspect",
            orchestration("conversation-1", "owner-1"),
            trusted("tenant-1", "deployment-1"),
            ConversationPersistencePolicy.READ_ONLY
        ));

        assertThat(ConversationStateScopeResolver.resolve(context)).isEmpty();
    }

    @Test
    void explicitBindingMustMatchTrustedExecutionAndApprovedSnapshot() {
        PipelineContext context = canonicalContext(new ConversationStateBinding(
            "tenant-1", "deployment-1", "conversation-2", "owner-1", 7L
        ));

        assertThatThrownBy(() -> ConversationStateScopeResolver.resolve(context))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not match");
    }

    @Test
    void explicitMatchingBindingCarriesTheExpectedControlEpoch() {
        PipelineContext context = canonicalContext(new ConversationStateBinding(
            "tenant-1", "deployment-1", "conversation-1", "owner-1", 7L
        ));

        assertThat(ConversationStateScopeResolver.resolve(context)).contains(
            new ConversationStateScope(
                "tenant-1", "deployment-1", "conversation-1", "owner-1", 7L
            )
        );
    }

    private PipelineContext canonicalContext(ConversationStateBinding binding) {
        return PipelineContext.from(new OrchestrationRequest(
            "inspect",
            orchestration("conversation-1", "owner-1"),
            trusted("tenant-1", "deployment-1"),
            ConversationPersistencePolicy.READ_ONLY,
            null,
            null,
            null,
            OrchestrationRequestPurpose.GENERAL,
            OrchestrationIntentPolicy.MODEL_DIRECTED,
            binding
        ));
    }

    private OrchestrationContext orchestration(String conversationId, String ownerId) {
        return OrchestrationContext.builder()
            .userId(ownerId)
            .conversationId(conversationId)
            .approvedConversationSnapshot(new ApprovedConversationSnapshot(
                "turn-1",
                ownerId,
                conversationId,
                "specialist-1",
                "a".repeat(64),
                0L,
                List.of(),
                Instant.parse("2026-10-16T01:00:00Z")
            ))
            .build();
    }

    private TrustedExecutionContext trusted(String tenantId, String deploymentId) {
        return new TrustedExecutionContext(
            new ExecutionPrincipal("service-1", ExecutionPrincipalType.SERVICE),
            null,
            ExecutionSource.APPLICATION,
            tenantId,
            deploymentId,
            Set.of(),
            "correlation-1",
            Instant.parse("2026-10-16T01:00:00Z")
        );
    }
}
