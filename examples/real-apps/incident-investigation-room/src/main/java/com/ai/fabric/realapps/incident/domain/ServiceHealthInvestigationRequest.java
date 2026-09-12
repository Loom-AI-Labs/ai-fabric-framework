package com.ai.fabric.realapps.incident.domain;

public record ServiceHealthInvestigationRequest(
    String question,
    String incidentId,
    String deploymentId,
    String sourceRevision
) {}
