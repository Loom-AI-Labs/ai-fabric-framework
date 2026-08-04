package com.ai.fabric.realapps.incident.domain;

import ai.fabric.execution.plan.PlanExecutionResult;

public record IncidentPlanComparison(
    PlanExecutionResult<IncidentAssessment> sequential,
    PlanExecutionResult<IncidentAssessment> parallel,
    boolean semanticallyEquivalent,
    String comparisonReason
) {}
