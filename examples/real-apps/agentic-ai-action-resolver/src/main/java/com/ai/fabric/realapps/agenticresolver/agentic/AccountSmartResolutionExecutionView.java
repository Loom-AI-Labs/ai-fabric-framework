package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.chain.SpecialistChainExecutionHandle;
import ai.fabric.execution.chain.SpecialistChainExecutionResult;
import ai.fabric.execution.chain.SpecialistChainExecutionSnapshot;
import ai.fabric.execution.chain.SpecialistChainExecutionStatus;
import ai.fabric.execution.chain.SpecialistChainFailure;
import ai.fabric.execution.chain.SpecialistChainStepTrace;
import java.time.Instant;
import java.util.List;

public record AccountSmartResolutionExecutionView(
    String executionId,
    String chain,
    SpecialistChainExecutionStatus status,
    int nextDecisionIndex,
    List<SpecialistChainStepTrace> timeline,
    SpecialistChainFailure failure,
    AccountSmartResolutionView result,
    boolean replayed,
    boolean durable,
    Instant submittedAt,
    Instant updatedAt,
    Instant deadline
) {
    public static AccountSmartResolutionExecutionView from(
        SpecialistChainExecutionHandle handle
    ) {
        return new AccountSmartResolutionExecutionView(
            handle.executionId(),
            handle.chainId().toString(),
            handle.status(),
            0,
            List.of(),
            handle.failureReason() == null
                ? null
                : new SpecialistChainFailure(
                    handle.failureReason(),
                    "The account specialist chain was not accepted.",
                    false
                ),
            null,
            handle.replayed(),
            handle.durable(),
            handle.submittedAt(),
            handle.submittedAt(),
            handle.deadline()
        );
    }

    public static AccountSmartResolutionExecutionView from(
        SpecialistChainExecutionSnapshot snapshot,
        SpecialistChainExecutionResult terminalResult
    ) {
        return new AccountSmartResolutionExecutionView(
            snapshot.executionId(),
            snapshot.chainId().toString(),
            snapshot.status(),
            snapshot.nextDecisionIndex(),
            snapshot.steps(),
            terminalResult == null
                ? snapshot.failure()
                : terminalResult.failure(),
            terminalResult == null
                ? null
                : AccountSmartResolutionView.from(terminalResult),
            terminalResult != null && terminalResult.replayed(),
            snapshot.durable(),
            snapshot.startedAt(),
            snapshot.updatedAt(),
            snapshot.deadline()
        );
    }
}
