package com.ai.fabric.realapps.incident.domain;

import java.util.List;

public record IncidentInvestigationAssessment(
    String incidentId,
    String deploymentId,
    String sourceRevision,
    String severity,
    String healthStatus,
    String changeRisk,
    String likelyCause,
    String recommendation,
    List<String> evidenceIds,
    ServiceHealthInvestigationFinding serviceHealth,
    ChangeRiskInvestigationFinding changeRiskFinding,
    List<IncidentDataSourceUsage> dataSources,
    String validationStatus
) {}
