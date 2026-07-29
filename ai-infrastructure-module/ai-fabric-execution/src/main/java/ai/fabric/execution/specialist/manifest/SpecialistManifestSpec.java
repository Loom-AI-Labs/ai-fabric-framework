package ai.fabric.execution.specialist.manifest;

public record SpecialistManifestSpec(
    String mode,
    SpecialistInstructionSpec instructions,
    SpecialistExecutionSpec execution,
    SpecialistCapabilitySpec capabilities,
    SpecialistInputSpec input,
    SpecialistGroundingSpec grounding,
    SpecialistOutputSpec output,
    SpecialistConversationSpec conversation,
    SpecialistLimitSpec limits
) {
    public SpecialistExtensionRefs extensionRefs() {
        return new SpecialistExtensionRefs(
            grounding != null ? grounding.validatorRefs() : null,
            output != null ? output.finalValidatorRefs() : null,
            output != null ? output.directProjectorRef() : null,
            output != null ? output.normalizerRef() : null
        );
    }
}
