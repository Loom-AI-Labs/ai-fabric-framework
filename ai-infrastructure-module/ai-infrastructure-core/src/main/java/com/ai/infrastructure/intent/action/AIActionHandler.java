package com.ai.infrastructure.intent.action;

import java.util.Map;
import java.util.Optional;

/**
 * Internal runtime contract used by the orchestrator to execute registered actions.
 *
 * <p>Framework users declare actions via {@code @AIAction} annotated beans; they do not implement this interface.</p>
 */
public interface AIActionHandler {

    AIActionMetaData getActionMetadata();

    boolean requiresConfirmation();

    default boolean validateActionAllowed(ActionContext context) {
        return true;
    }

    String getConfirmationMessage(Map<String, Object> params, ActionContext context);

    ActionResult executeAction(Map<String, Object> params, ActionContext context);

    default Optional<Map<String, Object>> buildPostActionLlmFacts(ActionResult actionResult, ActionContext context) {
        return Optional.empty();
    }

    default Map<String, Object> actionRuntimeConfig() {
        return Map.of();
    }

    default ActionResult handleError(Exception e, ActionContext context) {
        return ActionResult.builder()
            .success(false)
            .message(e != null ? e.getMessage() : "Action failed")
            .errorCode("ACTION_EXECUTION_FAILED")
            .build();
    }
}
