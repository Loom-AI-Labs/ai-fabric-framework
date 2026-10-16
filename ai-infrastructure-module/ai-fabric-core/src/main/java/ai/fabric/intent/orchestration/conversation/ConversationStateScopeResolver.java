package ai.fabric.intent.orchestration.conversation;

import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import java.util.Objects;
import java.util.Optional;

/** Resolves durable state scope only from server-owned execution context. */
public final class ConversationStateScopeResolver {

    private ConversationStateScopeResolver() {
    }

    public static Optional<ConversationStateScope> resolve(PipelineContext context) {
        if (context == null || context.getOrchestrationRequest() == null) {
            return Optional.empty();
        }
        ConversationStateBinding binding = context.getOrchestrationRequest().conversationStateBinding();
        if (binding == null) {
            return Optional.empty();
        }
        TrustedExecutionContext trusted = context.getOrchestrationRequest().trustedExecutionContext();
        OrchestrationContext orchestration = context.getOrchestrationContext();
        if (trusted == null
            || context.getOrchestrationRequest().conversationPersistencePolicy()
                != ConversationPersistencePolicy.READ_ONLY
            || orchestration == null
            || orchestration.getApprovedConversationSnapshot() == null) {
            throw invalidBinding();
        }
        var snapshot = orchestration.getApprovedConversationSnapshot();
        if (!Objects.equals(trusted.tenantId(), binding.tenantId())
            || !Objects.equals(trusted.deploymentId(), binding.deploymentId())
            || !Objects.equals(orchestration.getConversationId(), binding.conversationId())
            || !Objects.equals(context.getConversationOwnerIdentifier(), binding.ownerId())
            || !Objects.equals(snapshot.conversationId(), binding.conversationId())
            || !Objects.equals(snapshot.ownerId(), binding.ownerId())) {
            throw invalidBinding();
        }
        return Optional.of(new ConversationStateScope(
            binding.tenantId(),
            binding.deploymentId(),
            binding.conversationId(),
            binding.ownerId(),
            binding.expectedControlEpoch()
        ));
    }

    private static IllegalStateException invalidBinding() {
        return new IllegalStateException(
            "Canonical conversation state binding does not match the trusted execution context."
        );
    }
}
