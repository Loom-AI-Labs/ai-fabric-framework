package com.ai.fabric.realapps.incident.domain;

import java.util.List;

public record ChangeRiskFinding(
    String riskLevel,
    String suspectedChange,
    String summary,
    List<String> evidenceIds
) {}
