package com.ai.fabric.realapps.deploymentguard.domain;

public record DeploymentContext(
    String id,
    String tenantId,
    String deploymentId,
    String label,
    String environment
) {}
