package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.specialist.manifest.SpecialistDirectOutputProjector;
import ai.fabric.execution.specialist.manifest.SpecialistFinalOutputValidator;
import ai.fabric.execution.specialist.manifest.SpecialistGroundingValidator;
import com.ai.fabric.realapps.agenticresolver.service.AccountResolutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AccountResolverSpecialistExtensions {

    public static final String GROUNDING_VALIDATOR =
        "account-readiness-grounding@1";
    public static final String DIRECT_PROJECTOR =
        "account-readiness-projector@1";
    public static final String FINAL_VALIDATOR =
        "account-readiness-consistency@1";

    @Bean
    AccountReadinessProjection accountReadinessProjection(
        ObjectMapper objectMapper,
        AccountResolutionService accountResolutionService
    ) {
        return new AccountReadinessProjection(
            objectMapper,
            accountResolutionService
        );
    }

    @Bean
    SpecialistGroundingValidator accountReadinessGroundingValidator(
        AccountReadinessProjection projection
    ) {
        return SpecialistGroundingValidator.named(
            GROUNDING_VALIDATOR,
            context -> projection.validateGrounding(context.result())
        );
    }

    @Bean
    SpecialistDirectOutputProjector accountReadinessProjector(
        AccountReadinessProjection projection
    ) {
        return SpecialistDirectOutputProjector.named(
            DIRECT_PROJECTOR,
            (result, evidence) -> projection.project(result)
        );
    }

    @Bean
    SpecialistFinalOutputValidator accountReadinessConsistencyValidator(
        AccountReadinessProjection projection
    ) {
        return SpecialistFinalOutputValidator.named(
            FINAL_VALIDATOR,
            context -> projection.validateFinalOutput(
                context.output(),
                context.sourceResult()
            )
        );
    }
}
