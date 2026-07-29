package ai.fabric.execution.specialist.manifest;

public record SpecialistConversationSpec(
    SpecialistConversationBinding binding,
    boolean recordValidatedTurns
) {}
