package com.ai.fabric.realapps.incident.domain;

import java.time.Instant;

public record IncidentTransitionView(
    String delegationId,
    String handoffId,
    String parentInvocationId,
    String predecessorInvocationId,
    String sourceSpecialistId,
    String predecessorSpecialistId,
    String targetSpecialistId,
    String successorSpecialistId,
    int depth,
    String status,
    IncidentSpecialistExecutionView targetExecution,
    IncidentSpecialistExecutionView successorExecution,
    IncidentFailureView failure,
    boolean replayed,
    Instant startedAt,
    Instant completedAt
) {}
