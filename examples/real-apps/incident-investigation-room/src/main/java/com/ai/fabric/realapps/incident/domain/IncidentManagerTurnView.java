package com.ai.fabric.realapps.incident.domain;

import ai.fabric.execution.manager.ConversationManagerFailure;
import ai.fabric.execution.manager.ConversationManagerId;
import ai.fabric.execution.manager.ConversationManagerTurnResult;
import ai.fabric.execution.manager.ConversationManagerTurnStatus;
import ai.fabric.execution.specialist.SpecialistId;
import java.time.Instant;

public record IncidentManagerTurnView(
    String turnId,
    ConversationManagerId managerId,
    ConversationManagerTurnStatus status,
    String message,
    SpecialistId selectedTarget,
    String managerInvocationId,
    String workerInvocationId,
    String snapshotRevision,
    long snapshotSourceTurnCount,
    ConversationManagerFailure failure,
    boolean replayed,
    Instant startedAt,
    Instant completedAt,
    IncidentSpecialistTrace decisionTrace
) {
    public static IncidentManagerTurnView from(
        ConversationManagerTurnResult result,
        IncidentSpecialistTrace trace
    ) {
        return new IncidentManagerTurnView(
            result.turnId(),
            result.managerId(),
            result.status(),
            result.message(),
            result.selectedTarget(),
            result.managerInvocationId(),
            result.workerInvocationId(),
            result.snapshotRevision(),
            result.snapshotSourceTurnCount(),
            result.failure(),
            result.replayed(),
            result.startedAt(),
            result.completedAt(),
            trace
        );
    }
}
