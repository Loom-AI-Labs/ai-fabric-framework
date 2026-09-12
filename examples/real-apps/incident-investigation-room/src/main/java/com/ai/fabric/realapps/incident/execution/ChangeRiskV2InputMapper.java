package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.plan.PlanComponentId;
import ai.fabric.execution.plan.PlanStepInputMapper;
import ai.fabric.execution.plan.PlanStepOutputs;
import com.ai.fabric.realapps.incident.domain.ChangeRiskInvestigationRequest;
import com.ai.fabric.realapps.incident.domain.IncidentPlanRequest;
import org.springframework.stereotype.Component;

@Component
public class ChangeRiskV2InputMapper
    implements PlanStepInputMapper<IncidentPlanRequest, ChangeRiskInvestigationRequest> {

    @Override
    public PlanComponentId id() {
        return IncidentPlans.CHANGE_RISK_INPUT_V2;
    }

    @Override
    public Class<IncidentPlanRequest> planInputType() {
        return IncidentPlanRequest.class;
    }

    @Override
    public Class<ChangeRiskInvestigationRequest> stepInputType() {
        return ChangeRiskInvestigationRequest.class;
    }

    @Override
    public ChangeRiskInvestigationRequest map(
        IncidentPlanRequest input,
        PlanStepOutputs approvedOutputs
    ) {
        return new ChangeRiskInvestigationRequest(
            input.question(),
            input.incidentId(),
            input.deploymentId(),
            input.sourceRevision()
        );
    }
}
