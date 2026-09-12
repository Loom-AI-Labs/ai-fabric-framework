package com.ai.fabric.realapps.incident.domain;

import java.time.Instant;
import java.util.List;

public record IncidentPlanRunView(
    String executionId,
    String planId,
    String planContentHash,
    String status,
    String activeStepId,
    IncidentInvestigationAssessment output,
    List<IncidentPlanStepView> steps,
    IncidentFailureView failure,
    Instant startedAt,
    Instant completedAt
) {}
