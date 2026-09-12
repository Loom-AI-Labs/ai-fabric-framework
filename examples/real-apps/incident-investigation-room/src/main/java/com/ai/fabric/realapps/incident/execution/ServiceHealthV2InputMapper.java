package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.plan.PlanComponentId;
import ai.fabric.execution.plan.PlanStepInputMapper;
import ai.fabric.execution.plan.PlanStepOutputs;
import com.ai.fabric.realapps.incident.domain.IncidentPlanRequest;
import com.ai.fabric.realapps.incident.domain.ServiceHealthInvestigationRequest;
import org.springframework.stereotype.Component;

@Component
public class ServiceHealthV2InputMapper
    implements PlanStepInputMapper<IncidentPlanRequest, ServiceHealthInvestigationRequest> {

    @Override
    public PlanComponentId id() {
        return IncidentPlans.SERVICE_HEALTH_INPUT_V2;
    }

    @Override
    public Class<IncidentPlanRequest> planInputType() {
        return IncidentPlanRequest.class;
    }

    @Override
    public Class<ServiceHealthInvestigationRequest> stepInputType() {
        return ServiceHealthInvestigationRequest.class;
    }

    @Override
    public ServiceHealthInvestigationRequest map(
        IncidentPlanRequest input,
        PlanStepOutputs approvedOutputs
    ) {
        if (approvedOutputs.size() != 0) {
            throw new IllegalArgumentException(
                "Service health is an independent first-order branch"
            );
        }
        if (IncidentPlans.SERVICE_HEALTH_STEP.equals(input.failingBranch())) {
            throw new IllegalStateException(
                "The controlled service-health source is unavailable"
            );
        }
        return new ServiceHealthInvestigationRequest(
            input.question(),
            input.incidentId(),
            input.deploymentId(),
            input.sourceRevision()
        );
    }
}
