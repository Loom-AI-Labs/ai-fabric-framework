package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.specialist.manifest.SpecialistFinalOutputValidator;
import ai.fabric.execution.specialist.manifest.SpecialistOutputNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class IncidentSpecialistV2Extensions {

    public static final String SERVICE_VALIDATOR =
        "incident-service-action-citations@2";
    public static final String CHANGE_VALIDATOR =
        "incident-change-action-citations@2";
    public static final String SERVICE_NORMALIZER =
        "incident-service-canonical-trace@2";
    public static final String CHANGE_NORMALIZER =
        "incident-change-canonical-trace@2";

    @Bean
    IncidentActionCitationValidation incidentActionCitationValidation(
        ObjectMapper objectMapper
    ) {
        return new IncidentActionCitationValidation(objectMapper);
    }

    @Bean
    SpecialistFinalOutputValidator incidentServiceActionCitationValidator(
        IncidentActionCitationValidation validation
    ) {
        return SpecialistFinalOutputValidator.named(
            SERVICE_VALIDATOR,
            validation::validateService
        );
    }

    @Bean
    SpecialistFinalOutputValidator incidentChangeActionCitationValidator(
        IncidentActionCitationValidation validation
    ) {
        return SpecialistFinalOutputValidator.named(
            CHANGE_VALIDATOR,
            validation::validateChange
        );
    }

    @Bean
    SpecialistOutputNormalizer incidentServiceTraceNormalizer(
        IncidentActionCitationValidation validation
    ) {
        return SpecialistOutputNormalizer.named(
            SERVICE_NORMALIZER,
            validation::normalizeService
        );
    }

    @Bean
    SpecialistOutputNormalizer incidentChangeTraceNormalizer(
        IncidentActionCitationValidation validation
    ) {
        return SpecialistOutputNormalizer.named(
            CHANGE_NORMALIZER,
            validation::normalizeChange
        );
    }
}
