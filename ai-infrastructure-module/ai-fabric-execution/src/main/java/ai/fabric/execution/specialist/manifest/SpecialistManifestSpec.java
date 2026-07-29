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
    SpecialistLimitSpec limits,
    SpecialistDelegationSpec delegation
) {
    public SpecialistManifestSpec {
        delegation = delegation == null
            ? SpecialistDelegationSpec.disabled()
            : delegation;
    }

    public SpecialistManifestSpec(
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
        this(
            mode,
            instructions,
            execution,
            capabilities,
            input,
            grounding,
            output,
            conversation,
            limits,
            SpecialistDelegationSpec.disabled()
        );
    }

    public SpecialistExtensionRefs extensionRefs() {
        return new SpecialistExtensionRefs(
            grounding != null ? grounding.validatorRefs() : null,
            output != null ? output.finalValidatorRefs() : null,
            output != null ? output.directProjectorRef() : null,
            output != null ? output.normalizerRef() : null
        );
    }
}
