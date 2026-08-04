package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.manager.ConversationManagerComponentId;
import ai.fabric.execution.manager.ConversationManagerTargetInputMapper;
import com.ai.fabric.realapps.incident.domain.IncidentManagerRequest;
import com.ai.fabric.realapps.incident.domain.ServiceHealthRequest;

public final class IncidentServiceManagerInputMapper
    implements ConversationManagerTargetInputMapper<
        IncidentManagerRequest,
        ServiceHealthRequest
    > {

    public static final ConversationManagerComponentId ID =
        ConversationManagerComponentId.of(
            "incident-manager-service-input",
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
    public Class<ServiceHealthRequest> targetInputType() {
        return ServiceHealthRequest.class;
    }

    @Override
    public ServiceHealthRequest map(IncidentManagerRequest request) {
        return new ServiceHealthRequest(
            request.question(),
            request.incident().incidentId(),
            request.incident().deploymentId(),
            request.incident().sourceRevision(),
            request.incident().serviceEvidence()
        );
    }
}
