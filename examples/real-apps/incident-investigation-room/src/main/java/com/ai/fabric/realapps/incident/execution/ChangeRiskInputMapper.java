package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.plan.PlanComponentId;
import ai.fabric.execution.plan.PlanStepInputMapper;
import ai.fabric.execution.plan.PlanStepOutputs;
import com.ai.fabric.realapps.incident.domain.ChangeRiskRequest;
import com.ai.fabric.realapps.incident.domain.IncidentPlanRequest;
import org.springframework.stereotype.Component;

@Component
public class ChangeRiskInputMapper
    implements PlanStepInputMapper<IncidentPlanRequest, ChangeRiskRequest> {

    @Override
    public PlanComponentId id() {
        return IncidentPlans.CHANGE_RISK_INPUT;
    }

    @Override
    public Class<IncidentPlanRequest> planInputType() {
        return IncidentPlanRequest.class;
    }

    @Override
    public Class<ChangeRiskRequest> stepInputType() {
        return ChangeRiskRequest.class;
    }

    @Override
    public ChangeRiskRequest map(
        IncidentPlanRequest input,
        PlanStepOutputs approvedOutputs
    ) {
        if (IncidentPlans.CHANGE_RISK_STEP.equals(input.failingBranch())) {
            throw new IllegalStateException(
                "The controlled change-risk source is unavailable"
            );
        }
        return new ChangeRiskRequest(
            input.question(),
            input.incidentId(),
            input.deploymentId(),
            input.sourceRevision(),
            input.changeEvidence()
        );
    }
}
