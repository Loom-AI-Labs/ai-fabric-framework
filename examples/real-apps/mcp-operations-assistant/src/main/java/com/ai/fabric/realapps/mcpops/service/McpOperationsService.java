package com.ai.fabric.realapps.mcpops.service;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.connector.McpActionExecutor;
import ai.fabric.intent.orchestration.OrchestrationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class McpOperationsService {

    public static final String SERVER_REF = "ai-fabric-operations-reference";
    private static final String MISSING_SERVER_REF = "missing-approved-server";
    public static final String STATUS_ACTION = "get_sandbox_service_status";
    public static final String INCIDENTS_ACTION =
        "list_recent_sandbox_incidents";
    public static final String RESTART_ACTION = "restart_sandbox_service";
    public static final Set<String> REQUIRED_TOOLS = Set.of(
        STATUS_ACTION,
        INCIDENTS_ACTION,
        RESTART_ACTION
    );

    private final McpActionExecutor executor;
    private final McpDemoSessionService sessions;
    private final McpInvocationAuditService audits;
    private final McpConnectionStatusService connectionStatus;

    public McpOperationsService(
        McpActionExecutor executor,
        McpDemoSessionService sessions,
        McpInvocationAuditService audits,
        McpConnectionStatusService connectionStatus
    ) {
        this.executor = executor;
        this.sessions = sessions;
        this.audits = audits;
        this.connectionStatus = connectionStatus;
    }

    public List<ToolPolicy> catalog() {
        return List.of(
            new ToolPolicy(
                STATUS_ACTION,
                STATUS_ACTION,
                ActionAccessMode.READ,
                false,
                "Read current status from the isolated sandbox."
            ),
            new ToolPolicy(
                INCIDENTS_ACTION,
                INCIDENTS_ACTION,
                ActionAccessMode.READ,
                false,
                "Read recent incidents from the isolated sandbox."
            ),
            new ToolPolicy(
                RESTART_ACTION,
                RESTART_ACTION,
                ActionAccessMode.WRITE_ONLY,
                true,
                "Restart only the selected isolated sandbox service."
            )
        );
    }

    public SandboxState state(String sessionId) {
        McpDemoSessionService.ActiveSession session = sessions.active(sessionId);
        Map<String, Object> status = status(session);
        Map<String, Object> incidents = executeRead(
            session,
            INCIDENTS_ACTION,
            INCIDENTS_ACTION
        );
        return new SandboxState(
            session.serviceName(),
            scalarMap(status),
            recordList(incidents.get("incidents")),
            audits.timeline(session.sessionId()),
            connectionStatus.status()
        );
    }

    public Map<String, Object> currentStatus(String sessionId) {
        return scalarMap(status(sessions.active(sessionId)));
    }

    public BindingCanary bindingCanary(String sessionId) {
        McpDemoSessionService.ActiveSession session = sessions.active(sessionId);
        long writesBefore = audits.successfulWrites(session.sessionId());
        ActionResult result = executor.execute(
            STATUS_ACTION,
            ActionAccessMode.READ,
            trustedParams(session, null),
            actionContext(session),
            actionConfig(
                MISSING_SERVER_REF,
                STATUS_ACTION
            )
        );
        long writesAfter = audits.successfulWrites(session.sessionId());
        boolean passed = result != null
            && !result.isSuccess()
            && "MCP_TOOL_NOT_AVAILABLE".equals(result.getErrorCode())
            && writesBefore == writesAfter;
        return new BindingCanary(
            passed,
            MISSING_SERVER_REF,
            SERVER_REF,
            STATUS_ACTION,
            result != null ? result.getErrorCode() : "NO_RESULT",
            writesAfter - writesBefore
        );
    }

    public McpConnectionStatusService.ConnectionStatus connection() {
        return connectionStatus.status();
    }

    private Map<String, Object> executeRead(
        McpDemoSessionService.ActiveSession session,
        String actionId,
        String toolName
    ) {
        ActionResult result = executor.execute(
            actionId,
            ActionAccessMode.READ,
            trustedParams(session, null),
            actionContext(session),
            actionConfig(SERVER_REF, toolName)
        );
        if (result == null || !result.isSuccess()) {
            String code = result != null && result.getErrorCode() != null
                ? result.getErrorCode()
                : "MCP_UNAVAILABLE";
            throw new McpOperationsUnavailableException(
                code,
                result != null ? result.getMessage()
                    : "The MCP server returned no result."
            );
        }
        return result.getData() != null
            ? result.getData().toMap()
            : Map.of();
    }

    private Map<String, Object> status(
        McpDemoSessionService.ActiveSession session
    ) {
        return executeRead(session, STATUS_ACTION, STATUS_ACTION);
    }

    private Map<String, Object> trustedParams(
        McpDemoSessionService.ActiveSession session,
        Integer expectedRevision
    ) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sandboxId", session.sessionId());
        params.put("serviceName", session.serviceName());
        if (expectedRevision != null) {
            params.put("expectedRevision", expectedRevision);
        }
        return Map.copyOf(params);
    }

    private ActionContext actionContext(
        McpDemoSessionService.ActiveSession session
    ) {
        return new ActionContext(
            OrchestrationContext.builder()
                .requestId("mcp-state-" + UUID.randomUUID())
                .conversationId(session.conversationId())
                .userId(session.sessionId())
                .mode("resolver")
                .position("operations")
                .build(),
            null
        );
    }

    private Map<String, Object> actionConfig(
        String serverRef,
        String toolName
    ) {
        return Map.of(
            "adapterType", "mcp-tool",
            "execution", Map.of(
                "adapterType", "mcp-tool",
                "mcp", Map.of(
                    "serverRef", serverRef,
                    "toolName", toolName,
                    "argumentTemplate", Map.of(
                        "sandboxId", "{{params.sandboxId}}",
                        "serviceName", "{{params.serviceName}}"
                    ),
                    "responseMapping", Map.of(
                        "resultPath", "$.structuredContent",
                        "maxCharacters", 8_192
                    )
                )
            )
        );
    }

    private Map<String, Object> scalarMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Set<String> allowed = Set.of(
            "serviceName",
            "status",
            "currentVersion",
            "openIncidents",
            "revision",
            "restartCount",
            "lastRestartAt"
        );
        Map<String, Object> safe = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (allowed.contains(key) && isScalar(value)) {
                safe.put(key, value);
            }
        });
        return Map.copyOf(safe);
    }

    private List<Map<String, Object>> recordList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> safe = new ArrayList<>();
        for (Object item : list) {
            if (safe.size() >= 10 || !(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> record = new LinkedHashMap<>();
            map.forEach((key, field) -> {
                if (key != null && isScalar(field) && record.size() < 8) {
                    record.put(key.toString(), field);
                }
            });
            safe.add(Map.copyOf(record));
        }
        return List.copyOf(safe);
    }

    private boolean isScalar(Object value) {
        return value instanceof String
            || value instanceof Number
            || value instanceof Boolean;
    }

    public record ToolPolicy(
        String actionId,
        String toolName,
        ActionAccessMode accessMode,
        boolean requiresConfirmation,
        String description
    ) {
    }

    public record SandboxState(
        String selectedService,
        Map<String, Object> status,
        List<Map<String, Object>> incidents,
        List<McpInvocationAuditService.AuditView> timeline,
        McpConnectionStatusService.ConnectionStatus connection
    ) {
    }

    public record BindingCanary(
        boolean passed,
        String rejectedServerRef,
        String requiredServerRef,
        String duplicateToolName,
        String errorCode,
        long writeDelta
    ) {
    }

    public static final class McpOperationsUnavailableException
        extends RuntimeException {

        private final String code;

        public McpOperationsUnavailableException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
