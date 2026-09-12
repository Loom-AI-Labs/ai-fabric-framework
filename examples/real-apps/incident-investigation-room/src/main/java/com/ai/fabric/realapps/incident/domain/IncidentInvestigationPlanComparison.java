package com.ai.fabric.realapps.incident.domain;

public record IncidentInvestigationPlanComparison(
    IncidentPlanRunView sequential,
    IncidentPlanRunView parallel,
    boolean semanticallyEquivalent,
    String comparisonReason
) {}
