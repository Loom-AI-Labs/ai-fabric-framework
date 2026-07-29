package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.handoff.SpecialistHandoffResult;

public record AccountHandoffResponse(
    AIExecutionResult<AccountHandoffDecision> predecessor,
    SpecialistHandoffResult<AccountHandoffDecision, ?> handoff
) {}
