package com.ai.fabric.realapps.incident.domain;

import java.util.List;

public record ServiceHealthInvestigationFinding(
    String healthStatus,
    String severity,
    String summary,
    List<String> evidenceIds,
    List<IncidentDataSourceUsage> dataSources,
    int candidateEventCount,
    String selectionReason,
    String sourceRevision
) {}
