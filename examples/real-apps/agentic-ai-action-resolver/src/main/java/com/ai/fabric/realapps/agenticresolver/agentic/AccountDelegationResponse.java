package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.delegation.SpecialistDelegationResult;
import ai.fabric.execution.gateway.AIExecutionResult;

public record AccountDelegationResponse(
    AIExecutionResult<AccountDelegationDecision> coordinator,
    SpecialistDelegationResult<AccountDelegationDecision, ?>
        delegatedExecution
) {}
