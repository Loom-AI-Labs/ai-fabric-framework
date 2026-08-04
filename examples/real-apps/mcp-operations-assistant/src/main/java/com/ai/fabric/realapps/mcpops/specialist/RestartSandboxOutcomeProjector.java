package com.ai.fabric.realapps.mcpops.specialist;

import ai.fabric.execution.action.ActionOutcomeProjector;
import ai.fabric.execution.action.ActionOutcomeView;
import ai.fabric.intent.action.ActionResult;
import com.ai.fabric.realapps.mcpops.service.McpOperationsService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RestartSandboxOutcomeProjector implements ActionOutcomeProjector {

    private static final Set<String> SAFE_FIELDS = Set.of(
        "serviceName",
        "status",
        "currentVersion",
        "openIncidents",
        "revision",
        "restartCount",
        "restarted",
        "lastRestartAt"
    );

    @Override
    public String actionName() {
        return McpOperationsService.RESTART_ACTION;
    }

    @Override
    public ActionOutcomeView project(ActionResult result) {
        if (result == null || !result.isSuccess()) {
            return new ActionOutcomeView(
                actionName(),
                "The isolated sandbox service was not restarted.",
                Map.of("restarted", false)
            );
        }
        Map<String, Object> source = result.getData() != null
            ? result.getData().toMap()
            : Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (SAFE_FIELDS.contains(key)
                && (value instanceof String
                    || value instanceof Number
                    || value instanceof Boolean)) {
                safe.put(key, value);
            }
        });
        safe.put("restarted", true);
        return new ActionOutcomeView(
            actionName(),
            "The selected isolated sandbox service restarted successfully.",
            Map.copyOf(safe)
        );
    }
}
