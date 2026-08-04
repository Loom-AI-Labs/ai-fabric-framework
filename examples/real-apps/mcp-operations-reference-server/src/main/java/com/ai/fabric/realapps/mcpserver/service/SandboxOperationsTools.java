package com.ai.fabric.realapps.mcpserver.service;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class SandboxOperationsTools {

    private final SandboxOperationsService operations;

    public SandboxOperationsTools(SandboxOperationsService operations) {
        this.operations = operations;
    }

    @McpTool(
        name = "get_sandbox_service_status",
        description = "Read the current status of one isolated demo service"
    )
    public McpSchema.CallToolResult status(
        @McpToolParam(description = "Server-owned sandbox identifier", required = true)
        String sandboxId,
        @McpToolParam(description = "Allowlisted service name", required = true)
        String serviceName
    ) {
        var status = operations.status(sandboxId, serviceName);
        return success(
            "Sandbox service status retrieved",
            Map.of(
                "serviceName", status.service(),
                "status", status.status(),
                "currentVersion", status.version(),
                "revision", status.revision(),
                "openIncidents", status.openIncidents(),
                "restartCount", status.restartCount(),
                "lastRestartAt", status.lastRestartAt() == null
                    ? "never"
                    : status.lastRestartAt().toString()
            )
        );
    }

    @McpTool(
        name = "list_recent_sandbox_incidents",
        description = "List bounded safe incident summaries for one isolated demo service"
    )
    public McpSchema.CallToolResult incidents(
        @McpToolParam(description = "Server-owned sandbox identifier", required = true)
        String sandboxId,
        @McpToolParam(description = "Allowlisted service name", required = true)
        String serviceName
    ) {
        var incidents = operations.incidents(sandboxId, serviceName);
        return success(
            "Recent sandbox incidents retrieved",
            Map.of(
                "serviceName", incidents.service(),
                "revision", incidents.serviceRevision(),
                "incidents", incidents.incidents()
            )
        );
    }

    @McpTool(
        name = "restart_sandbox_service",
        description = "Restart one isolated demo service after application confirmation"
    )
    public McpSchema.CallToolResult restart(
        @McpToolParam(description = "Server-owned sandbox identifier", required = true)
        String sandboxId,
        @McpToolParam(description = "Allowlisted service name", required = true)
        String serviceName,
        @McpToolParam(description = "Optional optimistic source revision", required = false)
        Integer expectedRevision
    ) {
        var outcome = operations.restart(
            sandboxId,
            serviceName,
            expectedRevision
        );
        return success(
            outcome.message(),
            Map.of(
                "serviceName", outcome.serviceName(),
                "status", outcome.status(),
                "currentVersion", outcome.currentVersion(),
                "revision", outcome.revision(),
                "restartCount", outcome.restartCount(),
                "lastRestartAt", outcome.lastRestartAt().toString(),
                "restarted", true,
                "message", outcome.message()
            )
        );
    }

    private McpSchema.CallToolResult success(
        String message,
        Map<String, Object> structured
    ) {
        return McpSchema.CallToolResult.builder()
            .addTextContent(message)
            .structuredContent(structured)
            .isError(false)
            .build();
    }
}
