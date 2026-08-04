package com.ai.fabric.realapps.incident.domain;

import java.util.List;

public record ServiceHealthRequest(
    String question,
    String incidentId,
    String deploymentId,
    String sourceRevision,
    List<IncidentEvidence> evidence
) {}
