package ai.fabric.intent.action.policy;

import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;

import java.util.Map;

public interface ActionPostPolicyEngine {

    void handleSuccessfulAction(String actionName,
                                Map<String, Object> actionParams,
                                ActionResult actionResult,
                                ActionContext actionContext);
}
