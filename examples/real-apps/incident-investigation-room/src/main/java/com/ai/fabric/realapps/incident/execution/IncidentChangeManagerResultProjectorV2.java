package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.manager.ConversationManagerComponentId;
import ai.fabric.execution.manager.ConversationManagerTargetResultProjector;
import com.ai.fabric.realapps.incident.domain.ChangeRiskInvestigationFinding;
import com.ai.fabric.realapps.incident.domain.IncidentManagerRequest;
import com.ai.fabric.realapps.incident.service.IncidentDecisionTraceStore;

public final class IncidentChangeManagerResultProjectorV2
    implements ConversationManagerTargetResultProjector<IncidentManagerRequest, ChangeRiskInvestigationFinding> {

    public static final ConversationManagerComponentId ID =
        ConversationManagerComponentId.of("incident-manager-change-result", "2");
    private final IncidentDecisionTraceStore traces;

    public IncidentChangeManagerResultProjectorV2(
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
    public Class<ChangeRiskInvestigationFinding> targetOutputType() {
        return ChangeRiskInvestigationFinding.class;
    }

    @Override
    public String project(
        IncidentManagerRequest request,
        AIExecutionResult<ChangeRiskInvestigationFinding> execution
    ) {
        ChangeRiskInvestigationFinding output = execution.output();
        traces.record(
            execution.invocationId(),
            execution.specialistId().toString(),
            output
        );
        return output.summary() + " Change risk: " + output.riskLevel()
            + ". Suspected change: " + output.suspectedChange() + ".";
    }
}
