package com.ai.fabric.realapps.incident.domain;

import java.util.List;

public record ChangeRiskInvestigationFinding(
    String riskLevel,
    String suspectedChange,
    String summary,
    List<String> evidenceIds,
    List<String> runbookEvidenceIds,
    List<IncidentDataSourceUsage> dataSources,
    int candidateEventCount,
    String selectionReason,
    String sourceRevision
) {}
