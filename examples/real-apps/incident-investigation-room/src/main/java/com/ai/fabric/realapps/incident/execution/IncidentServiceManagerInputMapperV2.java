package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.manager.ConversationManagerComponentId;
import ai.fabric.execution.manager.ConversationManagerTargetInputMapper;
import com.ai.fabric.realapps.incident.domain.IncidentManagerRequest;
import com.ai.fabric.realapps.incident.domain.ServiceHealthInvestigationRequest;

public final class IncidentServiceManagerInputMapperV2
    implements ConversationManagerTargetInputMapper<IncidentManagerRequest, ServiceHealthInvestigationRequest> {

    public static final ConversationManagerComponentId ID =
        ConversationManagerComponentId.of("incident-manager-service-input", "2");

    @Override
    public ConversationManagerComponentId id() {
        return ID;
    }

    @Override
    public Class<IncidentManagerRequest> managerRequestType() {
        return IncidentManagerRequest.class;
    }

    @Override
    public Class<ServiceHealthInvestigationRequest> targetInputType() {
        return ServiceHealthInvestigationRequest.class;
    }

    @Override
    public ServiceHealthInvestigationRequest map(IncidentManagerRequest request) {
        return new ServiceHealthInvestigationRequest(
            request.question(),
            request.incident().incidentId(),
            request.incident().deploymentId(),
            request.incident().sourceRevision()
        );
    }
}
