package ai.fabric.execution.review.policy;

/**
 * Cross-request human-control purposes. Immediate user confirmation is not a
 * review type.
 */
public enum ReviewType {
    OPERATIONAL_REVIEW,
    CORRECTION,
    ESCALATION,
    OUTCOME_REVIEW,
    QUALITY_SAMPLE
}
