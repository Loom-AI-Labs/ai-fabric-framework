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
    SpecialistDelegationSpec delegation,
    SpecialistHandoffSpec handoff
) {
    public SpecialistManifestSpec {
        delegation = delegation == null
            ? SpecialistDelegationSpec.disabled()
            : delegation;
        handoff = handoff == null
            ? SpecialistHandoffSpec.disabled()
            : handoff;
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
        SpecialistLimitSpec limits,
        SpecialistDelegationSpec delegation
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
            delegation,
            SpecialistHandoffSpec.disabled()
        );
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
            SpecialistDelegationSpec.disabled(),
            SpecialistHandoffSpec.disabled()
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
