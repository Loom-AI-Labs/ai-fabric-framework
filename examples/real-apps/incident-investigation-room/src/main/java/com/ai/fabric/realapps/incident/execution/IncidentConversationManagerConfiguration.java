package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.manager.ConversationManagerDefinition;
import ai.fabric.execution.manager.ConversationManagerTarget;
import com.ai.fabric.realapps.incident.domain.IncidentManagerRequest;
import com.ai.fabric.realapps.incident.service.IncidentDecisionTraceStore;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class IncidentConversationManagerConfiguration {

    @Bean
    IncidentManagerInputAdapter incidentManagerInputAdapter() {
        return new IncidentManagerInputAdapter();
    }

    @Bean
    IncidentServiceManagerInputMapper incidentServiceManagerInputMapper() {
        return new IncidentServiceManagerInputMapper();
    }

    @Bean
    IncidentChangeManagerInputMapper incidentChangeManagerInputMapper() {
        return new IncidentChangeManagerInputMapper();
    }

    @Bean
    IncidentServiceManagerResultProjector
        incidentServiceManagerResultProjector() {
        return new IncidentServiceManagerResultProjector();
    }

    @Bean
    IncidentChangeManagerResultProjector
        incidentChangeManagerResultProjector() {
        return new IncidentChangeManagerResultProjector();
    }

    @Bean
    ConversationManagerDefinition<IncidentManagerRequest>
        incidentConversationManager(
            IncidentManagerInputAdapter inputAdapter,
            IncidentServiceManagerInputMapper serviceInput,
            IncidentServiceManagerResultProjector serviceResult,
            IncidentChangeManagerInputMapper changeInput,
            IncidentChangeManagerResultProjector changeResult
        ) {
        return new ConversationManagerDefinition<>(
            IncidentConversationManagers.INVESTIGATION,
            IncidentSpecialists.CONVERSATION_MANAGER,
            IncidentManagerRequest.class,
            inputAdapter,
            List.of(
                new ConversationManagerTarget<>(
                    IncidentSpecialists.SERVICE_HEALTH,
                    "Inspect the immutable service-health evidence for health, latency, errors, saturation, and availability.",
                    serviceInput,
                    serviceResult
                ),
                new ConversationManagerTarget<>(
                    IncidentSpecialists.CHANGE_RISK,
                    "Inspect the immutable recent-change and runbook evidence for release risk, likely change, and rollback context.",
                    changeInput,
                    changeResult
                )
            ),
            Duration.ofSeconds(55)
        );
    }

    @Bean
    IncidentManagerInputAdapterV2 incidentManagerInputAdapterV2() {
        return new IncidentManagerInputAdapterV2();
    }

    @Bean
    IncidentServiceManagerInputMapperV2
        incidentServiceManagerInputMapperV2() {
        return new IncidentServiceManagerInputMapperV2();
    }

    @Bean
    IncidentChangeManagerInputMapperV2
        incidentChangeManagerInputMapperV2() {
        return new IncidentChangeManagerInputMapperV2();
    }

    @Bean
    IncidentServiceManagerResultProjectorV2
        incidentServiceManagerResultProjectorV2(
            IncidentDecisionTraceStore traces
        ) {
        return new IncidentServiceManagerResultProjectorV2(traces);
    }

    @Bean
    IncidentChangeManagerResultProjectorV2
        incidentChangeManagerResultProjectorV2(
            IncidentDecisionTraceStore traces
        ) {
        return new IncidentChangeManagerResultProjectorV2(traces);
    }

    @Bean
    ConversationManagerDefinition<IncidentManagerRequest>
        incidentConversationManagerV2(
            IncidentManagerInputAdapterV2 inputAdapter,
            IncidentServiceManagerInputMapperV2 serviceInput,
            IncidentServiceManagerResultProjectorV2 serviceResult,
            IncidentChangeManagerInputMapperV2 changeInput,
            IncidentChangeManagerResultProjectorV2 changeResult
        ) {
        return new ConversationManagerDefinition<>(
            IncidentConversationManagers.INVESTIGATION_V2,
            IncidentSpecialists.CONVERSATION_MANAGER_V2,
            IncidentManagerRequest.class,
            inputAdapter,
            List.of(
                new ConversationManagerTarget<>(
                    IncidentSpecialists.SERVICE_HEALTH_V2,
                    "Investigate live health, metrics, alerts, latency, errors, saturation, and availability through approved read actions.",
                    serviceInput,
                    serviceResult
                ),
                new ConversationManagerTarget<>(
                    IncidentSpecialists.CHANGE_RISK_V2,
                    "Investigate releases, configuration changes, approvals, rollback safety, and scoped runbook guidance.",
                    changeInput,
                    changeResult
                )
            ),
            Duration.ofSeconds(70)
        );
    }
}
