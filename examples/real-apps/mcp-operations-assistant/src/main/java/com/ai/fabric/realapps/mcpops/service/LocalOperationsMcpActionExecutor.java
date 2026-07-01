package com.ai.fabric.realapps.mcpops.service;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.connector.McpActionExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class LocalOperationsMcpActionExecutor implements McpActionExecutor {

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ActionResult execute(String actionId,
                                ActionAccessMode accessMode,
                                Map<String, Object> params,
                                ActionContext context,
                                Map<String, Object> actionConfig) {
        Map<String, Object> execution = actionConfig != null && actionConfig.get("execution") instanceof Map<?, ?> rawExecution
            ? (Map<String, Object>) rawExecution
            : Map.of();
        Map<String, Object> mcp = execution.get("mcp") instanceof Map<?, ?> rawMcp
            ? (Map<String, Object>) rawMcp
            : Map.of();
        String toolName = String.valueOf(mcp.getOrDefault("toolName", ""));
        Map<String, Object> output = new LinkedHashMap<>();
        if ("service_health".equals(toolName)) {
            output.put("service", String.valueOf(params.getOrDefault("service", "checkout")));
            output.put("status", "HEALTHY");
            output.put("openIncidents", 0);
        } else if ("deployment_rollback".equals(toolName)) {
            output.put("service", String.valueOf(params.getOrDefault("service", "checkout")));
            output.put("targetVersion", String.valueOf(params.getOrDefault("targetVersion", "previous")));
            output.put("rollbackRequested", true);
        } else {
            return ActionResult.builder()
                .success(false)
                .errorCode(ERROR_MCP_TOOL_NOT_AVAILABLE)
                .message("Unknown local MCP tool: " + toolName)
                .build();
        }
        output.put("_hiddenConnectorTrace", "should-not-leak");
        return ActionResult.builder()
            .success(true)
            .message("MCP tool executed")
            .data(ActionResultContracts.object(output))
            .build();
    }
}
