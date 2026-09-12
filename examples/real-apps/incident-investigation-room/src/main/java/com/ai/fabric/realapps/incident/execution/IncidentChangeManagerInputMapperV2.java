package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.manager.ConversationManagerComponentId;
import ai.fabric.execution.manager.ConversationManagerTargetInputMapper;
import com.ai.fabric.realapps.incident.domain.ChangeRiskInvestigationRequest;
import com.ai.fabric.realapps.incident.domain.IncidentManagerRequest;

public final class IncidentChangeManagerInputMapperV2
    implements ConversationManagerTargetInputMapper<IncidentManagerRequest, ChangeRiskInvestigationRequest> {

    public static final ConversationManagerComponentId ID =
        ConversationManagerComponentId.of("incident-manager-change-input", "2");

    @Override
    public ConversationManagerComponentId id() {
        return ID;
    }

    @Override
    public Class<IncidentManagerRequest> managerRequestType() {
        return IncidentManagerRequest.class;
    }

    @Override
    public Class<ChangeRiskInvestigationRequest> targetInputType() {
        return ChangeRiskInvestigationRequest.class;
    }

    @Override
    public ChangeRiskInvestigationRequest map(IncidentManagerRequest request) {
        return new ChangeRiskInvestigationRequest(
            request.question(),
            request.incident().incidentId(),
            request.incident().deploymentId(),
            request.incident().sourceRevision()
        );
    }
}
