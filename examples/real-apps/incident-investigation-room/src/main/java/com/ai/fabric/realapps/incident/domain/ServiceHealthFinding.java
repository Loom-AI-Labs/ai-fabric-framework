package com.ai.fabric.realapps.incident.domain;

import java.util.List;

public record ServiceHealthFinding(
    String healthStatus,
    String severity,
    String summary,
    List<String> evidenceIds
) {}
