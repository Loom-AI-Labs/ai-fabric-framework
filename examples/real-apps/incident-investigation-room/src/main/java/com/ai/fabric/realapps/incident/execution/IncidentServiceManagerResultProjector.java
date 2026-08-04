package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.manager.ConversationManagerComponentId;
import ai.fabric.execution.manager.ConversationManagerTargetResultProjector;
import com.ai.fabric.realapps.incident.domain.IncidentManagerRequest;
import com.ai.fabric.realapps.incident.domain.ServiceHealthFinding;

public final class IncidentServiceManagerResultProjector
    implements ConversationManagerTargetResultProjector<
        IncidentManagerRequest,
        ServiceHealthFinding
    > {

    public static final ConversationManagerComponentId ID =
        ConversationManagerComponentId.of(
            "incident-manager-service-result",
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
    public Class<ServiceHealthFinding> targetOutputType() {
        return ServiceHealthFinding.class;
    }

    @Override
    public String project(
        IncidentManagerRequest request,
        AIExecutionResult<ServiceHealthFinding> execution
    ) {
        ServiceHealthFinding output = execution.output();
        return output.summary() + " Health: " + output.healthStatus()
            + ", severity: " + output.severity() + ".";
    }
}
