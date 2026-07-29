package ai.fabric.execution.specialist.manifest;

public record SpecialistCapabilitySpec(
    SpecialistRetrievalSpec retrieval,
    SpecialistActionSpec actions
) {}
