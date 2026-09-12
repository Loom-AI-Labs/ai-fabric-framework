package com.ai.fabric.realapps.incident.domain;

public record ChangeRiskInvestigationRequest(
    String question,
    String incidentId,
    String deploymentId,
    String sourceRevision
) {}
