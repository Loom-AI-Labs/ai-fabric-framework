package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.action.ActionOutcomeProjector;
import ai.fabric.execution.action.ActionOutcomeView;
import ai.fabric.intent.action.ActionResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Keeps review and action APIs free of internal account and subscription IDs.
 */
@Component
public class SupportCreditOutcomeProjector
    implements ActionOutcomeProjector {

    @Override
    public String actionName() {
        return AccountResolverSpecialists.REQUEST_REFUND_ACTION;
    }

    @Override
    public ActionOutcomeView project(ActionResult result) {
        if (result == null || !result.isSuccess()) {
            return new ActionOutcomeView(
                actionName(),
                "The billing resolution could not be created.",
                Map.of("created", false)
            );
        }
        Map<String, Object> source = result.getData() == null
            ? Map.of()
            : result.getData().toMap();
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("created", true);
        copy(source, safe, "resolutionType");
        copy(source, safe, "status");
        copy(source, safe, "amount");
        copy(source, safe, "policyDecision");
        copy(source, safe, "policyExplanation");
        return new ActionOutcomeView(
            actionName(),
            "The reviewed billing resolution was created.",
            Map.copyOf(safe)
        );
    }

    private void copy(
        Map<String, Object> source,
        Map<String, Object> target,
        String key
    ) {
        Object value = source.get(key);
        if (value instanceof String
            || value instanceof Number
            || value instanceof Boolean) {
            target.put(key, value);
        }
    }
}
