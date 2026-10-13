package ai.fabric.chat.control;

/** Typed admission result; intake-required never implies a state transition. */
public record HandoverAdmissionResult(
    HandoverAdmissionOutcome outcome,
    HandoverIntakeSchema intakeSchema,
    String code,
    String message
) {
    public HandoverAdmissionResult {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome is required");
        }
        if (outcome == HandoverAdmissionOutcome.HANDOVER_INTAKE_REQUIRED && intakeSchema == null) {
            throw new IllegalArgumentException("intakeSchema is required for HANDOVER_INTAKE_REQUIRED");
        }
    }
}
