package com.ai.fabric.realapps.agenticresolver.agentic.plan;

import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolutionResult;
import com.ai.fabric.realapps.agenticresolver.agentic.BillingResolutionAssessmentResult;
import java.math.BigDecimal;
import java.util.List;

public record AccountBillingResolutionPlanResult(
    AccountResolutionResult.Assessment accountAssessment,
    String accountSummary,
    List<AccountResolutionResult.Blocker> accountBlockers,
    BillingResolutionAssessmentResult.Decision billingDecision,
    BillingResolutionAssessmentResult.ExpectedStatus expectedBillingStatus,
    BigDecimal automaticLimit,
    String explanation
) {
    public AccountBillingResolutionPlanResult {
        accountBlockers = accountBlockers == null
            ? List.of()
            : List.copyOf(accountBlockers);
    }
}
