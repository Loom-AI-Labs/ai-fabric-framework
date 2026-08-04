package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.manager.ConversationManagerDefinition;
import ai.fabric.execution.manager.ConversationManagerTarget;
import com.ai.fabric.realapps.incident.domain.IncidentManagerRequest;
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
}
