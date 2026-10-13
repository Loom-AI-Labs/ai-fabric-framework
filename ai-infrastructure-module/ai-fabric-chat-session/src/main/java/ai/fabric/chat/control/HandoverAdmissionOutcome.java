package ai.fabric.chat.control;

/** Outcome of host-owned admission before a control transition is attempted. */
public enum HandoverAdmissionOutcome {
    ACCEPTED,
    HANDOVER_INTAKE_REQUIRED,
    DENIED
}
