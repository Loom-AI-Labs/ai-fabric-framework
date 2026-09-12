package com.ai.fabric.realapps.incident.domain;

import java.util.List;

public record IncidentSpecialistTrace(
    String specialist,
    String status,
    List<IncidentDataSourceUsage> dataSources,
    List<String> selectedEvidenceIds,
    List<String> runbookEvidenceIds,
    String sourceRevision,
    String applicationValidation
) {}
