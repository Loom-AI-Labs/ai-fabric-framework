package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.action.ActionOutcomeProjector;
import ai.fabric.execution.action.ActionOutcomeView;
import ai.fabric.intent.action.ActionResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Deliberately omits subscription IDs and raw address fields.
 */
@Component
public class UpdateAddressOutcomeProjector
    implements ActionOutcomeProjector {

    @Override
    public String actionName() {
        return AccountResolverSpecialists.UPDATE_ADDRESS_ACTION;
    }

    @Override
    public ActionOutcomeView project(ActionResult result) {
        if (result == null || !result.isSuccess()) {
            return new ActionOutcomeView(
                actionName(),
                "The address could not be updated.",
                Map.of("updated", false)
            );
        }
        Map<String, Object> source = result.getData() != null
            ? result.getData().toMap()
            : Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("updated", true);
        copy(source, safe, "addressType");
        copy(source, safe, "isValidated");
        return new ActionOutcomeView(
            actionName(),
            "The account address was updated successfully.",
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
