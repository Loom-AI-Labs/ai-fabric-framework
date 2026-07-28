package ai.fabric.intent.orchestration.request;

import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import java.util.Objects;

/**
 * Structured input envelope for interactive and trusted application orchestration.
 */
public record OrchestrationRequest(
    String modelInput,
    OrchestrationContext orchestrationContext,
    TrustedExecutionContext trustedExecutionContext,
    ConversationPersistencePolicy conversationPersistencePolicy,
    EffectiveCapabilityProfile effectiveCapabilityProfile,
    String conversationInput,
    String responseInstructions,
    OrchestrationRequestPurpose purpose
) {
    public OrchestrationRequest {
        modelInput = Objects.requireNonNull(modelInput, "modelInput must not be null").trim();
        if (modelInput.isEmpty()) {
            throw new IllegalArgumentException("modelInput must not be blank");
        }
        Objects.requireNonNull(orchestrationContext, "orchestrationContext must not be null");
        conversationPersistencePolicy = conversationPersistencePolicy == null
            ? ConversationPersistencePolicy.NEVER
            : conversationPersistencePolicy;
        conversationInput = normalizeOptional(conversationInput);
        responseInstructions = normalizeOptional(responseInstructions);
        purpose = purpose == null ? OrchestrationRequestPurpose.GENERAL : purpose;
    }

    public OrchestrationRequest(
        String modelInput,
        OrchestrationContext orchestrationContext,
        TrustedExecutionContext trustedExecutionContext,
        ConversationPersistencePolicy conversationPersistencePolicy,
        EffectiveCapabilityProfile effectiveCapabilityProfile,
        String conversationInput,
        String responseInstructions
    ) {
        this(
            modelInput,
            orchestrationContext,
            trustedExecutionContext,
            conversationPersistencePolicy,
            effectiveCapabilityProfile,
            conversationInput,
            responseInstructions,
            OrchestrationRequestPurpose.GENERAL
        );
    }

    public OrchestrationRequest(
        String modelInput,
        OrchestrationContext orchestrationContext,
        TrustedExecutionContext trustedExecutionContext,
        ConversationPersistencePolicy conversationPersistencePolicy,
        EffectiveCapabilityProfile effectiveCapabilityProfile,
        String conversationInput
    ) {
        this(
            modelInput,
            orchestrationContext,
            trustedExecutionContext,
            conversationPersistencePolicy,
            effectiveCapabilityProfile,
            conversationInput,
            null,
            OrchestrationRequestPurpose.GENERAL
        );
    }

    public OrchestrationRequest(
        String modelInput,
        OrchestrationContext orchestrationContext,
        TrustedExecutionContext trustedExecutionContext,
        ConversationPersistencePolicy conversationPersistencePolicy
    ) {
        this(
            modelInput,
            orchestrationContext,
            trustedExecutionContext,
            conversationPersistencePolicy,
            null,
            null,
            null,
            OrchestrationRequestPurpose.GENERAL
        );
    }

    public OrchestrationRequest(
        String modelInput,
        OrchestrationContext orchestrationContext,
        TrustedExecutionContext trustedExecutionContext,
        ConversationPersistencePolicy conversationPersistencePolicy,
        EffectiveCapabilityProfile effectiveCapabilityProfile
    ) {
        this(
            modelInput,
            orchestrationContext,
            trustedExecutionContext,
            conversationPersistencePolicy,
            effectiveCapabilityProfile,
            null,
            null,
            OrchestrationRequestPurpose.GENERAL
        );
    }

    /**
     * Adapts the original query/context API without changing its identity validation contract.
     */
    public static OrchestrationRequest interactive(
        String modelInput,
        OrchestrationContext orchestrationContext
    ) {
        return new OrchestrationRequest(
            modelInput,
            orchestrationContext,
            null,
            ConversationPersistencePolicy.CONVERSATION,
            null,
            modelInput,
            null,
            OrchestrationRequestPurpose.GENERAL
        );
    }

    /**
     * Validates source-specific trust before pipeline execution.
     */
    public void validateForExecution() {
        if (trustedExecutionContext == null) {
            orchestrationContext.validate();
            return;
        }
        if (trustedExecutionContext.source() == ExecutionSource.INTERACTIVE) {
            orchestrationContext.validate();
        }
    }

    public ExecutionSource executionSource() {
        return trustedExecutionContext == null
            ? ExecutionSource.INTERACTIVE
            : trustedExecutionContext.source();
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
