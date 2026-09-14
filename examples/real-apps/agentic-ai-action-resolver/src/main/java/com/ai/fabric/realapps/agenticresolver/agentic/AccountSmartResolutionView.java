package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.chain.SpecialistChainExecutionResult;
import ai.fabric.execution.chain.SpecialistChainExecutionStatus;
import ai.fabric.execution.chain.SpecialistChainFailure;
import ai.fabric.execution.chain.SpecialistChainResultView;
import ai.fabric.execution.chain.SpecialistChainStepTrace;
import java.time.Instant;
import java.util.List;

public record AccountSmartResolutionView(
    String executionId,
    String chain,
    String chainContentHash,
    SpecialistChainExecutionStatus status,
    String message,
    String handoffTarget,
    List<SpecialistChainResultView> results,
    List<SpecialistChainStepTrace> timeline,
    String conversationSnapshotRevision,
    long conversationSourceTurnCount,
    SpecialistChainFailure failure,
    boolean replayed,
    boolean durable,
    Instant startedAt,
    Instant completedAt
) {
    public static AccountSmartResolutionView from(
        SpecialistChainExecutionResult result
    ) {
        return new AccountSmartResolutionView(
            result.executionId(),
            result.chainId().toString(),
            result.chainContentHash(),
            result.status(),
            result.message(),
            result.handoffTarget() == null
                ? null
                : result.handoffTarget().toString(),
            result.projectedResults(),
            result.steps(),
            result.conversationSnapshotRevision(),
            result.conversationSourceTurnCount(),
            result.failure(),
            result.replayed(),
            result.durable(),
            result.startedAt(),
            result.completedAt()
        );
    }
}
