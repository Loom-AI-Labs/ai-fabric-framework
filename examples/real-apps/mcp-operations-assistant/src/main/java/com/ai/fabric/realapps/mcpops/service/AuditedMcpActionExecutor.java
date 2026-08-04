package com.ai.fabric.realapps.mcpops.service;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.connector.McpActionExecutor;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

public final class AuditedMcpActionExecutor implements McpActionExecutor {

    private final McpActionExecutor delegate;
    private final McpInvocationAuditService audit;
    private final Clock clock;

    public AuditedMcpActionExecutor(
        McpActionExecutor delegate,
        McpInvocationAuditService audit,
        Clock clock
    ) {
        this.delegate = delegate;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public boolean isAvailable() {
        return delegate.isAvailable();
    }

    @Override
    public ActionResult execute(
        String actionId,
        ActionAccessMode accessMode,
        Map<String, Object> params,
        ActionContext context,
        Map<String, Object> actionConfig
    ) {
        Instant startedAt = clock.instant();
        long startedNanos = System.nanoTime();
        ActionResult result = null;
        RuntimeException thrown = null;
        try {
            result = delegate.execute(
                actionId,
                accessMode,
                params,
                context,
                actionConfig
            );
            return result;
        } catch (RuntimeException ex) {
            thrown = ex;
            throw ex;
        } finally {
            long durationMs = Math.max(
                0,
                (System.nanoTime() - startedNanos) / 1_000_000
            );
            audit.record(
                sessionId(context, params),
                actionId,
                nestedText(actionConfig, "execution", "mcp", "serverRef"),
                nestedText(actionConfig, "execution", "mcp", "toolName"),
                accessMode != null ? accessMode.name() : "UNKNOWN",
                text(params, "serviceName"),
                result != null && result.isSuccess(),
                result != null ? result.getErrorCode()
                    : thrown != null ? thrown.getClass().getSimpleName() : null,
                durationMs,
                startedAt
            );
        }
    }

    private String sessionId(ActionContext context, Map<String, Object> params) {
        if (context != null && context.userId() != null) {
            return context.userId();
        }
        return text(params, "sandboxId");
    }

    private String text(Map<String, Object> source, String key) {
        Object value = source != null ? source.get(key) : null;
        return value != null ? value.toString() : null;
    }

    private String nestedText(Map<String, Object> source, String... path) {
        Object current = source;
        for (String segment : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current != null ? current.toString() : null;
    }
}
