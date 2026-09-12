package com.ai.fabric.realapps.incident.domain;

import java.time.Instant;

public record IncidentPlanStepView(
    String stepId,
    String parallelGroupId,
    String sourceRevision,
    String specialistId,
    String invocationId,
    String status,
    Instant startedAt,
    Instant completedAt,
    IncidentSpecialistTrace decisionTrace
) {}
