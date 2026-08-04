package com.ai.fabric.realapps.mcpops.service;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.connector.McpActionExecutor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LocalOperationsMcpActionExecutor implements McpActionExecutor {

    private static final String SERVER_REF = "ai-fabric-operations-reference";
    private final Map<String, LocalState> states = new ConcurrentHashMap<>();

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
        String serverRef = String.valueOf(mcp.getOrDefault("serverRef", ""));
        String toolName = String.valueOf(mcp.getOrDefault("toolName", ""));
        if (!SERVER_REF.equals(serverRef)) {
            return ActionResult.builder()
                .success(false)
                .errorCode(ERROR_MCP_TOOL_NOT_AVAILABLE)
                .message("Configured MCP server is unavailable.")
                .build();
        }
        String sandboxId = value(params, "sandboxId", "mcp-demo-smoke");
        String serviceName = value(params, "serviceName", "checkout");
        String key = sandboxId + ":" + serviceName;
        LocalState state = states.computeIfAbsent(
            key,
            ignored -> new LocalState(
                "checkout".equals(serviceName) ? "DEGRADED" : "HEALTHY",
                1,
                0
            )
        );
        Map<String, Object> output = new LinkedHashMap<>();
        if ("get_sandbox_service_status".equals(toolName)) {
            output.putAll(status(serviceName, state));
        } else if ("list_recent_sandbox_incidents".equals(toolName)) {
            output.put("serviceName", serviceName);
            output.put("incidents", "DEGRADED".equals(state.status)
                ? java.util.List.of(Map.of(
                    "incidentId", "sandbox-checkout-latency",
                    "severity", "MEDIUM",
                    "summary", "Checkout latency rose after a sandbox configuration change."
                ))
                : java.util.List.of());
            output.put("revision", state.revision);
        } else if ("restart_sandbox_service".equals(toolName)) {
            int expectedRevision = intValue(params.get("expectedRevision"));
            if (expectedRevision != state.revision) {
                return ActionResult.builder()
                    .success(false)
                    .errorCode("REVISION_CONFLICT")
                    .message("Sandbox state changed before restart confirmation.")
                    .build();
            }
            state.status = "HEALTHY";
            state.revision++;
            state.restartCount++;
            output.putAll(status(serviceName, state));
            output.put("restarted", true);
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

    private Map<String, Object> status(String serviceName, LocalState state) {
        Map<String, Object> status = new HashMap<>();
        status.put("serviceName", serviceName);
        status.put("status", state.status);
        status.put("currentVersion", "2026.08-demo");
        status.put("openIncidents", "DEGRADED".equals(state.status) ? 1 : 0);
        status.put("revision", state.revision);
        status.put("restartCount", state.restartCount);
        return status;
    }

    private String value(Map<String, Object> params, String key, String fallback) {
        Object value = params != null ? params.get(key) : null;
        return value != null && !value.toString().isBlank()
            ? value.toString()
            : fallback;
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    private static final class LocalState {
        private String status;
        private int revision;
        private int restartCount;

        private LocalState(String status, int revision, int restartCount) {
            this.status = status;
            this.revision = revision;
            this.restartCount = restartCount;
        }
    }
}
