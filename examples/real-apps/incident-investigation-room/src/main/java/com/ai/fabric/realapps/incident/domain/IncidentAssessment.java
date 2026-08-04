package com.ai.fabric.realapps.incident.domain;

import java.util.List;

public record IncidentAssessment(
    String incidentId,
    String deploymentId,
    String sourceRevision,
    String severity,
    String healthStatus,
    String changeRisk,
    String likelyCause,
    String recommendation,
    List<String> evidenceIds,
    ServiceHealthFinding serviceHealth,
    ChangeRiskFinding changeRiskFinding
) {}
