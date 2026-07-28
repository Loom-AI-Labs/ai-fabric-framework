package ai.fabric.execution.action;

import ai.fabric.intent.action.ActionResult;

/**
 * Application-owned boundary that prevents raw action state from becoming public.
 */
public interface ActionOutcomeProjector {

    String actionName();

    ActionOutcomeView project(ActionResult result);
}
