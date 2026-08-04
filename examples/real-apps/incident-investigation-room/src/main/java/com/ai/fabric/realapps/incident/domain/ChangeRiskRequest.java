package com.ai.fabric.realapps.incident.domain;

import java.util.List;

public record ChangeRiskRequest(
    String question,
    String incidentId,
    String deploymentId,
    String sourceRevision,
    List<IncidentEvidence> evidence
) {}
