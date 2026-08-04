package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.manager.ConversationManagerComponentId;
import ai.fabric.execution.manager.ConversationManagerTargetResultProjector;
import com.ai.fabric.realapps.incident.domain.ChangeRiskFinding;
import com.ai.fabric.realapps.incident.domain.IncidentManagerRequest;

public final class IncidentChangeManagerResultProjector
    implements ConversationManagerTargetResultProjector<
        IncidentManagerRequest,
        ChangeRiskFinding
    > {

    public static final ConversationManagerComponentId ID =
        ConversationManagerComponentId.of(
            "incident-manager-change-result",
            "1"
        );

    @Override
    public ConversationManagerComponentId id() {
        return ID;
    }

    @Override
    public Class<IncidentManagerRequest> managerRequestType() {
        return IncidentManagerRequest.class;
    }

    @Override
    public Class<ChangeRiskFinding> targetOutputType() {
        return ChangeRiskFinding.class;
    }

    @Override
    public String project(
        IncidentManagerRequest request,
        AIExecutionResult<ChangeRiskFinding> execution
    ) {
        ChangeRiskFinding output = execution.output();
        return output.summary() + " Change risk: " + output.riskLevel()
            + ". Suspected change: " + output.suspectedChange() + ".";
    }
}
