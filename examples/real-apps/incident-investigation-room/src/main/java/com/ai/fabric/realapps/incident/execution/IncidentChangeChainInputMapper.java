package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.chain.SpecialistChainComponentId;
import ai.fabric.execution.chain.SpecialistChainTargetInputMapper;
import ai.fabric.execution.chain.SpecialistChainTargetRequest;
import com.ai.fabric.realapps.incident.domain.ChangeRiskInvestigationRequest;
import com.ai.fabric.realapps.incident.domain.IncidentManagerRequest;

public final class IncidentChangeChainInputMapper
    implements SpecialistChainTargetInputMapper<IncidentManagerRequest, ChangeRiskInvestigationRequest> {

    public static final SpecialistChainComponentId ID =
        SpecialistChainComponentId.of("incident-chain-change-input", "1");

    @Override
    public SpecialistChainComponentId id() {
        return ID;
    }

    @Override
    public Class<IncidentManagerRequest> chainRequestType() {
        return IncidentManagerRequest.class;
    }

    @Override
    public Class<ChangeRiskInvestigationRequest> targetInputType() {
        return ChangeRiskInvestigationRequest.class;
    }

    @Override
    public ChangeRiskInvestigationRequest map(
        IncidentManagerRequest request,
        SpecialistChainTargetRequest targetRequest
    ) {
        return new ChangeRiskInvestigationRequest(
            request.question(),
            request.incident().incidentId(),
            request.incident().deploymentId(),
            request.incident().sourceRevision()
        );
    }
}
