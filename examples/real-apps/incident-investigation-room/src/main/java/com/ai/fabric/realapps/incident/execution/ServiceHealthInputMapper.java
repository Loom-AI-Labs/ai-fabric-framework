package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.plan.PlanComponentId;
import ai.fabric.execution.plan.PlanStepInputMapper;
import ai.fabric.execution.plan.PlanStepOutputs;
import com.ai.fabric.realapps.incident.domain.IncidentPlanRequest;
import com.ai.fabric.realapps.incident.domain.ServiceHealthRequest;
import org.springframework.stereotype.Component;

@Component
public class ServiceHealthInputMapper
    implements PlanStepInputMapper<IncidentPlanRequest, ServiceHealthRequest> {

    @Override
    public PlanComponentId id() {
        return IncidentPlans.SERVICE_HEALTH_INPUT;
    }

    @Override
    public Class<IncidentPlanRequest> planInputType() {
        return IncidentPlanRequest.class;
    }

    @Override
    public Class<ServiceHealthRequest> stepInputType() {
        return ServiceHealthRequest.class;
    }

    @Override
    public ServiceHealthRequest map(
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
        return new ServiceHealthRequest(
            input.question(),
            input.incidentId(),
            input.deploymentId(),
            input.sourceRevision(),
            input.serviceEvidence()
        );
    }
}
