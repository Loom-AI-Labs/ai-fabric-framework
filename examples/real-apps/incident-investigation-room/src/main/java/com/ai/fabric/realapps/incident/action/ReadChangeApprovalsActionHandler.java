package com.ai.fabric.realapps.incident.action;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionAllowed;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.ActionFacts;
import ai.fabric.intent.action.annotation.Param;
import com.ai.fabric.realapps.incident.domain.IncidentEventType;
import java.util.Map;
import java.util.Set;

@AIAction(
    name = ReadChangeApprovalsActionHandler.NAME,
    description = "Use when the question asks about approvals, rollback authorization, or approved change controls within the backend-authorized incident boundary",
    category = "incident-change-risk",
    accessMode = ActionAccessMode.READ,
    requiresConfirmation = false,
    readActionResolutionEligible = true
)
public class ReadChangeApprovalsActionHandler {

    public static final String NAME = "read_change_approvals";
    private final IncidentReadActionSupport support;

    public ReadChangeApprovalsActionHandler(IncidentReadActionSupport support) {
        this.support = support;
    }

    @ActionAllowed
    public boolean allowed(ActionContext context) {
        return support.allowed(context, NAME);
    }

    @ActionExecute
    public ActionResult execute(
        @Param(value = "windowMinutes", description = "Lookback window from 5 to 180 minutes", min = 5, max = 180)
        Integer windowMinutes,
        @Param(value = "limit", description = "Maximum candidate events from 1 to 6", min = 1, max = 6)
        Integer limit,
        ActionContext context
    ) {
        return support.execute(
            context,
            NAME,
            Set.of(IncidentEventType.CHANGE_APPROVAL),
            windowMinutes,
            limit
        );
    }

    @ActionFacts
    public Map<String, Object> facts(ActionResult result, ActionContext context) {
        return support.facts(result);
    }
}
