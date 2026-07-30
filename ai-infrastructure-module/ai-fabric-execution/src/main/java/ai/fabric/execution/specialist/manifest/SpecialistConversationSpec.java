package ai.fabric.execution.specialist.manifest;

public record SpecialistConversationSpec(
    SpecialistConversationBinding binding,
    boolean recordValidatedTurns,
    SpecialistInteractionCapability interactionCapability
) {
    public SpecialistConversationSpec {
        interactionCapability = interactionCapability == null
            ? SpecialistInteractionCapability.NON_INTERACTIVE
            : interactionCapability;
    }

    public SpecialistConversationSpec(
        SpecialistConversationBinding binding,
        boolean recordValidatedTurns
    ) {
        this(
            binding,
            recordValidatedTurns,
            SpecialistInteractionCapability.NON_INTERACTIVE
        );
    }
}
