package com.ai.fabric.realapps.incident.domain;

import java.time.Instant;

public record IncidentSpecialistExecutionView(
    String invocationId,
    String specialistId,
    String status,
    Object output,
    IncidentFailureView failure,
    Instant startedAt,
    Instant completedAt,
    IncidentSpecialistTrace decisionTrace
) {}
