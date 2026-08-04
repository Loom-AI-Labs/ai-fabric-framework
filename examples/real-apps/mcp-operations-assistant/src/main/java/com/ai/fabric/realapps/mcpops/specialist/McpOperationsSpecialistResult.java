package com.ai.fabric.realapps.mcpops.specialist;

import java.util.List;

public record McpOperationsSpecialistResult(
    String operation,
    String serviceName,
    String healthStatus,
    String summary,
    List<String> facts
) {
}
