package com.ai.fabric.realapps.agenticresolver.agentic;

import com.ai.fabric.realapps.agenticresolver.entity.RefundRequest;
import java.math.BigDecimal;

public record BillingResolutionAssessmentResult(
    RefundRequest.ResolutionType resolutionType,
    BigDecimal amount,
    Decision decision,
    ExpectedStatus expectedStatus,
    BigDecimal automaticLimit,
    String explanation
) {
    public enum Decision {
        AUTO_APPROVED,
        REVIEW_REQUIRED
    }

    public enum ExpectedStatus {
        APPROVED,
        PENDING_REVIEW
    }
}
