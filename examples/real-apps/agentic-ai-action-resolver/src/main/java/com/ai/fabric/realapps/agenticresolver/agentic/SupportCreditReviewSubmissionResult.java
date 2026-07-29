package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.gateway.AIExecutionFailure;
import ai.fabric.execution.gateway.AIExecutionStatus;
import ai.fabric.execution.review.ReviewTaskView;
import ai.fabric.execution.review.decision.ReviewDecisionFailure;
import java.util.List;

public record SupportCreditReviewSubmissionResult(
    String invocationId,
    AIExecutionStatus proposalStatus,
    ReviewTaskView reviewTask,
    boolean dispatchAccepted,
    List<AIEvidenceReference> evidence,
    AIExecutionFailure proposalFailure,
    ReviewDecisionFailure reviewFailure
) {

    public SupportCreditReviewSubmissionResult {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public boolean reviewCreated() {
        return reviewTask != null;
    }
}
