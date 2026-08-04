package com.ai.fabric.realapps.deploymentguard.domain;

import java.util.Map;

public record DeploymentKnowledgeDocument(
    String id,
    String tenantId,
    String deploymentId,
    String title,
    String sourceType,
    String content,
    int revision
) {
    public Map<String, Object> vectorMetadata() {
        return Map.of(
            "documentId", id,
            "tenantId", tenantId,
            "deploymentId", deploymentId,
            "title", title,
            "sourceType", sourceType,
            "visibility", "tenant",
            "revision", revision,
            "sourceUrl", "/api/deployment-guard/evidence/" + id
        );
    }
}
