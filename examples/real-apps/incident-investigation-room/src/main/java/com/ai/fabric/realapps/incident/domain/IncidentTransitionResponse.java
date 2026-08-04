package com.ai.fabric.realapps.incident.domain;

import ai.fabric.execution.gateway.AIExecutionResult;

public record IncidentTransitionResponse(
    AIExecutionResult<IncidentRoutingDecision> intake,
    Object transition,
    Object secondTransitionCanary
) {}
