package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.manager.ConversationManagerComponentId;
import ai.fabric.execution.manager.ConversationManagerTargetInputMapper;
import com.ai.fabric.realapps.incident.domain.ChangeRiskRequest;
import com.ai.fabric.realapps.incident.domain.IncidentManagerRequest;

public final class IncidentChangeManagerInputMapper
    implements ConversationManagerTargetInputMapper<
        IncidentManagerRequest,
        ChangeRiskRequest
    > {

    public static final ConversationManagerComponentId ID =
        ConversationManagerComponentId.of(
            "incident-manager-change-input",
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
    public Class<ChangeRiskRequest> targetInputType() {
        return ChangeRiskRequest.class;
    }

    @Override
    public ChangeRiskRequest map(IncidentManagerRequest request) {
        return new ChangeRiskRequest(
            request.question(),
            request.incident().incidentId(),
            request.incident().deploymentId(),
            request.incident().sourceRevision(),
            request.incident().changeEvidence()
        );
    }
}
