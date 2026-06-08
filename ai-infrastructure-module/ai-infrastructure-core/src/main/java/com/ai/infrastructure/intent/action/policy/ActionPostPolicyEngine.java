package com.ai.infrastructure.intent.action.policy;

import com.ai.infrastructure.intent.action.ActionContext;
import com.ai.infrastructure.intent.action.ActionResult;

import java.util.Map;

public interface ActionPostPolicyEngine {

    void handleSuccessfulAction(String actionName,
                                Map<String, Object> actionParams,
                                ActionResult actionResult,
                                ActionContext actionContext);
}
