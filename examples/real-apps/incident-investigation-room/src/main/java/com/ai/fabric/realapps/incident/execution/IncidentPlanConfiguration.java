package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.plan.ExecutionPlanDefinition;
import ai.fabric.execution.plan.FanInPolicy;
import ai.fabric.execution.plan.ParallelPlanStep;
import ai.fabric.execution.plan.PlanStage;
import ai.fabric.execution.plan.SpecialistPlanStep;
import com.ai.fabric.realapps.incident.domain.ChangeRiskFinding;
import com.ai.fabric.realapps.incident.domain.ChangeRiskRequest;
import com.ai.fabric.realapps.incident.domain.IncidentAssessment;
import com.ai.fabric.realapps.incident.domain.IncidentPlanRequest;
import com.ai.fabric.realapps.incident.domain.ServiceHealthFinding;
import com.ai.fabric.realapps.incident.domain.ServiceHealthRequest;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class IncidentPlanConfiguration {

    @Bean
    ExecutionPlanDefinition<IncidentPlanRequest, IncidentAssessment>
        incidentSequentialPlan() {
        return definition(
            IncidentPlans.SEQUENTIAL,
            List.of(serviceBranch(), changeBranch())
        );
    }

    @Bean
    ExecutionPlanDefinition<IncidentPlanRequest, IncidentAssessment>
        incidentParallelPlan() {
        return definition(
            IncidentPlans.PARALLEL,
            List.of(new ParallelPlanStep(
                IncidentPlans.PARALLEL_STAGE,
                List.of(serviceBranch(), changeBranch()),
                FanInPolicy.ALL_REQUIRED,
                2
            ))
        );
    }

    private ExecutionPlanDefinition<IncidentPlanRequest, IncidentAssessment>
    definition(
        ai.fabric.execution.plan.ExecutionPlanId id,
        List<? extends PlanStage> stages
    ) {
        return new ExecutionPlanDefinition<>(
            id,
            IncidentPlanRequest.class,
            IncidentAssessment.class,
            stages,
            IncidentPlans.ASSESSMENT_RESULT,
            Duration.ofSeconds(50)
        );
    }

    private SpecialistPlanStep serviceBranch() {
        return new SpecialistPlanStep(
            IncidentPlans.SERVICE_HEALTH_STEP,
            IncidentSpecialists.SERVICE_HEALTH,
            ServiceHealthRequest.class,
            ServiceHealthFinding.class,
            IncidentPlans.SERVICE_HEALTH_INPUT
        );
    }

    private SpecialistPlanStep changeBranch() {
        return new SpecialistPlanStep(
            IncidentPlans.CHANGE_RISK_STEP,
            IncidentSpecialists.CHANGE_RISK,
            ChangeRiskRequest.class,
            ChangeRiskFinding.class,
            IncidentPlans.CHANGE_RISK_INPUT
        );
    }
}
