package com.ai.fabric.realapps.mcpops.service;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionObjectPayload;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.connector.McpActionExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class McpOperationsService {

    private final McpActionExecutor mcpActionExecutor;
    private final Map<String, ToolPolicy> catalog;

    public McpOperationsService(McpActionExecutor mcpActionExecutor) {
        this.mcpActionExecutor = mcpActionExecutor;
        this.catalog = Map.of(
            "service_health", new ToolPolicy("service_health", ActionAccessMode.READ, false, "service_health"),
            "deployment_rollback", new ToolPolicy("deployment_rollback", ActionAccessMode.READ_WRITE, true, "deployment_rollback")
        );
    }

    public List<ToolPolicy> catalog() {
        return catalog.values().stream().sorted((a, b) -> a.actionId().compareTo(b.actionId())).toList();
    }

    public ToolExecutionResult execute(ToolExecutionRequest request) {
        ToolExecutionRequest effective = request != null ? request : new ToolExecutionRequest(null, null, false);
        String actionId = requireText(effective.actionId(), "actionId");
        ToolPolicy policy = catalog.get(actionId);
        if (policy == null) {
            return ToolExecutionResult.failure(actionId, "UNKNOWN_TOOL", "Unknown MCP action.");
        }
        if (!mcpActionExecutor.isAvailable()) {
            return ToolExecutionResult.failure(actionId, "MCP_UNAVAILABLE", "MCP executor is unavailable.");
        }
        if (policy.requiresConfirmation() && !effective.confirmed()) {
            return new ToolExecutionResult(
                actionId,
                false,
                true,
                "Confirm " + actionId,
                null,
                Map.of("accessMode", policy.accessMode().name())
            );
        }

        ActionResult actionResult = mcpActionExecutor.execute(
            actionId,
            policy.accessMode(),
            effective.params() == null ? Map.of() : effective.params(),
            null,
            actionConfig(policy)
        );
        if (actionResult == null || !actionResult.isSuccess()) {
            return ToolExecutionResult.failure(
                actionId,
                actionResult != null ? actionResult.getErrorCode() : "MCP_FAILED",
                actionResult != null ? actionResult.getMessage() : "MCP action returned no result."
            );
        }
        return new ToolExecutionResult(
            actionId,
            true,
            false,
            actionResult.getMessage(),
            null,
            sanitizePayload(actionResult)
        );
    }

    private Map<String, Object> actionConfig(ToolPolicy policy) {
        return Map.of(
            "adapterType", "mcp-tool",
            "execution", Map.of(
                "mcp", Map.of(
                    "serverRef", "local-ops",
                    "toolName", policy.mcpToolName(),
                    "argumentTemplate", Map.of()
                )
            )
        );
    }

    private Map<String, Object> sanitizePayload(ActionResult actionResult) {
        if (!(actionResult.getData() instanceof ActionObjectPayload payload)) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        payload.toMap().forEach((key, value) -> {
            if (key != null && !key.startsWith("_")) {
                sanitized.put(key, value);
            }
        });
        return Map.copyOf(sanitized);
    }

    private String requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    public record ToolPolicy(
        String actionId,
        ActionAccessMode accessMode,
        boolean requiresConfirmation,
        String mcpToolName
    ) {}

    public record ToolExecutionRequest(
        String actionId,
        Map<String, Object> params,
        boolean confirmed
    ) {}

    public record ToolExecutionResult(
        String actionId,
        boolean success,
        boolean confirmationRequired,
        String message,
        String errorCode,
        Map<String, Object> data
    ) {
        static ToolExecutionResult failure(String actionId, String errorCode, String message) {
            return new ToolExecutionResult(actionId, false, false, message, errorCode, Map.of());
        }
    }
}
