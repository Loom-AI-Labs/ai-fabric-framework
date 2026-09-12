package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.manager.ConversationManagerComponentId;
import ai.fabric.execution.manager.ConversationManagerTargetResultProjector;
import com.ai.fabric.realapps.incident.domain.IncidentManagerRequest;
import com.ai.fabric.realapps.incident.domain.ServiceHealthInvestigationFinding;
import com.ai.fabric.realapps.incident.service.IncidentDecisionTraceStore;

public final class IncidentServiceManagerResultProjectorV2
    implements ConversationManagerTargetResultProjector<IncidentManagerRequest, ServiceHealthInvestigationFinding> {

    public static final ConversationManagerComponentId ID =
        ConversationManagerComponentId.of("incident-manager-service-result", "2");
    private final IncidentDecisionTraceStore traces;

    public IncidentServiceManagerResultProjectorV2(
        IncidentDecisionTraceStore traces
    ) {
        this.traces = traces;
    }

    @Override
    public ConversationManagerComponentId id() {
        return ID;
    }

    @Override
    public Class<IncidentManagerRequest> managerRequestType() {
        return IncidentManagerRequest.class;
    }

    @Override
    public Class<ServiceHealthInvestigationFinding> targetOutputType() {
        return ServiceHealthInvestigationFinding.class;
    }

    @Override
    public String project(
        IncidentManagerRequest request,
        AIExecutionResult<ServiceHealthInvestigationFinding> execution
    ) {
        ServiceHealthInvestigationFinding output = execution.output();
        traces.record(
            execution.invocationId(),
            execution.specialistId().toString(),
            output
        );
        return output.summary() + " Health: " + output.healthStatus()
            + ", severity: " + output.severity() + ".";
    }
}
