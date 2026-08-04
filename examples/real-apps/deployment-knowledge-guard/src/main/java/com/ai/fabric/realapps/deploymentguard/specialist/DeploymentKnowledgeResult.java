package com.ai.fabric.realapps.deploymentguard.specialist;

import java.util.List;

public record DeploymentKnowledgeResult(
    String summary,
    String healthStatus,
    String release,
    String indexingStatus,
    String incidentRisk,
    String recommendedRunbook,
    List<String> evidenceIds
) {
    public DeploymentKnowledgeResult {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
