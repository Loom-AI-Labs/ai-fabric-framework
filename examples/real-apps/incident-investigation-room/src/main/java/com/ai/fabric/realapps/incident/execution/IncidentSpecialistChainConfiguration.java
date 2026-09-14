package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.chain.SpecialistChainConversationPolicy;
import ai.fabric.execution.chain.SpecialistChainDefinition;
import ai.fabric.execution.chain.SpecialistChainLimits;
import ai.fabric.execution.chain.SpecialistChainTarget;
import com.ai.fabric.realapps.incident.domain.IncidentManagerRequest;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class IncidentSpecialistChainConfiguration {

    @Bean
    IncidentChainInputAdapter incidentChainInputAdapter() {
        return new IncidentChainInputAdapter();
    }

    @Bean
    IncidentServiceChainInputMapper incidentServiceChainInputMapper() {
        return new IncidentServiceChainInputMapper();
    }

    @Bean
    IncidentChangeChainInputMapper incidentChangeChainInputMapper() {
        return new IncidentChangeChainInputMapper();
    }

    @Bean
    IncidentServiceChainResultProjector
        incidentServiceChainResultProjector() {
        return new IncidentServiceChainResultProjector();
    }

    @Bean
    IncidentChangeChainResultProjector
        incidentChangeChainResultProjector() {
        return new IncidentChangeChainResultProjector();
    }

    @Bean
    SpecialistChainDefinition<IncidentManagerRequest>
        incidentSmartInvestigationChain(
            IncidentChainInputAdapter inputAdapter,
            IncidentServiceChainInputMapper serviceInput,
            IncidentServiceChainResultProjector serviceResult,
            IncidentChangeChainInputMapper changeInput,
            IncidentChangeChainResultProjector changeResult
        ) {
        return new SpecialistChainDefinition<>(
            IncidentSpecialistChains.SMART_INVESTIGATION,
            IncidentSpecialists.CHAIN_MANAGER_V3,
            IncidentManagerRequest.class,
            inputAdapter,
            List.of(
                new SpecialistChainTarget<>(
                    IncidentSpecialists.SERVICE_HEALTH_V2,
                    "Inspect current metrics, alerts, latency, errors, saturation, and availability from approved live sources.",
                    serviceInput,
                    serviceResult,
                    true,
                    true,
                    true
                ),
                new SpecialistChainTarget<>(
                    IncidentSpecialists.CHANGE_RISK_V2,
                    "Inspect recent deployments, approvals, rollback safety, and tenant-scoped runbook guidance.",
                    changeInput,
                    changeResult,
                    true,
                    true,
                    true
                )
            ),
            new SpecialistChainLimits(
                Duration.ofSeconds(70),
                4,
                2,
                2,
                1,
                8_000
            ),
            SpecialistChainConversationPolicy.REQUIRED
        );
    }
}
