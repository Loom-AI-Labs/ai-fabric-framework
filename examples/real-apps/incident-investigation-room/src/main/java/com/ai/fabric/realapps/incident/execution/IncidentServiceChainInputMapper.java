package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.chain.SpecialistChainComponentId;
import ai.fabric.execution.chain.SpecialistChainTargetInputMapper;
import ai.fabric.execution.chain.SpecialistChainTargetRequest;
import com.ai.fabric.realapps.incident.domain.IncidentManagerRequest;
import com.ai.fabric.realapps.incident.domain.ServiceHealthInvestigationRequest;

public final class IncidentServiceChainInputMapper
    implements SpecialistChainTargetInputMapper<IncidentManagerRequest, ServiceHealthInvestigationRequest> {

    public static final SpecialistChainComponentId ID =
        SpecialistChainComponentId.of("incident-chain-service-input", "1");

    @Override
    public SpecialistChainComponentId id() {
        return ID;
    }

    @Override
    public Class<IncidentManagerRequest> chainRequestType() {
        return IncidentManagerRequest.class;
    }

    @Override
    public Class<ServiceHealthInvestigationRequest> targetInputType() {
        return ServiceHealthInvestigationRequest.class;
    }

    @Override
    public ServiceHealthInvestigationRequest map(
        IncidentManagerRequest request,
        SpecialistChainTargetRequest targetRequest
    ) {
        return new ServiceHealthInvestigationRequest(
            request.question(),
            request.incident().incidentId(),
            request.incident().deploymentId(),
            request.incident().sourceRevision()
        );
    }
}
