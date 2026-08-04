package com.ai.fabric.realapps.incident.domain;

public record IncidentManagerRequest(
    String question,
    IncidentPlanRequest incident
) {}
