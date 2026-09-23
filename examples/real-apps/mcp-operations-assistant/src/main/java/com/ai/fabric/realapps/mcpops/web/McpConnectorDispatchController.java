package com.ai.fabric.realapps.mcpops.web;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.connector.ActionConnectorProtocol;
import ai.fabric.intent.action.connector.McpActionExecutor;
import ai.fabric.intent.orchestration.OrchestrationContext;
import com.ai.fabric.realapps.mcpops.service.McpOperationsService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
public class McpConnectorDispatchController {

    private static final String DISPATCH_MODE = "CONNECTOR";
    private static final String REQUIRED_ARGUMENT = "request.revision";
    private static final Set<String> SAFE_RESULT_FIELDS = Set.of(
        "serviceName",
        "status",
        "currentVersion",
        "openIncidents",
        "revision",
        "restartCount",
        "restarted",
        "lastRestartAt"
    );

    private final McpActionExecutor mcpExecutor;

    public McpConnectorDispatchController(McpActionExecutor mcpExecutor) {
        this.mcpExecutor = mcpExecutor;
    }

    @PostMapping("/internal/mcp-connector/actions/execute")
    public Map<String, Object> execute(
        @RequestBody Map<String, Object> request
    ) {
        try {
            String actionId = requiredText(
                request.get(ActionConnectorProtocol.KEY_ACTION_ID),
                "actionId"
            );
            if (!McpOperationsService.RESTART_ACTION.equals(actionId)) {
                return failure(
                    "ACTION_NOT_ALLOWED",
                    "The MCP operations connector accepts only the restart action."
                );
            }

            Map<String, Object> params = map(
                request.get(ActionConnectorProtocol.KEY_PARAMS)
            );
            Map<String, Object> trace = map(
                request.get(ActionConnectorProtocol.KEY_TRACE)
            );
            Map<String, Object> actionConfig = map(trace.get("actionConfig"));
            Map<String, Object> mcp = mcp(actionConfig);
            requireExactBinding(mcp);

            Map<String, Object> adapted = adaptedPayload(params);
            requireRenderedGate(mcp, adapted);

            ActionResult result = mcpExecutor.execute(
                actionId,
                ActionAccessMode.WRITE_ONLY,
                directParams(adapted),
                actionContext(trace),
                directActionConfig(mcp)
            );
            return response(result, adapted);
        } catch (IllegalArgumentException exception) {
            return failure("INVALID_CONFIGURATION", exception.getMessage());
        }
    }

    private Map<String, Object> adaptedPayload(Map<String, Object> params) {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("sandbox", requiredText(params.get("sandboxId"), "sandboxId"));
        nested.put("service", requiredText(params.get("serviceName"), "serviceName"));
        nested.put("revision", requiredInteger(
            params.get("expectedRevision"),
            "expectedRevision"
        ));
        return Map.of("request", Map.copyOf(nested));
    }

    private Map<String, Object> directParams(Map<String, Object> adapted) {
        Map<String, Object> request = map(adapted.get("request"));
        return Map.of(
            "sandboxId", request.get("sandbox"),
            "serviceName", request.get("service"),
            "expectedRevision", request.get("revision")
        );
    }

    private void requireExactBinding(Map<String, Object> mcp) {
        if (!DISPATCH_MODE.equals(requiredText(
            mcp.get("dispatchMode"),
            "execution.mcp.dispatchMode"
        ))) {
            throw new IllegalArgumentException(
                "Connector dispatch requires execution.mcp.dispatchMode=CONNECTOR"
            );
        }
        if (!McpOperationsService.SERVER_REF.equals(requiredText(
            mcp.get("serverRef"),
            "execution.mcp.serverRef"
        ))) {
            throw new IllegalArgumentException("Unapproved MCP server reference");
        }
        if (!McpOperationsService.RESTART_ACTION.equals(requiredText(
            mcp.get("toolName"),
            "execution.mcp.toolName"
        ))) {
            throw new IllegalArgumentException("Unapproved MCP tool name");
        }
    }

    private void requireRenderedGate(
        Map<String, Object> mcp,
        Map<String, Object> adapted
    ) {
        List<String> configured = list(mcp.get("requiredAnyArguments"));
        if (!configured.contains(REQUIRED_ARGUMENT)) {
            throw new IllegalArgumentException(
                "Connector requires the rendered request.revision gate"
            );
        }
        Object revision = map(adapted.get("request")).get("revision");
        if (!(revision instanceof Number number) || number.intValue() < 1) {
            throw new IllegalArgumentException(
                "Rendered MCP arguments require a meaningful request.revision"
            );
        }
    }

    private Map<String, Object> directActionConfig(Map<String, Object> source) {
        return Map.of(
            "adapterType", "mcp-tool",
            "execution", Map.of(
                "adapterType", "mcp-tool",
                "mcp", Map.of(
                    "dispatchMode", "DIRECT_GATEWAY",
                    "serverRef", source.get("serverRef"),
                    "toolName", source.get("toolName"),
                    "argumentTemplate", Map.of(
                        "sandboxId", "{{params.sandboxId}}",
                        "serviceName", "{{params.serviceName}}",
                        "expectedRevision", "{{params.expectedRevision}}"
                    ),
                    "responseMapping", Map.of(
                        "resultPath", "$.structuredContent",
                        "maxCharacters", 8192
                    )
                )
            )
        );
    }

    private ActionContext actionContext(Map<String, Object> trace) {
        return new ActionContext(
            OrchestrationContext.builder()
                .requestId(text(trace.get("requestId")))
                .conversationId(text(trace.get("conversationId")))
                .userId(text(trace.get("userId")))
                .mode("resolver")
                .position("operations")
                .build(),
            null
        );
    }

    private Map<String, Object> response(
        ActionResult result,
        Map<String, Object> adapted
    ) {
        if (result == null) {
            return failure("MCP_UNAVAILABLE", "The MCP tool returned no result.");
        }
        if (!result.isSuccess()) {
            return failure(
                StringUtils.hasText(result.getErrorCode())
                    ? result.getErrorCode()
                    : "MCP_UNAVAILABLE",
                StringUtils.hasText(result.getMessage())
                    ? result.getMessage()
                    : "The MCP tool call failed."
            );
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (result.getData() != null) {
            result.getData().toMap().forEach((key, value) -> {
                if (SAFE_RESULT_FIELDS.contains(key) && isScalar(value)) {
                    data.put(key, value);
                }
            });
        }
        data.put("dispatchMode", DISPATCH_MODE);
        data.put("logicalGate", "expectedRevision");
        data.put("renderedGate", REQUIRED_ARGUMENT);
        data.put("adaptation", "request.* -> exact MCP tool arguments");
        data.put("adaptedPayload", adapted);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put(ActionConnectorProtocol.KEY_SUCCESS, true);
        response.put(
            ActionConnectorProtocol.KEY_MESSAGE,
            StringUtils.hasText(result.getMessage())
                ? result.getMessage()
                : "Sandbox service restart completed"
        );
        response.put(ActionConnectorProtocol.KEY_DATA, Map.copyOf(data));
        return Map.copyOf(response);
    }

    private boolean isScalar(Object value) {
        return value instanceof String
            || value instanceof Number
            || value instanceof Boolean;
    }

    private Map<String, Object> failure(String code, String message) {
        return Map.of(
            ActionConnectorProtocol.KEY_SUCCESS, false,
            ActionConnectorProtocol.KEY_ERROR_CODE, code,
            ActionConnectorProtocol.KEY_MESSAGE, message
        );
    }

    private Map<String, Object> mcp(Map<String, Object> actionConfig) {
        return map(map(actionConfig.get("execution")).get("mcp"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null) {
                copy.put(key.toString(), item);
            }
        });
        return Map.copyOf(copy);
    }

    private List<String> list(Object value) {
        if (!(value instanceof List<?> source)) {
            return List.of();
        }
        return source.stream()
            .filter(item -> item != null && StringUtils.hasText(item.toString()))
            .map(item -> item.toString().trim())
            .toList();
    }

    private String requiredText(Object value, String field) {
        String text = text(value);
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return text;
    }

    private int requiredInteger(Object value, String field) {
        try {
            int parsed = value instanceof Number number
                ? number.intValue()
                : Integer.parseInt(requiredText(value, field));
            if (parsed < 1) {
                throw new NumberFormatException("must be positive");
            }
            return parsed;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(field + " must be a positive integer");
        }
    }

    private String text(Object value) {
        return value == null ? null : value.toString().trim();
    }
}
