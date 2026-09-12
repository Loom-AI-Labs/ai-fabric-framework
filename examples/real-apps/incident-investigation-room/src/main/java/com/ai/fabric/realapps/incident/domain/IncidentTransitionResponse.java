package com.ai.fabric.realapps.incident.domain;

public record IncidentTransitionResponse(
    IncidentSpecialistExecutionView intake,
    IncidentTransitionView transition,
    IncidentTransitionView secondTransitionCanary
) {}
