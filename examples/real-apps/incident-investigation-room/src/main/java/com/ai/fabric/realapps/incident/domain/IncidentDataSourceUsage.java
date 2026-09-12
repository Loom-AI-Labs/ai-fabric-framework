package com.ai.fabric.realapps.incident.domain;

public record IncidentDataSourceUsage(
    String action,
    int candidateCount,
    boolean groundingUsable
) {}
